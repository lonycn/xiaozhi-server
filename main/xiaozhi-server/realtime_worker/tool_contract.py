"""Default-off boundary; only an authorized Java task may select the tool loop.

This legacy guard never executes a callback or fabricates function results.
"""
from .config import MODEL
from .control import Fault


def capability(authorized=False):
    return {'model': MODEL, 'provider_function_calling': 'documented',
            'worker_function_calling': 'enabled_for_authorized_task' if authorized else 'disabled',
            'error_code': None if authorized else 'UNSUPPORTED_TOOL'}


def require_disabled(tools):
    if tools is not None and (not isinstance(tools, list) or tools):
        raise Fault('UNSUPPORTED_TOOL')


def tool_item(item):
    if not isinstance(item, dict):
        return False
    kind = item.get('type')
    return isinstance(kind, str) and (kind in ('function_call', 'function_call_output') or kind.startswith('mcp_'))


def reject_tool_event(event):
    """Fail closed across native proposal, result echo and MCP event envelopes."""
    kind = event.get('type', '')
    if not isinstance(kind, str):
        return
    if kind.startswith(('response.function_call', 'response.mcp')):
        raise Fault('UNSUPPORTED_TOOL')
    if kind in ('session.created', 'session.updated'):
        session = event.get('session')
        if isinstance(session, dict):
            require_disabled(session.get('tools'))
    if kind in ('conversation.item.created', 'response.output_item.added', 'response.output_item.done') and tool_item(event.get('item')):
        raise Fault('UNSUPPORTED_TOOL')
    response = event.get('response')
    if kind.startswith('response.') and isinstance(response, dict) and isinstance(response.get('output'), list):
        if any(tool_item(item) for item in response['output']):
            raise Fault('UNSUPPORTED_TOOL')
