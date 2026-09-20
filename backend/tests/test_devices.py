def test_register_device_requires_login(client):
    r = client.post("/devices/register", json={"fcm_token": "x" * 30})
    assert r.status_code == 401


def test_register_device_twice_is_ok(client, auth_header):
    body = {"fcm_token": "t" * 40, "label": "Tab M10"}
    assert client.post("/devices/register", json=body, headers=auth_header).status_code == 200
    assert client.post("/devices/register", json=body, headers=auth_header).status_code == 200
