"""Send push messages with Firebase. The service-account file stays on the server only."""
import logging

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db.models import Device

log = logging.getLogger(__name__)
_app = None


def _get_app():
    global _app
    if _app is None:
        import firebase_admin
        from firebase_admin import credentials

        path = get_settings().fcm_service_account_file
        if not path:
            raise RuntimeError("FCM_SERVICE_ACCOUNT_FILE is not set in .env")
        _app = firebase_admin.initialize_app(credentials.Certificate(path))
    return _app


def send_to_all(
    db: Session, title: str, body: str, data: dict[str, str] | None = None, notify: bool = True
) -> int:
    """notify=False sends a silent data message; the app then shows its own notification (with buttons)."""
    from firebase_admin import messaging

    _get_app()
    sent = 0
    for dev in db.scalars(select(Device).where(Device.deleted.is_(False))):
        payload = {"title": title, "body": body, **(data or {})}
        if notify:
            msg = messaging.Message(
                token=dev.fcm_token,
                notification=messaging.Notification(title=title, body=body),
                data=data or {},
            )
        else:
            msg = messaging.Message(
                token=dev.fcm_token,
                data=payload,
                android=messaging.AndroidConfig(priority="high"),
            )
        try:
            messaging.send(msg)
            sent += 1
        except Exception as exc:  # token may be stale; keep going
            log.warning("FCM send failed for device %s: %s", dev.id, exc)
    return sent
