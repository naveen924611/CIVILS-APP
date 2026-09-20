"""Small owner settings shared between the tablet and the server (study hours, revision caps, ...).

GET /kv            -> {key: value, ...}
GET /kv/{key}      -> {"key", "value"}   (value is null when the key was never saved)
PUT /kv/{key}      body {"value": <any JSON>}
Keys look like `study.hours`. Feature modules read them with `get_kv(db, key, default)`.
"""
import re
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth.deps import current_user
from app.db.models import Setting
from app.db.session import get_db

router = APIRouter(tags=["kv"])
KEY = re.compile(r"^[a-z0-9_.-]{1,80}$")


def get_kv(db: Session, key: str, default: Any = None) -> Any:
    row = db.get(Setting, key)
    return default if row is None else row.value_json


def set_kv(db: Session, key: str, value: Any) -> None:
    row = db.get(Setting, key)
    if row is None:
        db.add(Setting(key=key, value_json=value))
    else:
        row.value_json = value
    db.commit()


class KvIn(BaseModel):
    value: Any = None


@router.get("/kv")
def all_values(_: str = Depends(current_user), db: Session = Depends(get_db)):
    return {r.key: r.value_json for r in db.scalars(select(Setting)) if KEY.match(r.key)}


@router.get("/kv/{key}")
def one_value(key: str, _: str = Depends(current_user), db: Session = Depends(get_db)):
    if not KEY.match(key):
        raise HTTPException(400, "Bad key")
    return {"key": key, "value": get_kv(db, key)}


@router.put("/kv/{key}")
def put_value(key: str, body: KvIn, _: str = Depends(current_user), db: Session = Depends(get_db)):
    if not KEY.match(key):
        raise HTTPException(400, "Bad key")
    set_kv(db, key, body.value)
    return {"key": key, "value": body.value}
