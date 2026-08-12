import hashlib
import hmac
import html
import secrets
from datetime import UTC, datetime, timedelta
from urllib.parse import parse_qs

from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_request_settings, get_session
from app.core.config import Settings
from app.repositories.admin_users import AdminFamilyBindingSummary
from app.services.admin_users import AdminUserService

router = APIRouter(prefix="/admin", include_in_schema=False)
_LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost", "testclient"}
_ADMIN_USERS = {"admin", "test"}
_PASSWORD_SALT = b"silverage-admin-v1"
_PASSWORD_DIGEST = bytes.fromhex(
    "01af0814d61db8e07447a66e20cd75a3539b2ae204ff73aeea6d4ca684dc75b9"
)
_PBKDF2_ITERATIONS = 600_000
_SESSION_COOKIE = "silverage_admin_session"
_sessions: dict[str, tuple[str, datetime]] = {}
_action_handles: dict[str, dict[str, str]] = {}


def _is_available(request: Request, settings: Settings) -> bool:
    return bool(
        settings.admin_enabled
        and settings.app_environment.lower() == "development"
        and request.client is not None
        and request.client.host in _LOCAL_HOSTS
    )


def _current_admin(request: Request) -> str | None:
    token = request.cookies.get(_SESSION_COOKIE)
    if not token:
        return None
    session = _sessions.get(token)
    if session is None:
        return None
    username, expires_at = session
    if expires_at <= datetime.now(UTC):
        _sessions.pop(token, None)
        return None
    return username


def _session_token(request: Request) -> str | None:
    token = request.cookies.get(_SESSION_COOKIE)
    return token if token in _sessions else None


def _verify_credentials(username: str, password: str) -> bool:
    normalized_username = username.strip().lower()
    candidate = hashlib.pbkdf2_hmac(
        "sha256", password.encode(), _PASSWORD_SALT, _PBKDF2_ITERATIONS
    )
    return normalized_username in _ADMIN_USERS and hmac.compare_digest(candidate, _PASSWORD_DIGEST)


@router.get("", response_class=HTMLResponse)
async def index(
    request: Request,
    settings: Settings = Depends(get_request_settings),
) -> Response:
    if not _is_available(request, settings):
        return Response(status_code=404)
    if _current_admin(request) is None:
        return _secure_html_response(_render_login())
    return RedirectResponse("/admin/users", status_code=303)


@router.post("/login")
async def login(
    request: Request,
    settings: Settings = Depends(get_request_settings),
) -> Response:
    if not _is_available(request, settings):
        return Response(status_code=404)
    form = await _read_form(request, max_bytes=256)
    if form is None:
        return _secure_html_response(_render_login("登录请求无效。"), status_code=400)
    username = form.get("username", "")
    password = form.get("password", "")
    if not _verify_credentials(username, password):
        return _secure_html_response(_render_login("账号或密码不正确。"), status_code=401)
    token = secrets.token_urlsafe(32)
    _sessions[token] = (
        username.strip().lower(),
        datetime.now(UTC) + timedelta(seconds=settings.admin_session_ttl_seconds),
    )
    _action_handles[token] = {}
    response = RedirectResponse("/admin/users", status_code=303)
    response.set_cookie(
        _SESSION_COOKIE,
        token,
        max_age=settings.admin_session_ttl_seconds,
        httponly=True,
        secure=False,
        samesite="strict",
        path="/admin",
    )
    response.headers["Cache-Control"] = "no-store"
    return response


@router.post("/logout")
async def logout(
    request: Request,
    settings: Settings = Depends(get_request_settings),
) -> Response:
    if not _is_available(request, settings):
        return Response(status_code=404)
    token = request.cookies.get(_SESSION_COOKIE)
    if token:
        _sessions.pop(token, None)
        _action_handles.pop(token, None)
    response = RedirectResponse("/admin", status_code=303)
    response.delete_cookie(_SESSION_COOKIE, path="/admin")
    return response


@router.get("/users", response_class=HTMLResponse)
async def users(
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> Response:
    auth_failure = _require_admin(request, settings)
    if auth_failure is not None:
        return auth_failure
    bindings = await AdminUserService(
        session, request.app.state.database.binding_lock
    ).list_bindings()
    return _secure_html_response(_render_users(request, bindings, "全部绑定关系。"))


@router.post("/users", response_class=HTMLResponse)
async def search_users(
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> Response:
    auth_failure = _require_admin(request, settings)
    if auth_failure is not None:
        return auth_failure
    form = await _read_form(request, max_bytes=256)
    if form is None:
        return Response(status_code=400)
    query = form.get("q", "").strip()
    results: list[AdminFamilyBindingSummary] = []
    if not query or len(query) < 2 or len(query) > 50:
        message = "搜索词长度需要在 2 到 50 个字符之间。"
    else:
        results = await AdminUserService(
            session, request.app.state.database.binding_lock
        ).list_bindings(query)
        message = f"找到 {len(results)} 条绑定关系。" if results else "没有找到匹配的绑定关系。"
    return _secure_html_response(_render_users(request, results, message))


@router.post("/bindings/update")
async def update_binding(
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> Response:
    admin_username = _current_admin(request)
    if not _is_available(request, settings) or admin_username is None:
        return RedirectResponse("/admin", status_code=303)
    form = await _read_form(request, max_bytes=1024)
    binding_id = _resolve_binding_handle(request, form)
    if form is None or binding_id is None:
        return Response(status_code=400)
    updated = await AdminUserService(
        session, request.app.state.database.binding_lock
    ).update_binding(
        binding_id,
        form.get("family_display_name", ""),
        form.get("elder_display_name", ""),
        form.get("relationship", ""),
        admin_username,
    )
    if not updated:
        return Response(status_code=400)
    return RedirectResponse("/admin/users", status_code=303)


@router.post("/bindings/delete")
async def delete_user_data(
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> Response:
    admin_username = _current_admin(request)
    if not _is_available(request, settings) or admin_username is None:
        return RedirectResponse("/admin", status_code=303)
    form = await _read_form(request, max_bytes=512)
    binding_id = _resolve_binding_handle(request, form)
    if binding_id is None or form is None:
        return Response(status_code=400)
    deleted = await AdminUserService(
        session, request.app.state.database.binding_lock
    ).delete_user_data(
        binding_id,
        form.get("family_mobile_confirmation", ""),
        admin_username,
    )
    if deleted is None:
        bindings = await AdminUserService(
            session, request.app.state.database.binding_lock
        ).list_bindings()
        return _secure_html_response(
            _render_users(request, bindings, "删除失败：输入的家属完整手机号不正确。"),
            status_code=400,
        )
    for original_name, thumbnail_name in deleted.image_files:
        await request.app.state.safety_image_storage.delete(original_name, thumbnail_name)
    await request.app.state.connection_manager.disconnect_user_data(
        deleted.family_id,
        deleted.elder_ids,
        deleted.device_ids,
    )
    return RedirectResponse("/admin/users", status_code=303)


def _require_admin(request: Request, settings: Settings) -> Response | None:
    if not _is_available(request, settings):
        return Response(status_code=404)
    if _current_admin(request) is None:
        return RedirectResponse("/admin", status_code=303)
    return None


def _resolve_binding_handle(request: Request, form: dict[str, str] | None) -> str | None:
    token = _session_token(request)
    if token is None or form is None:
        return None
    return _action_handles.get(token, {}).get(form.get("binding_handle", ""))


async def _read_form(request: Request, *, max_bytes: int) -> dict[str, str] | None:
    content_type = request.headers.get("content-type", "").split(";", 1)[0]
    if content_type != "application/x-www-form-urlencoded":
        return None
    body = await request.body()
    if len(body) > max_bytes:
        return None
    try:
        parsed = parse_qs(body.decode("utf-8"), keep_blank_values=True)
    except UnicodeDecodeError:
        return None
    return {key: values[0] for key, values in parsed.items() if values}


def _secure_html_response(content: str, *, status_code: int = 200) -> HTMLResponse:
    response = HTMLResponse(content, status_code=status_code)
    response.headers.update(
        {
            "Cache-Control": "no-store",
            "Content-Security-Policy": (
                "default-src 'none'; style-src 'unsafe-inline'; "
                "form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
            ),
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Options": "DENY",
        }
    )
    return response


def _render_login(error: str = "") -> str:
    error_html = f'<p class="error">{html.escape(error)}</p>' if error else ""
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>中台登录</title>
<style>{_styles()}</style></head><body><main class="login"><section class="card">
<h1>银龄助手中台</h1><p class="privacy">仅限本机用户与绑定关系管理。</p>{error_html}
<form method="post" action="/admin/login" class="stack">
<label>管理员账号<input name="username" required autocomplete="username"></label>
<label>登录密码
<input name="password" type="password" required autocomplete="current-password"></label>
<button type="submit">登录</button></form></section></main></body></html>"""


def _render_users(
    request: Request,
    results: list[AdminFamilyBindingSummary],
    message: str,
) -> str:
    token = _session_token(request)
    handles: dict[str, str] = {}
    cards = ""
    for binding in results:
        handle = secrets.token_urlsafe(18)
        handles[handle] = binding.binding_id
        cards += _render_binding(binding, handle)
    if token is not None:
        _action_handles[token] = handles
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>家属绑定管理</title>
<style>{_styles()}</style></head><body><main><section class="card">
<header><div><h1>家属绑定管理</h1><p class="privacy">显示全部绑定关系，包括已撤销记录。</p></div>
<form method="post" action="/admin/logout"><button class="secondary">退出</button></form></header>
<form method="post" action="/admin/users" class="search">
<input name="q" maxlength="50" placeholder="筛选家属/老人姓名或完整手机号"
autocomplete="off" required>
<button type="submit">搜索</button></form><p class="message">{html.escape(message)}</p>
    {cards}
</section></main></body></html>"""


def _render_binding(binding: AdminFamilyBindingSummary, handle: str) -> str:
    options = "".join(
        f'<option value="{value}"'
        f'{(" selected" if binding.relationship == value else "")}>{label}</option>'
        for value, label in (
            ("CHILD", "子女"),
            ("RELATIVE", "亲属"),
            ("CAREGIVER", "照护者"),
            ("OTHER", "其他"),
        )
    )
    status = "有效" if binding.binding_is_active else "已撤销"
    update_form = ""
    if binding.binding_is_active:
        update_form = f"""<form method="post" action="/admin/bindings/update">
<input type="hidden" name="binding_handle" value="{handle}">
<div class="fields"><label>家属称呼<input name="family_display_name" maxlength="20"
value="{html.escape(binding.family_display_name)}" required></label>
<label>家属手机号<input value="{html.escape(binding.family_mobile_masked)}" disabled></label>
<label>老人称呼<input name="elder_display_name" maxlength="20"
value="{html.escape(binding.elder_display_name)}" required></label>
<label>老人手机号<input value="{html.escape(binding.elder_mobile_masked)}" disabled></label>
<label>关系<select name="relationship">{options}</select></label>
<label>有效设备<input value="{binding.active_device_count}" disabled></label></div>
<button type="submit">保存修改</button></form>"""
    return f"""<article class="family"><p><strong>绑定状态：{status}</strong></p>{update_form}
<form method="post" action="/admin/bindings/delete" class="delete-form">
<input type="hidden" name="binding_handle" value="{handle}">
<p class="warning">此操作不可恢复，将彻底删除该家属账号、其创建的全部老人档案、
绑定、设备凭据及相关中台业务数据。</p>
<label>输入家属完整手机号进行二次确认
<input name="family_mobile_confirmation" inputmode="numeric" pattern="1[3-9][0-9]{{9}}"
placeholder="家属完整手机号" autocomplete="off" required></label>
<button type="submit" class="danger">彻底删除用户数据</button></form></article>"""


def _styles() -> str:
    return """
body{margin:0;background:#f4f6f8;color:#17202a;font:16px/1.55 system-ui,sans-serif}
main{max-width:1080px;margin:40px auto;padding:0 24px}.login{max-width:440px}
.card{background:#fff;border:1px solid #dce2e8;border-radius:14px;padding:24px}
h1{margin:0 0 6px}.privacy,.message,.empty,small{color:#52606d}.stack{display:grid;gap:14px}
label{display:grid;gap:6px}.search,header{display:flex;gap:10px;align-items:center}
.search{flex-wrap:wrap}header{justify-content:space-between}
input{min-width:260px;flex:1;padding:12px;border:1px solid #9aa5b1;border-radius:9px}
button{padding:12px 22px;border:0;border-radius:9px;background:#145da0;color:#fff}
.secondary{background:#52606d}
.error{color:#a61b1b}.family{margin-top:18px;padding-top:16px;border-top:1px solid #dce2e8}
.fields{display:grid;grid-template-columns:repeat(3,minmax(180px,1fr));gap:12px;margin-bottom:12px}
select{padding:12px;border:1px solid #9aa5b1;border-radius:9px}.delete-form{margin-top:8px}
.danger{background:#a61b1b}.warning{color:#8a1515;font-weight:600}
h2{margin-bottom:4px}table{width:100%;border-collapse:collapse}
th,td{text-align:left;padding:10px;border-bottom:1px solid #e6e9ed}
th{font-size:14px;color:#486581}@media(max-width:720px){.card{overflow:auto}th,td{white-space:nowrap}}
"""
