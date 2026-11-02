"""Safety boundaries for restore/backup; the actual restore drill tests PostgreSQL."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from database_tools import backup, restore


class DatabaseToolSafetyTest(unittest.TestCase):
    def test_live_or_unsafe_target_is_rejected_before_any_database_command(self):
        with patch("database_tools.compose") as command:
            for name in ("ledgercore", "postgres", "ledgercore_restore_a;DROP DATABASE ledgercore", ""):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    restore("missing.dump", name)
            command.assert_not_called()

    def test_corrupt_backup_is_rejected_before_creating_database(self):
        with tempfile.TemporaryDirectory() as folder, patch("database_tools.compose") as command:
            path = Path(folder) / "backup.dump"
            path.write_bytes(b"corrupt-or-incomplete-backup")
            path.with_suffix(".dump.json").write_text(json.dumps({"sha256": "wrong"}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                restore(path, "ledgercore_restore_safety_test")
            command.assert_not_called()

    def test_existing_backup_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder, patch("database_tools.compose") as command:
            path = Path(folder) / "backup.dump"
            path.write_bytes(b"original-backup")
            with self.assertRaises(FileExistsError):
                backup(path)
            self.assertEqual(path.read_bytes(), b"original-backup")
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
