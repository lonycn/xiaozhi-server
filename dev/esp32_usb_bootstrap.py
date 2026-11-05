#!/usr/bin/env python3
"""Explicit first-board USB maintenance. Never flashes a merged image or erases NVS.

stage backs up the complete chip, writes only inactive ota_1 and append-only NVS
metadata, reads everything back, and records administrator USB evidence. activate
then changes only the unused OTA selection sector. The old ota_0, bootloader,
partition table, Wi-Fi/identity records and assets stay intact.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import urllib.request
import zlib

FLASH_SIZE = 0x1000000
NVS_OFFSET, NVS_SIZE = 0x9000, 0x4000
APP_OFFSET, APP_SIZE, ASSETS_OFFSET = 0x410000, 0x3f0000, 0x800000


def digest(data):
    return hashlib.sha256(data).hexdigest()


def private_json(path, value):
    path = Path(path)
    with open(path, 'w', opener=lambda p, flags: os.open(p, flags, 0o600)) as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
    path.chmod(0o600)


def nvs_records(path, python, idf):
    tool = Path(idf) / 'components/nvs_flash/nvs_partition_tool/nvs_tool.py'
    result = subprocess.run([python, str(tool), str(path), '-d', 'minimal', '-f', 'json'],
                            check=True, capture_output=True, text=True)
    return {(r['namespace'], r['key']): (r['encoding'], r['data']) for r in json.loads(result.stdout)}


def prepare_nvs(source, target, release, version, python, idf):
    if not re.fullmatch('[a-f0-9]{32}', release) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}', version):
        raise ValueError('invalid release identity')
    sys.path.insert(0, str(Path(idf) / 'components/nvs_flash/nvs_partition_tool'))
    from nvs_parser import NVS_Partition
    data = bytearray(Path(source).read_bytes())
    if len(data) != NVS_SIZE:
        raise ValueError('unexpected NVS size')
    parsed = NVS_Partition(Path(source).name, data)
    namespaces = {entry.key: entry.data['value'] for page in parsed.pages for entry in page.entries
                  if entry.state == 'Written' and entry.metadata['namespace'] == 0}
    pages = [p for p in range(0, len(data), 4096) if struct.unpack_from('<I', data, p)[0] == 0xfffffffe]
    if len(pages) != 1:
        raise ValueError('exactly one active NVS page required')
    page = pages[0]
    states = [(data[page+32+i//4] >> (2*(i%4))) & 3 for i in range(126)]
    start = max((i for i, state in enumerate(states) if state != 3), default=-1) + 1
    namespace = namespaces.get('release_ota')
    additions = []

    def encode(ns, key, kind, value):
        payload = value.encode() + b'\0' if kind == 0x21 else b''
        span = 1 + (len(payload)+31)//32
        entry = bytearray(b'\xff'*32)
        entry[:4] = bytes([ns, kind, span, 0xff])
        entry[8:24] = key.encode().ljust(16, b'\0')
        if payload:
            struct.pack_into('<H', entry, 24, len(payload))
            struct.pack_into('<I', entry, 28, zlib.crc32(payload, 0xffffffff) & 0xffffffff)
        elif kind == 0x01:
            entry[24] = value
        else:
            struct.pack_into('<i', entry, 24, value)
        struct.pack_into('<I', entry, 4, zlib.crc32(entry[:4]+entry[8:], 0xffffffff) & 0xffffffff)
        return entry + payload.ljust((span-1)*32, b'\xff'), span

    if namespace is None:
        namespace = max(namespaces.values(), default=0) + 1
        if namespace >= 255:
            raise ValueError('no NVS namespace available')
        additions.append(encode(0, 'release_ota', 0x01, namespace))
    for key, kind, value in [('release_id', 0x21, release), ('version', 0x21, version), ('selected', 0x14, 1)]:
        additions.append(encode(namespace, key, kind, value))
    if start + sum(span for _, span in additions) > 126:
        raise ValueError('insufficient append-only NVS room; original data retained')
    for encoded, span in additions:
        offset = page + 64 + start*32
        if data[offset:offset+len(encoded)] != b'\xff'*len(encoded):
            raise ValueError('NVS destination is not erased')
        data[offset:offset+len(encoded)] = encoded
        for i in range(start, start+span):
            data[page+32+i//4] &= ~(1 << (2*(i%4)))
        start += span
    for page_index, old in enumerate(parsed.pages):
        for entry in old.entries:
            if entry.state == 'Written' and entry.metadata['namespace'] == namespace and entry.key in ('release_id', 'version', 'selected'):
                for i in range(entry.index, entry.index+entry.metadata['span']):
                    data[page_index*4096+32+i//4] &= ~(3 << (2*(i%4)))
    target = Path(target)
    target.write_bytes(data)
    target.chmod(0o600)
    before, after = nvs_records(source, python, idf), nvs_records(target, python, idf)
    unrelated = lambda records: {k: v for k, v in records.items() if k[0] != 'release_ota'}
    if unrelated(before) != unrelated(after) or any(after.get(('release_ota', k), (None, None))[1] != value
            for k, value in [('release_id', release), ('version', version), ('selected', 1)]):
        target.unlink()
        raise ValueError('NVS preservation verification failed')
    return {'preserved_records': len(unrelated(before)), 'sha256': digest(data)}


def ota_selection(original):
    if len(original) != FLASH_SIZE:
        raise ValueError('complete Flash snapshot required')
    seq, state, crc = struct.unpack_from('<I20xII', original, 0xd000)
    if seq != 1 or state != 2 or crc != zlib.crc32(struct.pack('<I', seq), 0xffffffff) & 0xffffffff:
        raise ValueError('first-board bootstrap requires valid original ota_0')
    if original[0xe000:0xf000] != b'\xff'*4096:
        raise ValueError('second OTA selection sector is already in use')
    entry = bytearray(b'\xff'*4096)
    struct.pack_into('<I', entry, 0, 2)
    struct.pack_into('<I', entry, 24, 0)  # NEW, bootloader must perform PENDING_VERIFY.
    struct.pack_into('<I', entry, 28, zlib.crc32(struct.pack('<I', 2), 0xffffffff) & 0xffffffff)
    return entry


def rollback_slot_copy(original, known_app):
    """Move a byte-verified VALID ota_1 image to ota_0 before USB maintenance.

    Used only for the known seq=1/2 first-board layout. The complete original
    snapshot remains the backup of ota_0. Do not reset until the plan is read back.
    """
    if len(original) != FLASH_SIZE or not 256 <= len(known_app) <= APP_SIZE:
        raise ValueError('complete Flash and bounded known application required')
    for offset, expected_seq in [(0xd000, 1), (0xe000, 2)]:
        seq, state, crc = struct.unpack_from('<I20xII', original, offset)
        if seq != expected_seq or state != 2 or crc != zlib.crc32(struct.pack('<I', seq), 0xffffffff) & 0xffffffff:
            raise ValueError('both original slots must be VALID with expected sequence')
    if original[APP_OFFSET:APP_OFFSET+len(known_app)] != known_app:
        raise ValueError('active rollback image differs from verified application')
    padded = known_app.ljust((len(known_app)+4095)//4096*4096, b'\xff')
    expected = bytearray(original)
    expected[0x20000:0x20000+len(padded)] = padded
    expected[0xe000:0xf000] = b'\xff'*4096
    ota_selection(expected)  # The copied, already VALID image becomes the rollback slot.
    return bytes(expected), padded


class Maintenance:
    def __init__(self, config, output):
        config = Path(config)
        if config.stat().st_mode & 0o077:
            raise ValueError('configuration must be private')
        self.cfg = json.loads(config.read_text())
        self.out = Path(output).resolve()
        self.python, self.idf = self.cfg['python'], self.cfg['idf']
        self.package = Path(self.cfg['package']).resolve()
        spec = importlib.util.spec_from_file_location('package_verifier', self.cfg['verifier'])
        verifier = importlib.util.module_from_spec(spec); spec.loader.exec_module(verifier)
        self.manifest = verifier.verify(self.package, development_lan=True)
        from esp32_firmware import Client
        self.client = Client(self.cfg['base_url'], Path(self.cfg['token_file']), development=True)

    def bridge(self, action):
        body = {'port': self.cfg['port']} if action == 'connect' else {}
        req = urllib.request.Request('http://127.0.0.1:8765/api/'+action,
                json.dumps(body).encode(), {'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response)

    def tool(self, arguments, name, reset=False):
        command = [self.python, '-m', 'esptool', '--chip', 'esp32s3', '--port', self.cfg['port'],
                   '--baud', '921600', '--after', 'hard-reset' if reset else 'no-reset'] + arguments
        with (self.out/(name+'.log')).open('w') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        if result.returncode:
            raise RuntimeError('USB operation failed; inspect private '+name+'.log')

    def stage(self):
        assigned=self.client.call('GET','devices/'+self.cfg['device_id']+'/assignments')
        current=next((a for a in assigned if a['id']==self.cfg['assignment_id']),{})
        if not current.get('enabled') or current.get('release_id')!=self.cfg['release_id'] or current.get('last_state') not in ('assigned','offered'):
            raise ValueError('active first-board assignment required before staging')
        release=next((r for r in self.client.call('GET','releases') if r['id']==self.cfg['release_id']),{})
        if release.get('state')!='published' or release.get('app_sha256')!=self.manifest['artifacts']['app.bin']['sha256'] or release.get('assets_sha256')!=self.manifest['artifacts']['assets.bin']['sha256']:
            raise ValueError('published package and assignment must match before staging')
        self.out.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.bridge('disconnect')
        print('Reading complete Flash backup', flush=True)
        try:
            snapshot = self.out/'before.bin'
            self.tool(['read-flash', '0', hex(FLASH_SIZE), str(snapshot)], 'backup')
            snapshot.chmod(0o600)
            original = snapshot.read_bytes()
            if self.cfg['factory_mac'] not in (self.out/'backup.log').read_text().lower():
                raise ValueError('factory MAC does not match authorized board')
            selection = ota_selection(original)
            known = Path(self.cfg['original_app']).read_bytes()
            if original[0x20000:0x20000+len(known)] != known:
                raise ValueError('original application is not the verified rollback image')
            first = (self.package/'first-flash.bin').read_bytes()
            if original[0x8000:0x8c00] != first[0x8000:0x8c00]:
                raise ValueError('partition table mismatch')
            app, assets = (self.package/'app.bin').read_bytes(), (self.package/'assets.bin').read_bytes()
            if original[ASSETS_OFFSET:ASSETS_OFFSET+len(assets)] != assets or len(app) > APP_SIZE:
                raise ValueError('installed resources or application size mismatch')
            nvs = self.out/'before-nvs.bin'; nvs.write_bytes(original[NVS_OFFSET:NVS_OFFSET+NVS_SIZE]); nvs.chmod(0o600)
            candidate = self.out/'bootstrap-nvs.bin'
            preserved = prepare_nvs(nvs, candidate, self.cfg['release_id'], self.manifest['app_version'], self.python, self.idf)
            records = nvs_records(nvs, self.python, self.idf)
            if records.get(('board', 'dev_mac'), (None, None))[1].lower() != self.cfg['software_mac']:
                raise ValueError('NVS identity does not match authorized device')
            print('Staging inactive application and preserving all existing NVS records', flush=True)
            self.tool(['write-flash', hex(APP_OFFSET), str(self.package/'app.bin'), hex(NVS_OFFSET), str(candidate)], 'stage-write')
            after = self.out/'staged.bin'
            self.tool(['read-flash', '0', hex(FLASH_SIZE), str(after)], 'stage-readback')
            after.chmod(0o600); staged = after.read_bytes()
            expected = bytearray(original)
            # esptool erases only touched sectors, including the final app padding.
            end = APP_OFFSET + ((len(app)+4095)//4096)*4096
            expected[APP_OFFSET:end] = app + b'\xff'*(end-APP_OFFSET-len(app))
            expected[NVS_OFFSET:NVS_OFFSET+NVS_SIZE] = candidate.read_bytes()
            if staged != expected:
                raise ValueError('Flash readback or untouched-region preservation failed')
            proof = {'previousVersion': self.cfg['previous_version'], 'factoryMac': self.cfg['factory_mac'],
                     'softwareMac': self.cfg['software_mac'], 'targetSlot': 1, 'snapshotSha256': digest(original),
                     'partitionSha256': digest(original[0x8000:0x8c00]), 'originalAppSha256': digest(known),
                     'nvsBeforeSha256': digest(nvs.read_bytes()), 'nvsAfterSha256': digest(candidate.read_bytes()),
                     'nvsPreserved': True, 'oldApplicationPreserved': True,
                     'appReadback': {'sha256': digest(staged[APP_OFFSET:APP_OFFSET+len(app)]), 'size': len(app)},
                     'assetsReadback': {'sha256': digest(staged[ASSETS_OFFSET:ASSETS_OFFSET+len(assets)]), 'size': len(assets)}}
            private_json(self.out/'usb-readback-proof.json', proof)
            response = self.client.call('POST', 'assignments/'+self.cfg['assignment_id']+'/usb-bootstrap', proof)
            private_json(self.out/'stage-result.json', {'readback': response, 'preservation': preserved, 'staged_sha256': digest(staged)})
            (self.out/'select-ota1.bin').write_bytes(selection)
            print(json.dumps({'staged': True, 'selected': False, 'readback': response, 'preserved_records': preserved['preserved_records']}), flush=True)
        except BaseException:
            self.tool(['run'], 'restore-running-original', reset=True)
            self.bridge('connect')
            raise

    def activate(self):
        result = json.loads((self.out/'stage-result.json').read_text())
        if result['readback']['state'] != 'verified':
            raise ValueError('verified USB stage required')
        assignments = self.client.call('GET', 'devices/'+self.cfg['device_id']+'/assignments')
        current = next((a for a in assignments if a['id'] == self.cfg['assignment_id']), {})
        if current.get('last_state') != 'verified' or not current.get('enabled'):
            raise ValueError('assignment is no longer verified and enabled')
        before = self.out/'activation-readback.bin'
        self.tool(['read-flash', '0', hex(FLASH_SIZE), str(before)], 'activation-preflight')
        before.chmod(0o600)
        if self.cfg['factory_mac'] not in (self.out/'activation-preflight.log').read_text().lower():
            raise ValueError('factory MAC changed before activation')
        if digest(before.read_bytes()) != result['staged_sha256']:
            raise ValueError('staged Flash changed before activation')
        self.boot_monitored('activate')
        private_json(self.out/'activation.json', {'selected_slot': 1, 'state': 'VALID', 'old_slot_preserved': True,
                                                 'automatic_boot_confirmation_required': True,'healthy_confirmed':True})
        print(json.dumps({'selected': True, 'slot': 1, 'boot_health_pending': False,'healthy_confirmed':True}), flush=True)

    def boot_monitored(self, name):
        """Keep one USB descriptor open across reset until the pending image confirms health."""
        import esptool
        esp = esptool.detect_chip(self.cfg['port'])
        try:
            esptool.main(['--chip', 'esp32s3', '--after', 'hard-reset', 'write-flash', '0xe000',
                          str(self.out/'select-ota1.bin')], esp=esp)
            esp._port.baudrate = 115200
            esp._port.timeout = .25
            deadline = time.monotonic()+195
            healthy = False
            with (self.out/(name+'-boot.log')).open('w') as log:
                os.chmod(log.name,0o600)
                last_poll = 0
                while time.monotonic()<deadline:
                    line=esp._port.readline().decode(errors='replace')
                    if line:
                        log.write(line);log.flush()
                        if any(x in line for x in ['App version:', 'panic', 'Guru Meditation', 'Backtrace:', 'Marking firmware', 'Activation done', 'Restored existing', 'State:', 'Error', 'error', 'Failed']):
                            print(line.strip()[:350],flush=True)
                    if time.monotonic()-last_poll>=2:
                        last_poll=time.monotonic()
                        rows=self.client.call('GET','devices/'+self.cfg['device_id']+'/assignments')
                        row=next((r for r in rows if r['id']==self.cfg['assignment_id']),{})
                        if row.get('last_state')=='healthy':
                            healthy=True;break
                        if row.get('last_state') in ('failed','rolled_back'):break
            private_json(self.out/(name+'-health.json'),{'healthy':healthy,'serial_held_open_during_pending_boot':True})
        finally:
            esp._port.close()
            self.bridge('connect')
        if not healthy:raise RuntimeError('new image did not confirm health; inspect captured boot log')

    def retry_boot(self):
        if (self.out/'retry-boot-health.json').exists():
            raise ValueError('automatic repeated boot attempts are not allowed')
        result=json.loads((self.out/'stage-result.json').read_text())
        if result['readback']['state']!='verified':raise ValueError('verified USB evidence required')
        self.bridge('disconnect')
        current=self.out/'before-retry.bin'
        self.tool(['read-flash','0',hex(FLASH_SIZE),str(current)],'retry-preflight')
        current.chmod(0o600);raw=current.read_bytes();original=(self.out/'before.bin').read_bytes()
        if self.cfg['factory_mac'] not in (self.out/'retry-preflight.log').read_text().lower():
            raise ValueError('factory MAC changed before retry')
        app=(self.package/'app.bin').read_bytes()
        if raw[:NVS_OFFSET]!=original[:NVS_OFFSET] or raw[0xf000:APP_OFFSET]!=original[0xf000:APP_OFFSET] or raw[ASSETS_OFFSET:]!=original[ASSETS_OFFSET:] or raw[APP_OFFSET:APP_OFFSET+len(app)]!=app:
            raise ValueError('retry requires intact original bootloader/application/assets and verified new application')
        self.boot_monitored('retry-boot')

    def rollback(self):
        """Restore original selection and NVS; do not overwrite either application or resources."""
        original = (self.out/'before.bin').read_bytes()
        ota_selection(original)  # Validate the saved original, including its CRC and VALID state.
        self.bridge('disconnect')
        before = self.out/'before-rollback.bin'
        self.tool(['read-flash', '0', hex(FLASH_SIZE), str(before)], 'rollback-backup')
        before.chmod(0o600)
        if self.cfg['factory_mac'] not in (self.out/'rollback-backup.log').read_text().lower():
            raise ValueError('factory MAC changed before restore')
        raw = before.read_bytes()
        if raw[0x20000:APP_OFFSET] != original[0x20000:APP_OFFSET] or raw[ASSETS_OFFSET:] != original[ASSETS_OFFSET:]:
            raise ValueError('original application or resources changed; refuse automatic restore')
        nvs = self.out/'restore-nvs.bin';nvs.write_bytes(original[NVS_OFFSET:NVS_OFFSET+NVS_SIZE]);nvs.chmod(0o600)
        ota = self.out/'restore-otadata.bin';ota.write_bytes(original[0xd000:0xf000])
        self.tool(['write-flash', hex(NVS_OFFSET), str(nvs), '0xd000', str(ota)], 'restore-original', reset=True)
        self.bridge('connect')
        private_json(self.out/'rollback.json', {'restored_slot': 0, 'restored_nvs_sha256': digest(nvs.read_bytes()), 'applications_and_assets_untouched': True})
        print(json.dumps({'restored': True, 'slot': 0, 'original_nvs_restored': True}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['stage', 'activate', 'retry_boot', 'rollback'])
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.action in ('activate', 'retry_boot') and importlib.util.find_spec('esptool') is None:
        raise ValueError('Use the configured ESP-IDF Python for activation; esptool is required before USB maintenance')
    action = Maintenance(args.config, args.output)
    getattr(action, args.action)()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'error': type(error).__name__, 'detail': str(error) if isinstance(error, ValueError) else 'USB maintenance failed; private evidence retained'}), file=sys.stderr)
        raise SystemExit(1)
