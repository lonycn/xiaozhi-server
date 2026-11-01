"""Bounded provider proposals. Execution authority always remains with the manager."""
import json
import re

from .provider import ProviderFailure


LIMITS = dict(max_rounds=3, max_calls=8, max_argument_bytes=16384,
              max_result_bytes=65536, timeout_seconds=30, max_output_chars=120000)
NAME = re.compile(r'[A-Za-z_][A-Za-z0-9_-]{0,63}')
CALL_ID = re.compile(r'[A-Za-z0-9_-]{1,128}')


def encoded(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':'))


def supported(model):
    # Explicit documented Qwen families only; model selection is still enforced by Worker.
    return isinstance(model, str) and bool(re.fullmatch(
        r'qwen(?:-(?:flash|plus|max|turbo)|3\.[678]-max|3\.[567]-plus|'
        r'3\.[5678]-flash|3-(?:max|coder|omni-flash)|'
        r'3\.8-omni-flash|3-vl-(?:plus|flash))(?:-[a-z0-9-]+)?', model))


def policy(task):
    tools = task.get('tools')
    if not tools:
        return None
    if not supported(task.get('model')):
        raise ProviderFailure('UNSUPPORTED_TOOL')
    if not isinstance(tools, list) or len(tools) > 32:
        raise ProviderFailure('TOOL_PROTOCOL_ERROR')
    names = set()
    try:
        if len(encoded(tools).encode('utf-8')) > 65536:
            raise ValueError()
        for tool in tools:
            function = tool['function']
            name = function['name']
            if (tool['type'] != 'function' or not isinstance(name, str) or not NAME.fullmatch(name)
                    or name in names or not isinstance(function.get('parameters'), dict)
                    or function['parameters'].get('type') != 'object'):
                raise ValueError()
            names.add(name)
        limits = dict(LIMITS)
        supplied = task.get('tool_policy') or {}
        if not isinstance(supplied, dict):
            raise ValueError()
        for key, cap in LIMITS.items():
            value = supplied.get(key, cap)
            if type(value) is not int or value < 1:
                raise ValueError()
            limits[key] = min(value, cap)
        for key in ('attempt', 'control_epoch'):
            value = task.get(key)
            if not ((type(value) is int and value >= 0) or
                    (isinstance(value, str) and re.fullmatch(r'[0-9]{1,19}', value))):
                raise ValueError()
        return names, limits
    except (KeyError, TypeError, ValueError, UnicodeError, RecursionError):
        raise ProviderFailure('TOOL_PROTOCOL_ERROR') from None


def strict_arguments(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError()
            result[key] = value
        return result
    def invalid_constant(_):
        raise ValueError()
    try:
        result = json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid_constant)
        if not isinstance(result, dict):
            raise ValueError()
        encoded(result).encode('utf-8')
        return result
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise ProviderFailure('TOOL_ARGUMENTS_INVALID') from None


class Proposals:
    def __init__(self, names, limits):
        self.names, self.limits = names, limits
        self.parts, self.size = {}, 0

    def add(self, fragments):
        if not isinstance(fragments, list):
            raise ProviderFailure('TOOL_PROTOCOL_ERROR')
        if len(fragments) > self.limits['max_calls']:
            raise ProviderFailure('TOOL_LIMIT_EXCEEDED')
        for fragment in fragments:
            index = getattr(fragment, 'index', None)
            if type(index) is not int or not 0 <= index < self.limits['max_calls']:
                raise ProviderFailure('TOOL_PROTOCOL_ERROR')
            item = self.parts.setdefault(index, dict(id='', name='', arguments=''))
            kind = getattr(fragment, 'type', None)
            if kind not in (None, 'function'):
                raise ProviderFailure('UNSUPPORTED_TOOL')
            call_id = getattr(fragment, 'id', None)
            # Qwen compatible streams emit an empty id on continuation chunks.
            # Only a nonempty id establishes identity; completion still requires one.
            if call_id not in (None, ''):
                if not isinstance(call_id, str) or not CALL_ID.fullmatch(call_id) or (item['id'] and item['id'] != call_id):
                    raise ProviderFailure('TOOL_PROTOCOL_ERROR')
                item['id'] = call_id
            function = getattr(fragment, 'function', None)
            if function is not None:
                for key in ('name', 'arguments'):
                    value = getattr(function, key, None)
                    if value is None:
                        continue
                    if not isinstance(value, str):
                        raise ProviderFailure('TOOL_PROTOCOL_ERROR')
                    # Some compatible streams repeat the stable name. Arguments are deltas.
                    if key == 'name' and value == item[key]:
                        continue
                    try:
                        self.size += len(value.encode('utf-8'))
                    except UnicodeError:
                        raise ProviderFailure('TOOL_PROTOCOL_ERROR') from None
                    if self.size > self.limits['max_argument_bytes']:
                        raise ProviderFailure('TOOL_LIMIT_EXCEEDED')
                    item[key] += value
                    if key == 'name' and len(item[key]) > 64:
                        raise ProviderFailure('TOOL_PROTOCOL_ERROR')

    def complete(self, seen):
        calls, ids = [], set()
        if sorted(self.parts) != list(range(len(self.parts))):
            raise ProviderFailure('TOOL_PROTOCOL_ERROR')
        for item in (self.parts[i] for i in sorted(self.parts)):
            if not item['id'] or item['id'] in ids or item['id'] in seen or item['name'] not in self.names:
                raise ProviderFailure('TOOL_PROTOCOL_ERROR')
            ids.add(item['id'])
            calls.append(dict(provider_call_id=item['id'], name=item['name'],
                              arguments=strict_arguments(item['arguments'])))
        return calls


def result_messages(response, calls, maximum):
    try:
        if not isinstance(response, dict):
            raise ValueError()
        if len(encoded(response).encode('utf-8')) > maximum:
            raise ProviderFailure('TOOL_LIMIT_EXCEEDED')
        results = response['results']
        if not isinstance(results, list) or len(results) != len(calls):
            raise ValueError()
        mapped = {}
        for result in results:
            call_id = result['provider_call_id']
            if call_id in mapped:
                raise ValueError()
            mapped[call_id] = result
        messages = []
        for call in calls:
            result = mapped[call['provider_call_id']]
            if (result['name'] != call['name'] or result['status'] not in ('succeeded', 'failed')
                    or not isinstance(result.get('output'), dict)
                    or (result.get('error_code') is not None and not isinstance(result['error_code'], str))):
                raise ValueError()
            messages.append(dict(role='tool', tool_call_id=call['provider_call_id'], content=encoded(result)))
        return messages
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
        raise ProviderFailure('TOOL_PROTOCOL_ERROR') from None
