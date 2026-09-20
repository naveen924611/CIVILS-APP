from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth.deps import current_user
from app.db.models import Device
from app.db.session import get_db

router = APIRouter(prefix="/devices", tags=["devices"])


class DeviceIn(BaseModel):
    fcm_token: str = Field(min_length=20, max_length=512)
    label: str = Field(default="", max_length=100)


@router.post("/register")
def register(body: DeviceIn, _: str = Depends(current_user), db: Session = Depends(get_db)):
    """The app calls this after login (and whenever Firebase gives it a new token)."""
    row = db.scalar(select(Device).where(Device.fcm_token == body.fcm_token))
    if row is None:
        db.add(Device(fcm_token=body.fcm_token, label=body.label))
    else:
        row.label = body.label or row.label
        row.deleted = False
    db.commit()
    return {"ok": True}
