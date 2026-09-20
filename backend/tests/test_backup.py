import sqlite3
import tarfile

import pytest

from app.tools.backup import DB_NAME, create_backup, restore_backup


def _make_db(path, value):
    con = sqlite3.connect(path)
    con.execute("create table t (v text)")
    con.execute("insert into t values (?)", (value,))
    con.commit()
    con.close()


def test_backup_and_restore_roundtrip(tmp_path):
    src = tmp_path / "src"
    (src / "audio").mkdir(parents=True)
    (src / "audio" / "brief.mp3").write_bytes(b"mp3")
    _make_db(src / DB_NAME, "hello")

    archive = create_backup(src, src / DB_NAME, tmp_path / "out")

    dst = tmp_path / "dst"
    restore_backup(archive, dst)
    con = sqlite3.connect(dst / DB_NAME)
    assert con.execute("select v from t").fetchone() == ("hello",)
    con.close()
    assert (dst / "audio" / "brief.mp3").read_bytes() == b"mp3"


def test_restore_refuses_to_overwrite_without_force(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _make_db(src / DB_NAME, "a")
    archive = create_backup(src, src / DB_NAME, tmp_path / "out")
    with pytest.raises(SystemExit):
        restore_backup(archive, src)
    restore_backup(archive, src, force=True)
    assert (src / f"{DB_NAME}.bak").exists()


def test_old_backups_are_pruned(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _make_db(src / DB_NAME, "a")
    out = tmp_path / "out"
    out.mkdir()
    for i in range(5):
        (out / f"civils-backup-2020010{i}-000000.tar.gz").write_bytes(b"x")
    create_backup(src, src / DB_NAME, out, keep=3)
    assert len(list(out.glob("civils-backup-*.tar.gz"))) == 3


def test_restore_rejects_path_traversal(tmp_path):
    evil = tmp_path / "evil.tar.gz"
    payload = tmp_path / "p.txt"
    payload.write_text("x")
    with tarfile.open(evil, "w:gz") as tar:
        tar.add(payload, arcname="../escape.txt")
    with pytest.raises(SystemExit):
        restore_backup(evil, tmp_path / "dst")
    assert not (tmp_path / "escape.txt").exists()
