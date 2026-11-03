"""Tests for recover_backup.py against the fixture written by the Kotlin BackupFormat code.

Run: python3 -m unittest discover -s tools -p "test_*.py" -v
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import recover_backup  # noqa: E402

FIXTURE = Path(__file__).parent / "testdata" / "sample-v1.spbak"
PASSPHRASE = "correct-horse-battery-staple"


class RecoverBackupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.words = recover_backup.load_wordlist()
        cls.data = FIXTURE.read_bytes()

    def test_header_uses_default_parameters(self):
        header = recover_backup.parse_header(self.data)
        self.assertEqual((65536, 3, 1), (header["memory_kib"], header["iterations"], header["parallelism"]))

    def test_decrypts_fixture_written_by_the_app_code(self):
        result = recover_backup.recover(self.data, PASSPHRASE, self.words)
        self.assertEqual("Stored Phrase test", result["manifest"]["generator"])
        first, second = result["entries"]
        self.assertEqual("Test 12", first["meta"]["label"])
        self.assertEqual(["abandon"] * 11 + ["about"], first["words"])
        self.assertTrue(first["checksumValid"])
        self.assertEqual("TREZOR", first["bip39Passphrase"])
        self.assertEqual("ví thử", first["notes"])
        self.assertIsNone(first["birthdayHeight"])
        self.assertEqual(3, first["meta"]["verifiedAt"])
        self.assertEqual("Zodl test", second["meta"]["label"])
        self.assertEqual(["abandon"] * 23 + ["art"], second["words"])
        self.assertTrue(second["checksumValid"])
        self.assertEqual(2726400, second["birthdayHeight"])

    def test_wrong_passphrase_is_rejected(self):
        with self.assertRaises(recover_backup.BackupError):
            recover_backup.recover(self.data, "wrong passphrase", self.words)

    def test_modified_header_is_rejected(self):
        tampered = bytearray(self.data)
        tampered[20] ^= 1  # salt byte, authenticated as AAD
        with self.assertRaises(recover_backup.BackupError):
            recover_backup.recover(bytes(tampered), PASSPHRASE, self.words)

    def test_hostile_parameters_are_rejected(self):
        tampered = bytearray(self.data)
        tampered[6] = 0x7F  # ~2 TiB of Argon2 memory
        with self.assertRaises(recover_backup.BackupError):
            recover_backup.parse_header(bytes(tampered))

    def test_bip39_checksum(self):
        abandon = self.words.index("abandon")
        self.assertTrue(recover_backup.bip39_checksum_ok([abandon] * 11 + [self.words.index("about")]))
        self.assertFalse(recover_backup.bip39_checksum_ok([abandon] * 11 + [self.words.index("above")]))


if __name__ == "__main__":
    unittest.main()
