"""Backup and restore, so the server can move between machines (laptop <-> Oracle).

  python -m app.tools.backup create [--out DIR] [--keep 14]
  python -m app.tools.backup restore FILE [--force]

A backup is one .tar.gz holding a consistent copy of the SQLite database plus the
`uploads/` and `audio/` folders (if they exist). Only these names are ever restored.
"""
import argparse
import shutil
import sqlite3
import sys
import tarfile
import tempfile
from datetime import datetime
from pathlib import Path

from app.config import get_settings

DB_NAME = "civils.db"
EXTRA_DIRS = ("uploads", "audio")


def _db_path() -> Path:
    url = get_settings().database_url
    if not url.startswith("sqlite:///"):
        raise SystemExit("Backup only supports the SQLite database.")
    return Path(url.replace("sqlite:///", "", 1))


def create_backup(data_dir: Path, db_file: Path, out_dir: Path, keep: int = 14) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    archive = out_dir / f"civils-backup-{stamp}.tar.gz"
    with tempfile.TemporaryDirectory() as tmp:
        snapshot = Path(tmp) / DB_NAME
        # sqlite's own backup API gives a consistent copy even while the server is writing
        src = sqlite3.connect(db_file)
        dst = sqlite3.connect(snapshot)
        with dst:
            src.backup(dst)
        src.close()
        dst.close()
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(snapshot, arcname=DB_NAME)
            for name in EXTRA_DIRS:
                folder = data_dir / name
                if folder.is_dir():
                    tar.add(folder, arcname=name)
    old = sorted(out_dir.glob("civils-backup-*.tar.gz"))[:-keep] if keep > 0 else []
    for f in old:
        f.unlink()
    return archive


def _safe(member: tarfile.TarInfo) -> bool:
    p = Path(member.name)
    if p.is_absolute() or ".." in p.parts:
        return False
    if not (member.isfile() or member.isdir()):
        return False  # no links or devices
    return p.parts[0] == DB_NAME or p.parts[0] in EXTRA_DIRS


def restore_backup(archive: Path, data_dir: Path, force: bool = False) -> None:
    db_file = data_dir / DB_NAME
    if db_file.exists() and not force:
        raise SystemExit(f"{db_file} already exists. Use --force to replace it (kept as .bak).")
    data_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        bad = [m.name for m in members if not _safe(m)]
        if bad:
            raise SystemExit(f"Refusing to restore, unexpected entries: {bad[:3]}")
        if DB_NAME not in [m.name for m in members]:
            raise SystemExit("This archive has no database.")
        if db_file.exists():
            shutil.copy2(db_file, data_dir / f"{DB_NAME}.bak")
        for wal in ("-wal", "-shm"):  # stale write-ahead files would corrupt the restored db
            (data_dir / f"{DB_NAME}{wal}").unlink(missing_ok=True)
        tar.extractall(data_dir, members=members)  # members were validated above


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="app.tools.backup")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("create")
    c.add_argument("--out", default=None, help="folder for the backup (default: <data>/backups)")
    c.add_argument("--keep", type=int, default=14)
    r = sub.add_parser("restore")
    r.add_argument("file")
    r.add_argument("--force", action="store_true")
    args = ap.parse_args(argv)

    db_file = _db_path()
    data_dir = db_file.parent
    if args.cmd == "create":
        out = Path(args.out) if args.out else data_dir / "backups"
        print(f"Backup written: {create_backup(data_dir, db_file, out, args.keep)}")
    else:
        restore_backup(Path(args.file), data_dir, args.force)
        print("Restored. Restart the server now.")


if __name__ == "__main__":
    sys.exit(main())
