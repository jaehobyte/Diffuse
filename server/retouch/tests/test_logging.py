import logging

from conftest import TOKEN, metadata, post, standard_parts


def test_logs_carry_request_id_kind_status_and_timings_only(client, caplog):
    caplog.set_level(logging.INFO)  # the level the service runs at
    ok = post(client, standard_parts(meta=metadata(request_id="log-check-1")))
    assert ok.status_code == 200
    denied = post(client, standard_parts(meta=metadata(request_id="log-check-2")), token="wrong-token-value")
    assert denied.status_code == 403
    bad = post(client, standard_parts(meta=metadata(request_id="log-check-3", contract_version=9)))
    assert bad.status_code == 400

    text = caplog.text
    assert "request_id=log-check-1 kind=blemish status=200" in text
    assert "timing_ms=" in text
    assert "status=403" in text
    assert "request_id=log-check-3" in text
    for forbidden in (TOKEN, "wrong-token-value", "Bearer", "authorization", "PNG", "IHDR", "expected_engine_version"):
        assert forbidden.lower() not in text.lower(), forbidden
    for record in caplog.records:
        assert not isinstance(record.args, dict)
        for arg in record.args or ():
            assert not isinstance(arg, (bytes, bytearray))
