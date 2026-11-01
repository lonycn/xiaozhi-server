#!/usr/bin/env python3
"""Bounded loopback-only TCP fault proxy; no payloads or credentials are logged."""
import argparse
import asyncio
import ipaddress
import json
import math
import os
import random
import signal
import stat
import sys
import time
from pathlib import Path

CHUNK = 16384
MAX_CONFIG_BYTES = 65536
MAX_METRICS_BYTES = 5 * 1024 * 1024
DIRECTIONS = ('up', 'down')
DEFAULT = {'delay_ms': 0.0, 'jitter_ms': 0.0, 'rate_bps': 0, 'blackhole': False}


def validate_config(data):
    if not isinstance(data, dict) or set(data) - {'listen', 'upstream', 'max_connections', 'events', 'schedule_start'}:
        raise ValueError('invalid top-level configuration')
    if data.get('schedule_start', 'startup') not in ('startup', 'first_accept'):
        raise ValueError('invalid schedule_start')
    def endpoint(value):
        if not isinstance(value, dict) or set(value) != {'host', 'port'}:
            raise ValueError('endpoint requires host and port')
        try:
            address = ipaddress.ip_address(value['host'])
        except (ValueError, TypeError):
            raise ValueError('endpoint host must be a numeric loopback address') from None
        port = value['port']
        if address.version != 4 or not address.is_loopback or type(port) is not int or not 1 <= port <= 65535:
            raise ValueError('endpoint must be IPv4 loopback and valid port')
        return (str(address), port)
    listen, upstream = endpoint(data.get('listen')), endpoint(data.get('upstream'))
    if listen == upstream:
        raise ValueError('listen and upstream must differ')
    limit = data.get('max_connections', 16)
    if type(limit) is not int or not 1 <= limit <= 64:
        raise ValueError('invalid max_connections')
    events = data.get('events', [])
    if not isinstance(events, list) or len(events) > 1000:
        raise ValueError('invalid events')
    last = -1.0
    for event in events:
        if not isinstance(event, dict) or set(event) - {'at', 'up', 'down', 'reject_new', 'close_existing'} or 'at' not in event:
            raise ValueError('invalid event fields')
        at = event['at']
        if type(at) not in (int, float) or not 0 <= at <= 86400 or not math.isfinite(at) or at < last:
            raise ValueError('invalid event time')
        last = at
        for flag in ('reject_new', 'close_existing'):
            if flag in event and type(event[flag]) is not bool:
                raise ValueError('invalid event flag')
        for direction in DIRECTIONS:
            if direction not in event:
                continue
            item = event[direction]
            if not isinstance(item, dict) or set(item) - set(DEFAULT):
                raise ValueError('invalid direction fields')
            for key, value in item.items():
                if key == 'blackhole':
                    if type(value) is not bool:
                        raise ValueError('blackhole must be boolean')
                elif type(value) not in (int, float) or not 0 <= value <= (10_000_000 if key == 'rate_bps' else 60_000) or not math.isfinite(value):
                    raise ValueError('invalid direction value')
    return listen, upstream, limit, events


class FaultProxy:
    def __init__(self, config, metrics_path):
        self.listen, self.upstream, self.limit, self.events = validate_config(config)
        self.schedule_start = config.get('schedule_start', 'startup')
        self.metrics_path = Path(metrics_path)
        self.policy = {direction: dict(DEFAULT) for direction in DIRECTIONS}
        self.reject_new = False
        self.connections = set()
        self.connection_tasks = {}
        self.condition = asyncio.Condition()
        self.server = None
        self.scheduler = None
        self.tasks = set()
        self.closing = False
        self.closed = False
        self.close_lock = asyncio.Lock()
        self.started = time.monotonic()
        self.stats = {'accepted': 0, 'rejected': 0, 'upstream_failures': 0, 'closed': 0,
                      'forced_closed': 0, 'up_bytes': 0, 'down_bytes': 0,
                      'up_dropped_bytes': 0, 'down_dropped_bytes': 0,
                      'up_delay_ms': 0.0, 'down_delay_ms': 0.0}

    def metric(self, event):
        record = {'event': event, 'elapsed_seconds': round(time.monotonic() - self.started, 3),
                  'active_connections': len(self.connections), **self.stats}
        encoded = (json.dumps(record, separators=(',', ':')) + '\n').encode('utf-8')
        # Open without following symlinks and cap the output, including existing records.
        fd = os.open(self.metrics_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode):
                raise OSError('metrics must be a regular file')
            if info.st_size + len(encoded) <= MAX_METRICS_BYTES:
                os.write(fd, encoded)
        finally:
            os.close(fd)

    async def start(self):
        self.server = await asyncio.start_server(self.accept, *self.listen, limit=CHUNK)
        self.started = time.monotonic()
        try:
            self.metric('started')
        except OSError:
            self.server.close()
            await self.server.wait_closed()
            raise
        if self.schedule_start == 'startup':
            self.scheduler = asyncio.create_task(self.run_scenario(time.monotonic()))

    async def run_scenario(self, origin):
        for event in self.events:
            await asyncio.sleep(max(0, origin + event['at'] - time.monotonic()))
            await self.apply(event)

    async def apply(self, event):
        async with self.condition:
            for direction in DIRECTIONS:
                self.policy[direction].update(event.get(direction, {}))
            if 'reject_new' in event:
                self.reject_new = event['reject_new']
            self.condition.notify_all()
        if event.get('close_existing', False):
            for pair in list(self.connections):
                self.stats['forced_closed'] += 1
                for writer in pair:
                    if writer:
                        writer.close()
                if pair[1] and (task := self.connection_tasks.get(pair)):
                    task.cancel()
        try:
            self.metric('scenario')
        except OSError:
            print('Metrics unavailable; scenario continues.', file=sys.stderr)

    async def accept(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        try:
            if self.closing or self.reject_new or len(self.connections) >= self.limit:
                self.stats['rejected'] += 1
                writer.close()
                await writer.wait_closed()
                return
            # Reserve a slot before the asynchronous connect, preventing races at the limit.
            pair = (writer, None)
            self.connections.add(pair)
            try:
                remote_reader, remote_writer = await asyncio.wait_for(asyncio.open_connection(*self.upstream, limit=CHUNK), 3)
            except asyncio.CancelledError:
                self.connections.discard(pair)
                writer.close()
                await asyncio.gather(writer.wait_closed(), return_exceptions=True)
                raise
            except (OSError, asyncio.TimeoutError):
                self.stats['upstream_failures'] += 1
                self.connections.discard(pair)
                writer.close()
                await writer.wait_closed()
                return
            self.connections.discard(pair)
            if writer.is_closing():
                remote_writer.close()
                await remote_writer.wait_closed()
                return
            pair = (writer, remote_writer)
            self.connections.add(pair)
            self.connection_tasks[pair] = task
            self.stats['accepted'] += 1
            if self.schedule_start == 'first_accept' and self.scheduler is None:
                self.scheduler = asyncio.create_task(self.run_scenario(time.monotonic()))
            try:
                pumps = [asyncio.create_task(self.pump(reader, remote_writer, 'up')),
                         asyncio.create_task(self.pump(remote_reader, writer, 'down'))]
                try:
                    await asyncio.wait(pumps, return_when=asyncio.FIRST_COMPLETED)
                finally:
                    for pump in pumps:
                        pump.cancel()
                    await asyncio.gather(*pumps, return_exceptions=True)
            finally:
                self.connections.discard(pair)
                self.connection_tasks.pop(pair, None)
                self.stats['closed'] += 1
                for output in pair:
                    output.close()
                await asyncio.gather(*(output.wait_closed() for output in pair), return_exceptions=True)
        finally:
            self.tasks.discard(task)

    async def pump(self, reader, writer, direction):
        chunk = b''
        try:
            while True:
                # A blackhole stalls reads and lets bounded transport buffers apply TCP backpressure.
                async with self.condition:
                    await self.condition.wait_for(lambda: not self.policy[direction]['blackhole'])
                chunk = await reader.read(CHUNK)
                if not chunk:
                    break
                async with self.condition:
                    await self.condition.wait_for(lambda: not self.policy[direction]['blackhole'])
                    policy = dict(self.policy[direction])
                # Break long delays into bounded waits and respond to restored policies.
                delay_started = time.monotonic()
                delay = max(0, policy['delay_ms'] + random.uniform(-policy['jitter_ms'], policy['jitter_ms'])) / 1000
                await self.policy_wait(direction, delay, (policy['delay_ms'], policy['jitter_ms']))
                self.stats[direction + '_delay_ms'] += (time.monotonic() - delay_started) * 1000
                remaining = float(len(chunk))
                while remaining > 0:
                    async with self.condition:
                        await self.condition.wait_for(lambda: not self.policy[direction]['blackhole'])
                        rate = self.policy[direction]['rate_bps']
                        if not rate:
                            break
                        wait = min(.1, remaining / rate)
                        tick = time.monotonic()
                        try:
                            await asyncio.wait_for(self.condition.wait(), wait)
                        except asyncio.TimeoutError:
                            pass
                        elapsed = time.monotonic() - tick
                        remaining -= rate * elapsed
                        self.stats[direction + '_delay_ms'] += elapsed * 1000
                # Recheck a blackhole activated during the delay.
                async with self.condition:
                    await self.condition.wait_for(lambda: not self.policy[direction]['blackhole'])
                writer.write(chunk)
                await writer.drain()
                self.stats[direction + '_bytes'] += len(chunk)
                chunk = b''
        except (ConnectionError, OSError):
            pass
        finally:
            if chunk:
                self.stats[direction + '_dropped_bytes'] += len(chunk)

    async def policy_wait(self, direction, delay, original):
        end = time.monotonic() + delay
        async with self.condition:
            while time.monotonic() < end:
                if (self.policy[direction]['delay_ms'], self.policy[direction]['jitter_ms']) != original:
                    return
                try:
                    await asyncio.wait_for(self.condition.wait(), end - time.monotonic())
                except asyncio.TimeoutError:
                    return

    async def close(self):
        async with self.close_lock:
            if self.closed:
                return
            self.closing = True
            if self.scheduler:
                self.scheduler.cancel()
                await asyncio.gather(self.scheduler, return_exceptions=True)
            if self.server:
                self.server.close()
                await self.server.wait_closed()
            for pair in list(self.connections):
                for writer in pair:
                    if writer:
                        writer.close()
            for task in list(self.tasks):
                task.cancel()
            await asyncio.gather(*list(self.tasks), return_exceptions=True)
            self.closed = True
            try:
                self.metric('stopped')
            except OSError:
                print('Metrics unavailable at shutdown.', file=sys.stderr)


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, help='JSON scenario file')
    parser.add_argument('--metrics', required=True, help='JSONL aggregate metrics file')
    args = parser.parse_args()
    with open(args.config, 'rb') as source:
        raw = source.read(MAX_CONFIG_BYTES + 1)
    if len(raw) > MAX_CONFIG_BYTES:
        raise ValueError('configuration exceeds size limit')
    config = json.loads(raw, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON number')))
    proxy = FaultProxy(config, args.metrics)
    await proxy.start()
    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stopped.set)
    try:
        await stopped.wait()
    finally:
        await proxy.close()


if __name__ == '__main__':
    asyncio.run(main())
