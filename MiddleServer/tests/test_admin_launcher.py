import pytest

from app.admin_launcher import admin_url, parse_args


def test_admin_launcher_accepts_custom_loopback_host_and_port() -> None:
    args = parse_args(["--host", "127.0.0.2", "--port", "8765", "--open-browser"])
    assert args.host == "127.0.0.2"
    assert args.port == 8765
    assert args.open_browser is True
    assert admin_url(args.host, args.port) == "http://127.0.0.2:8765/admin"
    assert admin_url("::1", 9000) == "http://[::1]:9000/admin"


@pytest.mark.parametrize("host", ["0.0.0.0", "192.168.1.20", "203.0.113.10", "::"])
def test_admin_launcher_accepts_external_binding_addresses(host: str) -> None:
    args = parse_args(["--host", host, "--port", "8765"])
    assert args.host == host


@pytest.mark.parametrize(
    "arguments",
    [
        ["--host", "localhost"],
        ["--host", "not-an-ip"],
        ["--port", "0"],
        ["--port", "65536"],
    ],
)
def test_admin_launcher_rejects_non_loopback_or_invalid_ports(arguments: list[str]) -> None:
    with pytest.raises(SystemExit):
        parse_args(arguments)
