import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zlib

spec = importlib.util.spec_from_file_location('usb_bootstrap', Path(__file__).resolve().parents[1] / 'esp32_usb_bootstrap.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SelectionTests(unittest.TestCase):
    def test_private_evidence_is_written_with_restricted_permissions(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'evidence.json'
            module.private_json(path, {'verified': True})
            self.assertIn('true',path.read_text())
            self.assertEqual(path.stat().st_mode & 0o777,0o600)

    def snapshot(self):
        data = bytearray(b'\xff' * module.FLASH_SIZE)
        struct.pack_into('<I', data, 0xd000, 1)
        struct.pack_into('<I', data, 0xd018, 2)
        struct.pack_into('<I', data, 0xd01c, zlib.crc32(struct.pack('<I', 1), 0xffffffff) & 0xffffffff)
        return data

    def test_selection_preserves_valid_old_slot_and_requires_boot_verification(self):
        original = self.snapshot()
        selection = module.ota_selection(original)
        self.assertEqual(struct.unpack_from('<I', selection, 0)[0], 2)
        self.assertEqual(struct.unpack_from('<I', selection, 24)[0], 0)
        self.assertEqual(struct.unpack_from('<I', original, 0xd018)[0], 2)
        self.assertEqual(selection[32:], b'\xff' * (4096-32))

    def test_rejects_used_selection_or_unconfirmed_original(self):
        data = self.snapshot(); data[0xe000] = 1
        with self.assertRaises(ValueError): module.ota_selection(data)

    def test_known_valid_image_copy_preserves_active_image_nvs_assets_and_bootloader(self):
        data = self.snapshot()
        app = b'known application'*100
        data[module.APP_OFFSET:module.APP_OFFSET+len(app)] = app
        struct.pack_into('<I', data, 0xe000, 2)
        struct.pack_into('<I', data, 0xe018, 2)
        struct.pack_into('<I', data, 0xe01c, zlib.crc32(struct.pack('<I', 2), 0xffffffff) & 0xffffffff)
        expected, padded = module.rollback_slot_copy(data, app)
        self.assertEqual(expected[:0xe000], data[:0xe000])
        self.assertEqual(expected[0xf000:0x20000], data[0xf000:0x20000])
        self.assertEqual(expected[0x20000:0x20000+len(padded)], padded)
        self.assertEqual(expected[0x20000+len(padded):], data[0x20000+len(padded):])
        self.assertEqual(expected[0xe000:0xf000], b'\xff'*4096)
        with self.assertRaises(ValueError): module.rollback_slot_copy(data, b'other application'*100)
        struct.pack_into('<I', data, 0xe018, 1)
        with self.assertRaises(ValueError): module.rollback_slot_copy(data, app)
        data = self.snapshot(); struct.pack_into('<I', data, 0xd018, 1)
        with self.assertRaises(ValueError): module.ota_selection(data)


@unittest.skipUnless(os.environ.get('ESP32_BOOTSTRAP_IDF_PATH'), 'requires prepared ESP-IDF NVS tools')
class NvsTests(unittest.TestCase):
    def test_official_parser_confirms_wifi_identity_and_repeat_metadata_preservation(self):
        idf = os.environ['ESP32_BOOTSTRAP_IDF_PATH']
        python = os.environ['ESP32_BOOTSTRAP_PYTHON']
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); csv = folder/'fixture.csv'; source = folder/'source.bin'
            csv.write_text('key,type,encoding,value\nwifi,namespace,,\nssid,data,string,fixture-network\npassword,data,string,fixture-password\nboard,namespace,,\ndev_mac,data,string,02:21:64:63:44:5a\nuuid,data,string,fixture-identity\n')
            subprocess.run([python, str(Path(idf)/'components/nvs_flash/nvs_partition_generator/nvs_partition_gen.py'), 'generate', str(csv), str(source), '0x4000'], check=True, capture_output=True)
            target = folder/'target.bin'
            result = module.prepare_nvs(source, target, 'a'*32, '2.5.3', python, idf)
            self.assertGreaterEqual(result['preserved_records'], 4)
            again = folder/'again.bin'
            module.prepare_nvs(target, again, 'b'*32, '2.5.4', python, idf)
            records = module.nvs_records(again, python, idf)
            self.assertEqual(records[('release_ota', 'release_id')][1], 'b'*32)
            self.assertEqual(records[('release_ota', 'version')][1], '2.5.4')
            self.assertEqual(records[('wifi', 'password')][1], 'fixture-password')
            self.assertEqual(records[('board', 'uuid')][1], 'fixture-identity')


if __name__ == '__main__': unittest.main()
