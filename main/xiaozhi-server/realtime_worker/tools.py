"""One cancellable manager request, serialized provider ACKs, no local tool execution."""
import asyncio
import hashlib
import json
import re
import time
from .control import Fault, AuthorityLost
from .tool_contract import tool_item

CAPS = dict(max_rounds=3, max_calls=6, max_argument_bytes=4096, max_result_bytes=32768,
            timeout_seconds=30, max_output_chars=120000, max_session_calls=24, max_session_result_bytes=131072,
            max_session_provider_steps=32, max_session_tokens=100000)


def wire(value):
    try:
        raw = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':'))
        raw.encode('utf-8')
        return raw
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise Fault('TOOL_PROTOCOL_ERROR') from None


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,200}', value):
        raise Fault('TOOL_PROTOCOL_ERROR')
    return value


def arguments(raw, maximum):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result: raise ValueError()
            result[key] = value
        return result
    try:
        if not isinstance(raw, str) or len(raw.encode('utf-8')) > maximum: raise ValueError()
        result = json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(result, dict): raise ValueError()
        wire(result)
        return result
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise Fault('TOOL_ARGUMENTS_INVALID') from None


def declarations(tools):
    if not isinstance(tools, list) or not 0 < len(tools) <= 32 or len(wire(tools).encode()) > 65536:
        raise Fault('UNSUPPORTED_TOOL')
    names = set()
    for tool in tools:
        function = tool.get('function') if isinstance(tool, dict) else None
        if (not isinstance(tool, dict) or tool.get('type') != 'function' or not isinstance(function, dict)
                or not isinstance(function.get('name'), str)
                or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_-]{0,63}', function['name'])
                or function['name'] in names or not isinstance(function.get('parameters'), dict)
                or function['parameters'].get('type') != 'object'):
            raise Fault('UNSUPPORTED_TOOL')
        names.add(function['name'])
    return names


class RealtimeTools:
    def __init__(self, session):
        self.session = session
        self.tools = session.task['tools']
        self.names = declarations(self.tools)
        policy = session.task.get('tool_policy')
        if not isinstance(policy, dict): raise Fault('UNSUPPORTED_TOOL')
        self.limits = {}
        for key, maximum in CAPS.items():
            value = policy.get(key, maximum)
            if type(value) is not int or value < 1: raise Fault('TOOL_PROTOCOL_ERROR')
            self.limits[key] = min(value, maximum)
        generation = session.task.get('turn_generation')
        if type(generation) is not int or not 0 <= generation < 2**63: raise Fault('TOOL_PROTOCOL_ERROR')
        self.generation = generation
        self.calls, self.pending = {}, {}
        self.seen_calls, self.echoes, self.abandoned = set(), {}, set()
        self.discarded_outputs = {}
        self.result_items = set()
        self.job = None
        self.jobs = set()
        self.waiting = None
        self.continuation = None
        self.round = self.turn_calls = self.turn_bytes = self.session_calls = self.session_bytes = 0
        self.steps = self.known_tokens = 0
        self.turn_output = self.step_output = 0
        self.turn_usage = []
        self.prefix = ''
        self.turn_started = None

    def busy(self):
        return bool(self.waiting or self.pending or self.continuation)

    def synchronize(self, value):
        if type(value) is not int or value < self.generation or value >= 2**63:
            raise Fault('TOOL_PROTOCOL_ERROR')
        if value == self.generation: return
        uncertain_continuation = self.continuation is not None and self.session.waiting_response
        self.cancel()
        self.generation = value
        # A response.create already sent on the old generation has no provider
        # correlation field we can safely reassign to new audio. End explicitly.
        if uncertain_continuation: raise Fault('TOOL_RESULT_UNCERTAIN')

    def cancel(self):
        if self.job and not self.job.done(): self.job.cancel()
        if self.waiting: self.abandoned.add(self.waiting['response_id'])
        if self.session.response:
            self.session.response['suppressed'] = True
            self.abandoned.add(self.session.response['id'])
        self.calls.clear()
        for value in self.pending.values(): self.discarded_outputs[value['call_id']] = value
        self.pending.clear()
        self.waiting = self.continuation = None
        self.round = self.turn_calls = self.turn_bytes = 0
        self.turn_output = self.step_output = 0
        self.turn_usage = []
        self.prefix = ''
        self.turn_started = None
        self.session.pending_since = None

    async def input_started(self, source):
        previous = self.generation
        receipt = await self.session.canonical(source, 'input.started', turn_generation=previous)
        value = receipt.get('turn_generation')
        if type(value) is not int or value <= previous: raise Fault('TOOL_PROTOCOL_ERROR')
        self.synchronize(value)

    def response_started(self, response):
        self.calls = {}
        self.steps += 1
        self.step_output = 0
        if self.steps > self.limits['max_session_provider_steps']: raise Fault('TOOL_LIMIT_EXCEEDED')
        response['generation'] = self.generation
        extra = {'turn_generation': self.generation}
        if self.continuation:
            extra['continuation_id'] = self.continuation
            self.continuation = None
        return extra

    def record(self, response_id, item_id, call_id, name=None):
        identifier(response_id);identifier(item_id);identifier(call_id)
        response = self.session.response
        if response_id in self.abandoned: return None
        if not response or response['id'] != response_id or response.get('generation') != self.generation:
            raise Fault('TOOL_PROTOCOL_ERROR')
        if call_id in self.seen_calls: raise Fault('TOOL_PROTOCOL_ERROR')
        part = self.calls.get(call_id)
        if part is None:
            if len(self.calls) >= self.limits['max_calls']: raise Fault('TOOL_LIMIT_EXCEEDED')
            if any(item['provider_item_id'] == item_id for item in self.calls.values()): raise Fault('TOOL_PROTOCOL_ERROR')
            part = dict(provider_call_id=call_id, provider_item_id=item_id, name=None, raw='', complete=None)
            self.calls[call_id] = part
        if part['provider_item_id'] != item_id: raise Fault('TOOL_PROTOCOL_ERROR')
        if name is not None:
            if name not in self.names or part['name'] not in (None, name): raise Fault('UNSUPPORTED_TOOL')
            part['name'] = name
        return part

    def complete(self, part, raw):
        parsed = arguments(raw, self.limits['max_argument_bytes'])
        if part['raw'] and arguments(part['raw'], self.limits['max_argument_bytes']) != parsed:
            raise Fault('TOOL_ARGUMENTS_INVALID')
        if part['complete'] is not None and part['complete'] != parsed: raise Fault('TOOL_ARGUMENTS_INVALID')
        part['complete'] = parsed

    async def provider_event(self, event):
        kind = event['type']
        if kind.startswith('response.mcp'): raise Fault('UNSUPPORTED_TOOL')
        early_tool = kind.startswith('response.function_call') or (
            kind in ('response.output_item.added','response.output_item.done') and tool_item(event.get('item')))
        if early_tool and not self.session.response and self.session.waiting_response:
            pending = self.session.early
            if len(pending)>=8 or sum(len(wire(value)) for value in pending)+len(wire(event))>512000:
                raise Fault('TOOL_LIMIT_EXCEEDED')
            pending.append(event);self.session.early_since=self.session.early_since or time.monotonic()
            return True
        if kind in ('session.created', 'session.updated'):
            echoed = event.get('session', {}).get('tools')
            if echoed is not None and echoed != [] and echoed != self.tools: raise Fault('TOOL_PROTOCOL_ERROR')
            return False
        item = event.get('item')
        if kind == 'conversation.item.created' and isinstance(item, dict):
            if item.get('type') == 'function_call_output':
                await self.output_ack(event)
                return True
            if item.get('type') == 'function_call':
                response_id = event.get('response_id') or (self.session.response or {}).get('id')
                part = self.record(response_id, item.get('id'), item.get('call_id'), item.get('name'))
                if part is not None: self.session.remember_item(item['id'], hashlib.sha256(wire([item,event.get('previous_item_id')]).encode()).digest())
                return True
            if tool_item(item): raise Fault('UNSUPPORTED_TOOL')
        if kind in ('response.output_item.added', 'response.output_item.done') and tool_item(item):
            if item.get('type') != 'function_call': raise Fault('UNSUPPORTED_TOOL')
            part = self.record(event.get('response_id'), item.get('id'), item.get('call_id'), item.get('name'))
            if part is not None and kind.endswith('.done'): self.complete(part, item.get('arguments'))
            return True
        if kind.startswith('response.function_call'):
            part = self.record(event.get('response_id'), event.get('item_id'), event.get('call_id'), event.get('name'))
            if part is None: return True
            if kind == 'response.function_call_arguments.delta':
                delta = event.get('delta')
                if not isinstance(delta, str) or part['complete'] is not None: raise Fault('TOOL_PROTOCOL_ERROR')
                part['raw'] += delta
                try: size = len(part['raw'].encode('utf-8'))
                except UnicodeError: raise Fault('TOOL_PROTOCOL_ERROR') from None
                if size > self.limits['max_argument_bytes']: raise Fault('TOOL_LIMIT_EXCEEDED')
            elif kind == 'response.function_call_arguments.done': self.complete(part, event.get('arguments'))
            else: raise Fault('UNSUPPORTED_TOOL')
            return True
        return False

    async def finish_step(self, event):
        response = event['response']
        rid = response['id']
        output = response.get('output', [])
        tool_output = [item for item in output if tool_item(item)]
        if any(item.get('type') != 'function_call' for item in tool_output): raise Fault('UNSUPPORTED_TOOL')
        if not tool_output and not self.calls: return False
        if self.session.interrupt or self.session.response.get('suppressed') or rid in self.abandoned:
            self.calls.clear()
            return False
        if response.get('status') != 'completed': raise Fault('TOOL_PROTOCOL_ERROR')
        for item in tool_output:
            part = self.record(rid, item.get('id'), item.get('call_id'), item.get('name'))
            if part is not None: self.complete(part, item.get('arguments'))
        if not self.calls or any(part['complete'] is None or part['name'] is None for part in self.calls.values()):
            raise Fault('TOOL_ARGUMENTS_INVALID')
        calls = [dict(provider_item_id=part['provider_item_id'], provider_call_id=part['provider_call_id'],
                      name=part['name'], arguments=part['complete']) for part in self.calls.values()]
        self.round += 1
        self.turn_calls += len(calls);self.session_calls += len(calls)
        if self.turn_started is None: self.turn_started = time.monotonic()
        from .session import actual_usage
        usage = actual_usage(response.get('usage'))
        await self.session.canonical(event, 'response.step.completed', response_id=rid,
            turn_generation=self.generation, finish_reason='tool_calls', usage=usage)
        self.usage(usage)
        if (self.round > self.limits['max_rounds'] or self.turn_calls > self.limits['max_calls']
                or self.session_calls > self.limits['max_session_calls']): raise Fault('TOOL_LIMIT_EXCEEDED')
        self.session.terminal_responses[rid] = dict(source={'event_id':event.get('event_id', 'done_'+rid)},
                                                   message_id=self.session.response.get('message_id'))
        self.prefix += self.session.response['text']
        if output and isinstance(output[-1], dict) and output[-1].get('id'):
            self.session.last_provider_item_id = identifier(output[-1]['id'])
        self.waiting = dict(response_id=rid, generation=self.generation, calls=calls)
        self.seen_calls.update(call['provider_call_id'] for call in calls)
        self.session.response = None
        self.session.pending_since = None
        self.calls = {}
        if self.job and not self.job.done(): raise Fault('TOOL_RESULT_UNCERTAIN')
        captured = dict(self.waiting)
        async def execute():
            try:
                result = await asyncio.wait_for(self.session.manager.tools(self.session.task, self.session.provider_session_id,
                    captured['response_id'], captured['generation'], self.round, calls,
                    self.limits['max_result_bytes'] + 8192), self.limits['timeout_seconds'])
                if captured['generation'] == self.generation:
                    self.session.enqueue('tool_result', (captured, result))
            except asyncio.CancelledError: raise
            except Exception as error:
                if captured['generation'] == self.generation:
                    self.session.enqueue('fault', error if isinstance(error, (Fault, AuthorityLost)) else Fault('TOOL_TIMEOUT'))
        self.job = asyncio.create_task(execute())
        self.jobs.add(self.job);self.job.add_done_callback(self.jobs.discard)
        return True

    def usage(self, usage):
        self.turn_usage.append(usage)
        if usage is not None:
            self.known_tokens += usage['total_tokens']
            if self.known_tokens > self.limits['max_session_tokens']: raise Fault('TOOL_LIMIT_EXCEEDED')

    def logical_usage(self):
        if not self.turn_usage or any(value is None for value in self.turn_usage): return None
        return {key:sum(value[key] for value in self.turn_usage) for key in ('input_tokens','output_tokens','total_tokens')}

    def text_delta(self, text):
        from .session import text_units
        size = text_units(text)
        self.turn_output += size;self.step_output += size
        if self.turn_output > self.limits['max_output_chars']: raise Fault('TOOL_LIMIT_EXCEEDED')

    def final_text(self, text):
        from .session import text_units
        remaining = max(0, text_units(text)-self.step_output)
        self.turn_output += remaining;self.step_output += remaining
        if self.turn_output > self.limits['max_output_chars']: raise Fault('TOOL_LIMIT_EXCEEDED')

    async def result(self, captured, response):
        if captured['generation'] != self.generation or self.waiting != captured: return
        await self.session.authorize(force_busy=True)
        if captured['generation'] != self.generation: return
        if (not isinstance(response, dict) or response.get('turn_generation') != self.generation
                or not isinstance(response.get('results'), list) or len(response['results']) != len(captured['calls'])
                or type(response.get('remaining_calls')) is not int or not 0 <= response['remaining_calls'] <= self.limits['max_calls']):
            raise Fault('TOOL_PROTOCOL_ERROR')
        size = len(wire(response).encode('utf-8'))
        self.turn_bytes += size;self.session_bytes += size
        if self.turn_bytes > self.limits['max_result_bytes'] or self.session_bytes > self.limits['max_session_result_bytes']:
            raise Fault('TOOL_LIMIT_EXCEEDED')
        mapped = {}
        for result in response['results']:
            call_id = identifier(result.get('provider_call_id'))
            if call_id in mapped: raise Fault('TOOL_PROTOCOL_ERROR')
            mapped[call_id] = result
        for call in captured['calls']:
            result = mapped.get(call['provider_call_id'])
            if (result is None or result.get('provider_item_id') != call['provider_item_id'] or result.get('name') != call['name']
                    or result.get('status') not in ('succeeded', 'failed') or not isinstance(result.get('output'), dict)
                    or result.get('error_code') is not None and (not isinstance(result['error_code'], str) or len(result['error_code'])>100)):
                raise Fault('TOOL_PROTOCOL_ERROR')
            item_id = identifier(result.get('result_item_id'))
            if item_id in self.result_items: raise Fault('TOOL_PROTOCOL_ERROR')
            self.result_items.add(item_id)
            self.pending[item_id] = dict(call_id=call['provider_call_id'], output=wire(result), response_id=captured['response_id'],
                                         generation=self.generation, sent=False, anchor=None)
        await self.send_next()

    async def send_next(self):
        for item_id, expected in self.pending.items():
            if not expected['sent']:
                expected['sent'] = True;expected['anchor'] = self.session.last_provider_item_id
                await self.session.send_provider({'type':'conversation.item.create', 'item': dict(
                    id=item_id, type='function_call_output', call_id=expected['call_id'], output=expected['output'])})
                self.session.pending_since = time.monotonic()
                return

    async def output_ack(self, event):
        item = event['item'];item_id = identifier(item.get('id'));fingerprint = wire([item,event.get('previous_item_id')])
        if item_id in self.echoes:
            if self.echoes[item_id] != fingerprint: raise Fault('TOOL_PROTOCOL_ERROR')
            return
        discarded = self.discarded_outputs.get(item.get('call_id'))
        if discarded is not None:
            if item.get('output') != discarded['output'] or event.get('previous_item_id') != discarded['anchor']:
                raise Fault('TOOL_PROTOCOL_ERROR')
            self.echoes[item_id] = fingerprint
            return
        active = [(key, value) for key, value in self.pending.items() if value['sent']]
        if len(active) != 1: raise Fault('TOOL_PROTOCOL_ERROR')
        request_id, expected = active[0]
        if (item.get('call_id') != expected['call_id'] or item.get('output') != expected['output']
                or event.get('previous_item_id') != expected['anchor'] or expected['generation'] != self.generation):
            raise Fault('TOOL_PROTOCOL_ERROR')
        await self.session.canonical(event, 'tool.result.ack', response_id=expected['response_id'],
            turn_generation=self.generation, provider_call_id=expected['call_id'], provider_item_id=item_id, request_item_id=request_id)
        self.echoes[item_id] = fingerprint
        self.session.remember_item(item_id, hashlib.sha256(fingerprint.encode()).digest())
        del self.pending[request_id]
        if self.pending:
            await self.send_next()
        else:
            receipt = await self.session.canonical({'event_id':'continue_'+expected['response_id']}, 'response.continue',
                response_id=expected['response_id'], turn_generation=self.generation)
            self.continuation = identifier(receipt.get('continuation_id'))
            self.waiting = None
            self.session.waiting_response = True
            self.session.pending_since = time.monotonic()
            await self.session.send_provider({'type':'response.create'})

    def check_deadlines(self):
        if self.busy() and self.turn_started and time.monotonic()-self.turn_started > self.limits['timeout_seconds']:
            raise Fault('TOOL_TIMEOUT')

    async def close(self):
        for job in self.jobs: job.cancel()
        await asyncio.gather(*self.jobs, return_exceptions=True)
