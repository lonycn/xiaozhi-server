import asyncio
import importlib.util
import json
import socket
import tempfile
import time
import unittest
from unittest.mock import patch
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('media_fault_proxy', Path(__file__).with_name('media_fault_proxy.py'))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class ProxyTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.received = bytearray()
        async def echo(reader, writer):
            try:
                while data := await reader.read(16384):
                    self.received.extend(data)
                    writer.write(data)
                    await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()
        self.upstream = await asyncio.start_server(echo, '127.0.0.1', 0)
        self.cfg = {'listen': {'host': '127.0.0.1', 'port': port()},
                    'upstream': {'host': '127.0.0.1', 'port': self.upstream.sockets[0].getsockname()[1]},
                    'max_connections': 2}
        self.proxy = module.FaultProxy(self.cfg, Path(self.tmp.name) / 'metrics.jsonl')
        await self.proxy.start()

    async def asyncTearDown(self):
        await self.proxy.close()
        self.upstream.close()
        await self.upstream.wait_closed()
        self.tmp.cleanup()

    async def connect(self):
        return await asyncio.open_connection(**self.cfg['listen'])

    async def test_byte_transparency_delay_and_rate(self):
        await self.proxy.apply({'up': {'delay_ms': 100, 'rate_bps': 1000}})
        reader, writer = await self.connect()
        payload = bytes(range(100))
        start = time.monotonic()
        writer.write(payload)
        await writer.drain()
        self.assertEqual(await asyncio.wait_for(reader.readexactly(len(payload)), 2), payload)
        self.assertGreaterEqual(time.monotonic() - start, .17)
        await self.proxy.apply({'up': {'delay_ms': 0, 'rate_bps': 0}})
        writer.write(b'back')
        await writer.drain()
        self.assertEqual(await asyncio.wait_for(reader.readexactly(4), .4), b'back')
        writer.close()
        await writer.wait_closed()
        self.assertGreaterEqual(self.proxy.stats['up_delay_ms'], 200)

    async def test_blackhole_holds_bytes_and_restores_both_directions(self):
        reader, writer = await self.connect()
        await self.proxy.apply({'up': {'blackhole': True}, 'down': {'blackhole': True}})
        writer.write(b'opaque-secret-sentinel')
        await writer.drain()
        with self.assertRaises(asyncio.TimeoutError):
            await asyncio.wait_for(reader.read(1), .12)
        await self.proxy.apply({'up': {'blackhole': False}, 'down': {'blackhole': False}})
        self.assertEqual(await asyncio.wait_for(reader.readexactly(22), 1), b'opaque-secret-sentinel')
        writer.close()
        await writer.wait_closed()
        self.assertNotIn('opaque-secret-sentinel', self.proxy.metrics_path.read_text())

    async def test_downstream_delay_does_not_block_upstream_delivery(self):
        await self.proxy.apply({'down': {'delay_ms': 180, 'jitter_ms': 10}})
        reader, writer = await self.connect()
        started = time.monotonic()
        writer.write(b'downstream')
        await writer.drain()
        await asyncio.sleep(.05)
        self.assertIn(b'downstream', self.received)
        with self.assertRaises(asyncio.TimeoutError):
            await asyncio.wait_for(reader.read(1), .05)
        self.assertEqual(await asyncio.wait_for(reader.readexactly(10), 1), b'downstream')
        self.assertGreaterEqual(time.monotonic() - started, .16)
        writer.close()
        await writer.wait_closed()

    async def test_reject_disconnect_and_recovery(self):
        reader, writer = await self.connect()
        writer.write(b'a')
        await writer.drain()
        self.assertEqual(await reader.readexactly(1), b'a')
        await self.proxy.apply({'reject_new': True, 'close_existing': True})
        self.assertEqual(await asyncio.wait_for(reader.read(), 1), b'')
        rejected, rejected_writer = await self.connect()
        self.assertEqual(await asyncio.wait_for(rejected.read(), 1), b'')
        rejected_writer.close()
        await rejected_writer.wait_closed()
        await self.proxy.apply({'reject_new': False})
        restored, restored_writer = await self.connect()
        restored_writer.write(b'works')
        await restored_writer.drain()
        self.assertEqual(await asyncio.wait_for(restored.readexactly(5), 1), b'works')
        writer.close()
        restored_writer.close()
        await writer.wait_closed()
        await restored_writer.wait_closed()
        self.assertGreaterEqual(self.proxy.stats['forced_closed'], 1)
        self.assertGreaterEqual(self.proxy.stats['rejected'], 1)

    async def test_scheduled_blackhole_and_automatic_recovery(self):
        await self.proxy.close()
        self.cfg['events'] = [{'at': .05, 'up': {'blackhole': True}},
                              {'at': .3, 'up': {'blackhole': False}}]
        self.proxy = module.FaultProxy(self.cfg, Path(self.tmp.name) / 'scheduled.jsonl')
        await self.proxy.start()
        reader, writer = await self.connect()
        await asyncio.sleep(.1)
        writer.write(b'after')
        await writer.drain()
        with self.assertRaises(asyncio.TimeoutError):
            await asyncio.wait_for(reader.read(1), .08)
        self.assertEqual(await asyncio.wait_for(reader.readexactly(5), 1), b'after')
        writer.close()
        await writer.wait_closed()
        events = [json.loads(line)['event'] for line in self.proxy.metrics_path.read_text().splitlines()]
        self.assertEqual(events.count('scenario'), 2)

    async def test_connection_limit(self):
        r1, w1 = await self.connect()
        r2, w2 = await self.connect()
        await asyncio.sleep(.05)
        r3, w3 = await self.connect()
        self.assertEqual(await asyncio.wait_for(r3.read(), 1), b'')
        for w in (w1, w2, w3):
            w.close()
            await w.wait_closed()

    async def test_close_during_blackhole_releases_connection_slot(self):
        reader, writer = await self.connect()
        writer.write(b'first')
        await writer.drain()
        self.assertEqual(await reader.readexactly(5), b'first')
        await self.proxy.apply({'up': {'blackhole': True}, 'close_existing': True})
        self.assertEqual(await asyncio.wait_for(reader.read(), 1), b'')
        await asyncio.sleep(.05)
        self.assertEqual(len(self.proxy.connections), 0)
        await self.proxy.apply({'up': {'blackhole': False}})
        restored, other = await self.connect()
        other.write(b'next')
        await other.drain()
        self.assertEqual(await asyncio.wait_for(restored.readexactly(4), 1), b'next')
        writer.close()
        other.close()
        await writer.wait_closed()
        await other.wait_closed()

    async def test_scenario_clock_can_start_on_first_accepted_connection(self):
        await self.proxy.close()
        self.cfg.update(schedule_start='first_accept', events=[{'at': .05, 'reject_new': True}])
        self.proxy = module.FaultProxy(self.cfg, Path(self.tmp.name) / 'first.jsonl')
        await self.proxy.start()
        await asyncio.sleep(.12)
        self.assertFalse(self.proxy.reject_new)
        reader, writer = await self.connect()
        writer.write(b'x')
        await writer.drain()
        self.assertEqual(await reader.readexactly(1), b'x')
        await asyncio.sleep(.1)
        rejected, other = await self.connect()
        self.assertEqual(await asyncio.wait_for(rejected.read(), 1), b'')
        writer.close()
        other.close()
        await writer.wait_closed()
        await other.wait_closed()

    async def test_restoring_rate_or_delay_unblocks_pending_bytes(self):
        await self.proxy.apply({'up': {'rate_bps': 1, 'delay_ms': 0}})
        reader, writer = await self.connect()
        writer.write(b'x' * 100)
        await writer.drain()
        await asyncio.sleep(.12)
        await self.proxy.apply({'up': {'rate_bps': 0}})
        self.assertEqual(await asyncio.wait_for(reader.readexactly(100), .5), b'x' * 100)
        await self.proxy.apply({'up': {'delay_ms': 60000}})
        writer.write(b'z')
        await writer.drain()
        await asyncio.sleep(.1)
        await self.proxy.apply({'up': {'delay_ms': 0}})
        self.assertEqual(await asyncio.wait_for(reader.readexactly(1), .5), b'z')
        writer.close()
        await writer.wait_closed()

    async def test_close_is_idempotent_and_cancels_blackholed_tasks(self):
        reader, writer = await self.connect()
        writer.write(b'go')
        await writer.drain()
        self.assertEqual(await reader.readexactly(2), b'go')
        await self.proxy.apply({'up': {'blackhole': True}})
        writer.write(b'held')
        await writer.drain()
        await asyncio.sleep(.05)
        await asyncio.wait_for(asyncio.gather(self.proxy.close(), self.proxy.close()), 1)
        self.assertEqual(len(self.proxy.tasks), 0)
        self.assertEqual(len(self.proxy.connections), 0)
        self.assertEqual(await asyncio.wait_for(reader.read(), 1), b'')
        self.assertEqual(sum(json.loads(line)['event'] == 'stopped' for line in self.proxy.metrics_path.read_text().splitlines()), 1)
        writer.close()
        await writer.wait_closed()

    async def test_metrics_cap_does_not_block_proxy(self):
        with self.proxy.metrics_path.open('wb') as f:
            f.truncate(module.MAX_METRICS_BYTES)
        await self.proxy.apply({'up': {'delay_ms': 0}})
        self.assertEqual(self.proxy.metrics_path.stat().st_size, module.MAX_METRICS_BYTES)
        reader, writer = await self.connect()
        writer.write(b'ok')
        await writer.drain()
        self.assertEqual(await asyncio.wait_for(reader.readexactly(2), 1), b'ok')
        writer.close()
        await writer.wait_closed()

    async def test_shutdown_during_upstream_connect_releases_reservation(self):
        entered = asyncio.Event()
        async def stalled(*args, **kwargs):
            entered.set()
            await asyncio.Event().wait()
        with patch.object(module.asyncio, 'open_connection', side_effect=stalled):
            loop = asyncio.get_running_loop()
            raw = socket.socket()
            raw.setblocking(False)
            await loop.sock_connect(raw, (self.cfg['listen']['host'], self.cfg['listen']['port']))
            await asyncio.wait_for(entered.wait(), 1)
            await asyncio.wait_for(self.proxy.close(), 1)
            self.assertEqual(len(self.proxy.connections), 0)
            self.assertEqual(len(self.proxy.tasks), 0)
            raw.close()

    async def test_cli_rejects_oversize_and_nonfinite_json_before_listening(self):
        for raw in (b' ' * (module.MAX_CONFIG_BYTES + 1), b'{"events":[{"at":NaN}]}'):
            config = Path(self.tmp.name) / 'bad.json'
            config.write_bytes(raw)
            child = await asyncio.create_subprocess_exec(
                'python3', str(Path(__file__).with_name('media_fault_proxy.py')),
                '--config', str(config), '--metrics', str(Path(self.tmp.name) / 'cli.jsonl'),
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            self.assertNotEqual(await asyncio.wait_for(child.wait(), 2), 0)
            self.assertFalse((Path(self.tmp.name) / 'cli.jsonl').exists())

    async def test_metrics_symlink_start_failure_closes_listener(self):
        await self.proxy.close()
        target = Path(self.tmp.name) / 'target'
        target.write_text('unchanged')
        link = Path(self.tmp.name) / 'link'
        link.symlink_to(target)
        self.proxy = module.FaultProxy(self.cfg, link)
        with self.assertRaises(OSError):
            await self.proxy.start()
        self.assertEqual(target.read_text(), 'unchanged')
        replacement = await asyncio.start_server(lambda r, w: w.close(), **self.cfg['listen'])
        replacement.close()
        await replacement.wait_closed()
        self.proxy.metrics_path = Path(self.tmp.name) / 'cleanup.jsonl'


class ConfigTests(unittest.TestCase):
    def test_rejects_external_addresses_and_unknown_fields(self):
        cfg = {'listen': {'host': '127.0.0.1', 'port': 1234}, 'upstream': {'host': '127.0.0.1', 'port': 2345}}
        module.validate_config(cfg)
        for bad in ({**cfg, 'upstream': {'host': 'example.com', 'port': 80}},
                    {**cfg, 'listen': {'host': '0.0.0.0', 'port': 1234}},
                    {**cfg, 'events': [{'at': 0, 'command': 'anything'}]},
                    {**cfg, 'events': [{'at': 0, 'up': {'rate_bps': -1}}]},
                    {**cfg, 'events': [{'at': float('nan')}]},
                    {**cfg, 'events': [{'at': 0, 'up': {'delay_ms': float('inf')}}]},
                    {**cfg, 'events': [{'at': 10**500}]},
                    {**cfg, 'max_connections': 65}):
            with self.assertRaises(ValueError):
                module.validate_config(bad)

if __name__ == '__main__':
    unittest.main()
