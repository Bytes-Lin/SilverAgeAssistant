import base64
import hashlib
import hmac
import re
import secrets
from datetime import UTC, datetime, timedelta
from typing import Any

import jwt

from app.core.config import Settings
from app.core.errors import ApiError

_MOBILE_SEPARATORS = re.compile(r"[\s\-()]")
_MAINLAND_MOBILE = re.compile(r"1[3-9]\d{9}")


def utc_now() -> datetime:
    return datetime.now(UTC)


def ensure_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def normalize_mainland_mobile(value: str) -> str:
    normalized = _MOBILE_SEPARATORS.sub("", value.strip())
    if normalized.startswith("+86"):
        normalized = normalized[3:]
    elif normalized.startswith("0086"):
        normalized = normalized[4:]
    if not _MAINLAND_MOBILE.fullmatch(normalized):
        raise ApiError(400, "INVALID_MOBILE_FORMAT", "手机号格式不正确")
    return normalized


def mask_mobile(normalized: str) -> str:
    return f"{normalized[:3]}****{normalized[-4:]}"


def keyed_digest(secret: str, purpose: str, value: str) -> str:
    message = f"{purpose}:{value}".encode()
    return hmac.new(secret.encode(), message, hashlib.sha256).hexdigest()


def hash_binding_code(secret: str, salt: str, code: str) -> str:
    return keyed_digest(secret, "binding-code", f"{salt}:{code}")


def format_binding_code(value: int) -> str:
    return f"{value % 1_000_000:06d}"


def derive_binding_code(
    secret: str,
    family_account_id: str,
    elder_id: str,
    client_request_id: str,
    server_nonce: str,
) -> str:
    material = f"{family_account_id}:{elder_id}:{client_request_id}:{server_nonce}"
    digest = hmac.new(secret.encode(), material.encode(), hashlib.sha256).digest()
    return format_binding_code(int.from_bytes(digest[:8], "big"))


def derive_device_credential(
    secret: str,
    binding_id: str,
    device_id: str,
    client_request_id: str,
) -> str:
    material = f"device-credential:{binding_id}:{device_id}:{client_request_id}"
    digest = hmac.new(secret.encode(), material.encode(), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


def random_salt() -> str:
    return secrets.token_hex(16)


def create_jwt(
    settings: Settings,
    subject: str,
    token_type: str,
    ttl_seconds: int,
    extra: dict[str, Any] | None = None,
) -> tuple[str, datetime]:
    now = utc_now()
    expires_at = now + timedelta(seconds=ttl_seconds)
    claims: dict[str, Any] = {
        "sub": subject,
        "type": token_type,
        "iat": now,
        "exp": expires_at,
        "jti": secrets.token_urlsafe(16),
    }
    if extra:
        claims.update(extra)
    encoded = jwt.encode(claims, settings.jwt_secret, algorithm="HS256")
    token = encoded.decode() if isinstance(encoded, bytes) else encoded
    return token, expires_at


def decode_jwt(settings: Settings, token: str, expected_type: str) -> dict[str, Any]:
    try:
        claims = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    except jwt.PyJWTError as exc:
        raise ApiError(401, "AUTHENTICATION_REQUIRED", "认证信息无效或已过期") from exc
    if claims.get("type") != expected_type or not isinstance(claims.get("sub"), str):
        raise ApiError(401, "AUTHENTICATION_REQUIRED", "认证信息无效或已过期")
    return claims
