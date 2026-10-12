import threading

import pytest
from fastapi.testclient import TestClient

from app.config import KINDS, QUALIFIED_KINDS, ConfigError, Settings, load_settings
from app.main import create_app
from conftest import FakeEngine, auth, factory_of, metadata, post, settings, standard_parts, wait_ready


@pytest.mark.parametrize("header", [None, "Basic abc", "Bearer", "Bearer ", "Token test-token-not-a-secret"])
def test_missing_or_non_bearer_is_401_with_challenge(client, header):
    headers = {} if header is None else {"Authorization": header}
    for response in (
        client.get("/health", headers=headers),
        client.post("/v1/retouch", content=b"x", headers={**headers, "Content-Type": "text/plain"}),
    ):
        assert response.status_code == 401
        assert response.json() == {"error": "unauthorized"}
        assert response.headers["www-authenticate"] == "Bearer"


def test_wrong_token_is_403(client):
    assert client.get("/health", headers=auth("wrong")).status_code == 403
    response = post(client, standard_parts(), token="wrong")
    assert response.status_code == 403
    assert response.json() == {"error": "forbidden"}


def test_health_is_503_until_load_and_warm_up_finish():
    load_gate, warm_gate = threading.Event(), threading.Event()
    engine = FakeEngine("blemish", load_gate=load_gate, warm_gate=warm_gate)
    app = create_app(settings(enabled_kinds=("blemish",)), factory_of({"blemish": engine}))
    with TestClient(app) as client:
        response = client.get("/health", headers=auth())
        assert response.status_code == 503
        assert response.json() == {
            "contract_version": 1,
            "status": "loading",
            "supported_kinds": [],
            "engines": {},
            "evaluation_engines": {},
        }

        # Requests are refused while loading.
        refused = post(client, standard_parts())
        assert refused.status_code == 503
        assert refused.json() == {"error": "not_ready", "request_id": "req-1"}

        load_gate.set()  # loaded, but warm-up has not run
        assert client.get("/health", headers=auth()).status_code == 503
        warm_gate.set()
        ready = wait_ready(client)
        assert ready.json() == {
            "contract_version": 1,
            "status": "ready",
            "supported_kinds": ["blemish"],
            "engines": {"blemish": "blemish/fake@1"},
            "evaluation_engines": {},
        }


def test_a_failed_engine_makes_health_failed():
    engines = {"blemish": FakeEngine("blemish", fail_load=True), "shine": FakeEngine("shine")}
    app = create_app(settings(enabled_kinds=("blemish", "shine")), factory_of(engines))
    with TestClient(app) as client:
        for _ in range(500):
            body = client.get("/health", headers=auth()).json()
            if body["status"] != "loading":
                break
        response = client.get("/health", headers=auth())
        assert response.status_code == 503
        assert response.json()["status"] == "failed"
        assert response.json()["supported_kinds"] == []


def test_supported_kinds_are_enabled_and_loaded_only():
    engines = {k: FakeEngine(k) for k in ("blemish", "shine", "dark_circles", "shaving_shadow")}
    app = create_app(settings(enabled_kinds=("shaving_shadow", "blemish")), factory_of(engines))
    with TestClient(app) as client:
        body = wait_ready(client).json()
        assert body["supported_kinds"] == ["blemish", "shaving_shadow"]
        assert body["engines"] == {"blemish": "blemish/fake@1", "shaving_shadow": "shaving_shadow/fake@1"}
        response = post(client, standard_parts(meta=metadata(kind="shine", expected_engine_version="shine/fake@1")))
        assert response.status_code == 422
        assert response.json() == {"error": "unsupported_kind", "request_id": "req-1"}
        assert engines["shine"].calls == 0


def test_evaluation_kinds_are_callable_but_never_reported_as_supported():
    engines = {k: FakeEngine(k) for k in ("blemish", "shine")}
    app = create_app(
        settings(qualified_kinds=("shine",), enabled_kinds=("shine",), evaluation_kinds=("blemish",)),
        factory_of(engines),
    )
    with TestClient(app) as client:
        body = wait_ready(client).json()
        assert body["supported_kinds"] == ["shine"]
        assert body["engines"] == {"shine": "shine/fake@1"}
        assert body["evaluation_engines"] == {"blemish": "blemish/fake@1"}
        assert post(client, standard_parts()).status_code == 200
        assert engines["blemish"].calls == 1


def test_a_kind_without_a_passed_gate_cannot_be_enabled(tmp_path):
    # Omitting the setting enables exactly the qualified kinds, never every engine.
    parsed = load_settings({"RETOUCH_AUTH_TOKEN": "x", "RETOUCH_EVALUATION_KINDS": "blemish"})
    assert parsed.enabled_kinds == QUALIFIED_KINDS
    assert parsed.qualified_kinds == QUALIFIED_KINDS
    unqualified = [k for k in KINDS if k not in QUALIFIED_KINDS]
    for kind in unqualified:
        with pytest.raises(ConfigError):
            load_settings({"RETOUCH_AUTH_TOKEN": "x", "RETOUCH_ENABLED_KINDS": kind})
        # The gate is code, not an environment variable an operator could set.
        with pytest.raises(ConfigError):
            load_settings(
                {"RETOUCH_AUTH_TOKEN": "x", "RETOUCH_ENABLED_KINDS": kind, "RETOUCH_QUALIFIED_KINDS": kind}
            )
    with pytest.raises(ConfigError):
        Settings(auth_token="x", model_dir=tmp_path, qualified_kinds=())  # nothing to load
    with pytest.raises(ConfigError):
        Settings(auth_token="x", model_dir=tmp_path, qualified_kinds=KINDS, enabled_kinds=("blemish",),
                 evaluation_kinds=("blemish",))


def test_settings_refuse_to_start_without_a_token_or_with_unknown_kinds(tmp_path):
    with pytest.raises(ConfigError):
        Settings(auth_token="", model_dir=tmp_path)
    with pytest.raises(ConfigError):
        load_settings({"RETOUCH_AUTH_TOKEN": "   "})
    with pytest.raises(ConfigError):
        load_settings({"RETOUCH_AUTH_TOKEN": "x", "RETOUCH_EVALUATION_KINDS": "blemish,wrinkles"})
    parsed = load_settings(
        {"RETOUCH_AUTH_TOKEN": "x", "RETOUCH_EVALUATION_KINDS": "shine, blemish", "RETOUCH_MAX_QUEUED": "2"}
    )
    assert parsed.evaluation_kinds == ("shine", "blemish")
    assert parsed.max_queued == 2 and parsed.max_running == 1 and parsed.job_deadline_s == 55.0
    assert parsed.port == 8084 and parsed.host == "127.0.0.1"


def test_deployment_entry_point_has_no_fake_engine_path():
    import inspect

    import app.serve as serve

    source = inspect.getsource(serve)
    assert "build_real_engines" in source
    assert "fake" not in source.lower().replace("there is no fake switch", "")
