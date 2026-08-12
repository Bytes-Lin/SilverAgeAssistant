import argparse
import ipaddress
import threading
import webbrowser

import uvicorn

from app.core.config import Settings
from app.main import create_app


def parse_args(args: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="启动银龄助手本机中台管理页面")
    parser.add_argument(
        "--host",
        default="127.0.0.1",
        help="监听 IP；公网/局域网服务通常使用 0.0.0.0，默认 127.0.0.1",
    )
    parser.add_argument("--port", type=int, default=8000, help="监听端口，默认 8000")
    parser.add_argument("--open-browser", action="store_true", help="启动后自动打开浏览器")
    parsed = parser.parse_args(args)
    try:
        ipaddress.ip_address(parsed.host)
    except ValueError:
        parser.error("--host 必须是有效的 IPv4 或 IPv6 地址")
    if not 1 <= parsed.port <= 65_535:
        parser.error("--port 必须在 1 到 65535 之间")
    return parsed


def admin_url(host: str, port: int) -> str:
    browser_host = f"[{host}]" if ":" in host else host
    return f"http://{browser_host}:{port}/admin"


def main() -> None:
    args = parse_args()
    settings = Settings(admin_enabled=True)
    app = create_app(settings)
    if args.open_browser:
        threading.Timer(1.0, webbrowser.open, args=(admin_url(args.host, args.port),)).start()
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
