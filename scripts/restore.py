"""Restore into a new isolated database; never overwrites the live LedgerCore database."""

import argparse
import json

from database_tools import restore

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", help="Custom dump with adjacent .json checksum manifest")
    parser.add_argument("--database", required=True, help="New ledgercore_restore_<suffix> database")
    args = parser.parse_args()
    print(json.dumps(restore(args.path, args.database), indent=2))
