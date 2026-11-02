"""Exercise a real backup and restore without removing any existing data or volumes."""

import json
import tempfile
import uuid
from pathlib import Path

from database_tools import backup, restore, snapshot, sql

if __name__ == "__main__":
    database = "ledgercore_restore_" + uuid.uuid4().hex[:12]
    before = snapshot()
    with tempfile.TemporaryDirectory(prefix="ledgercore-restore-") as folder:
        path = Path(folder) / "backup.dump"
        metadata = backup(path)
        restored = restore(path, database)
        after = snapshot()
        if before != after:
            raise RuntimeError("Live database changed during drill; rerun with writers paused to compare exact counts")
        if restored != before:
            raise RuntimeError("Restored counts/invariants differ from the source")
        # Only the UUID-named database created by this invocation is removed.
        sql(f'DROP DATABASE "{database}"')
        print(json.dumps({"status": "PASSED", "sha256": metadata["sha256"], "verified": restored,
                          "restore_database": database, "cleanup": "disposable database removed"}, indent=2))
