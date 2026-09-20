import pytest
from argon2 import PasswordHasher
from fastapi.testclient import TestClient

from app.auth.ratelimit import limiter
from app.config import get_settings
from app.db.session import reset_engine

PASSWORD = "correct-horse-battery"


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path}/test.db")
    monkeypatch.setenv("JWT_SECRET", "test-secret-test-secret-test-secret-1234")
    monkeypatch.setenv("OWNER_USERNAME", "naveen")
    monkeypatch.setenv("SCHEDULER_ENABLED", "false")
    monkeypatch.setenv("DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("OWNER_PASSWORD_HASH", PasswordHasher().hash(PASSWORD))
    get_settings.cache_clear()
    reset_engine()
    limiter.reset()
    from app.main import create_app

    with TestClient(create_app()) as c:
        yield c
    reset_engine()
    get_settings.cache_clear()


@pytest.fixture
def tokens(client):
    r = client.post("/auth/login", json={"username": "naveen", "password": PASSWORD})
    assert r.status_code == 200
    return r.json()


@pytest.fixture
def auth_header(tokens):
    return {"Authorization": f"Bearer {tokens['access_token']}"}


@pytest.fixture
def env(tmp_path, monkeypatch):
    """Settings + session factory on a fresh database, for tests that do not need the web app."""
    from app.db.base import Base
    from app.db.session import get_engine, get_session_factory

    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path}/env.db")
    monkeypatch.setenv("JWT_SECRET", "test-secret-test-secret-test-secret-1234")
    monkeypatch.setenv("DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("GEMINI_API_KEY", "g-test")
    monkeypatch.setenv("GROQ_API_KEY", "q-test")
    monkeypatch.setenv("FEEDS_FILE", str(tmp_path / "feeds.yaml"))
    monkeypatch.setenv("SCHEDULER_ENABLED", "false")
    get_settings.cache_clear()
    reset_engine()
    Base.metadata.create_all(get_engine())
    yield get_settings(), get_session_factory()
    reset_engine()
    get_settings.cache_clear()
