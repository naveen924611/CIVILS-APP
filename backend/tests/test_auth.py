from tests.conftest import PASSWORD


def test_login_ok_and_me(client, auth_header):
    assert client.get("/auth/me", headers=auth_header).json() == {"username": "naveen"}


def test_login_wrong_password(client):
    r = client.post("/auth/login", json={"username": "naveen", "password": "nope"})
    assert r.status_code == 401


def test_login_wrong_username(client):
    r = client.post("/auth/login", json={"username": "someone", "password": PASSWORD})
    assert r.status_code == 401


def test_protected_needs_token(client):
    assert client.get("/auth/me").status_code == 401
    assert client.get("/auth/me", headers={"Authorization": "Bearer garbage"}).status_code == 401


def test_refresh_rotates(client, tokens):
    r = client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert r.status_code == 200
    new = r.json()
    assert new["refresh_token"] != tokens["refresh_token"]
    # the old refresh token no longer works
    again = client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert again.status_code == 401
    # the new one does
    ok = client.post("/auth/refresh", json={"refresh_token": new["refresh_token"]})
    assert ok.status_code == 200


def test_refresh_token_is_not_accepted_as_access_token(client, tokens):
    r = client.get("/auth/me", headers={"Authorization": f"Bearer {tokens['refresh_token']}"})
    assert r.status_code == 401


def test_logout_revokes_refresh(client, tokens, auth_header):
    r = client.post("/auth/logout", json={"refresh_token": tokens["refresh_token"]}, headers=auth_header)
    assert r.status_code == 204
    assert client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 401


def test_rate_limit_after_five_failures(client):
    for _ in range(5):
        assert client.post("/auth/login", json={"username": "naveen", "password": "bad"}).status_code == 401
    r = client.post("/auth/login", json={"username": "naveen", "password": PASSWORD})
    assert r.status_code == 429


def test_empty_hash_never_logs_in(client, monkeypatch):
    from app.config import get_settings

    monkeypatch.setenv("OWNER_PASSWORD_HASH", "")
    get_settings.cache_clear()
    r = client.post("/auth/login", json={"username": "naveen", "password": ""})
    assert r.status_code == 401
