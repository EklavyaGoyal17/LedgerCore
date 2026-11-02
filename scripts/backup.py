"""Usage: python scripts/backup.py backups/ledgercore-20261024.dump"""

import argparse
import json

from database_tools import backup

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Create a consistent custom-format PostgreSQL backup.")
    parser.add_argument("path", help="New output dump path (never overwritten)")
    args = parser.parse_args()
    print(json.dumps(backup(args.path), indent=2))
