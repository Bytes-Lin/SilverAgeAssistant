import asyncio
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from starlette.middleware.base import RequestResponseEndpoint

from app.api.v1.router import api_router
from app.core.config import Settings, get_settings
from app.core.database import Database
from app.core.errors import ApiError
from app.services.safety_image_storage import SafetyImageStorage
from app.services.safety_monitoring import SafetyMonitoringService
from app.websocket.manager import ConnectionManager


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or get_settings()
    database = Database(resolved_settings)
    connection_manager = ConnectionManager()
    safety_image_storage = SafetyImageStorage(
        resolved_settings.safety_image_storage_path,
        resolved_settings.safety_image_thumbnail_max_pixels,
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        if resolved_settings.auto_create_schema:
            await database.create_schema()
        await safety_image_storage.initialize()
        cleanup_task = asyncio.create_task(
            _safety_image_cleanup_loop(
                database,
                resolved_settings,
                connection_manager,
                safety_image_storage,
            )
        )
        try:
            yield
        finally:
            cleanup_task.cancel()
            with suppress(asyncio.CancelledError):
                await cleanup_task
            await database.dispose()

    app = FastAPI(
        title=resolved_settings.app_name,
        version="0.1.0",
        lifespan=lifespan,
        description=("银龄助手轻量中台。手机号首版按中国大陆规则规范化；业务部署必须使用 HTTPS。"),
    )
    app.state.settings = resolved_settings
    app.state.database = database
    app.state.connection_manager = connection_manager
    app.state.safety_image_storage = safety_image_storage

    @app.middleware("http")
    async def request_id_middleware(
        request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        if (
            request.url.path == "/api/v1/devices/me/family-contacts"
            or request.url.path.endswith("/model-config")
            or "/model-usage" in request.url.path
            or "/safety-monitoring/" in request.url.path
            or "/safety-events" in request.url.path
        ) and "Cache-Control" not in response.headers:
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.exception_handler(ApiError)
    async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
        request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
        error_body: dict[str, object] = {
            "code": exc.code,
            "message": exc.message,
            "request_id": request_id,
        }
        if exc.details is not None:
            error_body["details"] = exc.details
        return JSONResponse(
            status_code=exc.status_code,
            content={"error": error_body},
            headers={"X-Request-ID": request_id},
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
        missing_idempotency_key = any(
            error.get("type") == "missing"
            and tuple(error.get("loc", ())) == ("header", "Idempotency-Key")
            for error in exc.errors()
        )
        command_content_fields = {"content", "created_at", "title", "scheduled_at", "timezone"}
        invalid_command_content = "/commands/" in request.url.path and any(
            len(error.get("loc", ())) >= 2
            and error["loc"][0] == "body"
            and error["loc"][-1] in command_content_fields
            for error in exc.errors()
        )
        invalid_model_configuration = (
            request.method == "PUT"
            and request.url.path.endswith("/model-config")
            and any(
                len(error.get("loc", ())) >= 1 and error["loc"][0] == "body"
                for error in exc.errors()
            )
        )
        is_safety_configuration_update = request.method == "PUT" and request.url.path.endswith(
            "/safety-monitoring/config"
        )
        invalid_safety_enabled = is_safety_configuration_update and any(
            len(error.get("loc", ())) >= 2
            and error["loc"][0] == "body"
            and error["loc"][-1] == "enabled"
            for error in exc.errors()
        )
        invalid_safety_interval = is_safety_configuration_update and any(
            len(error.get("loc", ())) >= 2
            and error["loc"][0] == "body"
            and error["loc"][-1] == "interval_minutes"
            for error in exc.errors()
        )
        invalid_safety_configuration = is_safety_configuration_update and any(
            len(error.get("loc", ())) >= 1 and error["loc"][0] == "body" for error in exc.errors()
        )
        invalid_safety_event = (
            request.method == "POST"
            and request.url.path == "/api/v1/devices/me/safety-events"
            and any(
                len(error.get("loc", ())) >= 1 and error["loc"][0] == "body"
                for error in exc.errors()
            )
        )
        is_usage_upload = (
            request.method == "POST" and request.url.path == "/api/v1/model-usage/batches"
        )
        usage_batch_too_large = is_usage_upload and any(
            error.get("type") == "too_long" and tuple(error.get("loc", ())) == ("body", "items")
            for error in exc.errors()
        )
        invalid_usage_query = (
            request.method == "GET"
            and request.url.path.endswith("/model-usage")
            and any(
                error.get("loc", ())[0] == "query" for error in exc.errors() if error.get("loc")
            )
        )
        if invalid_safety_enabled and not missing_idempotency_key:
            error_code = "INVALID_SAFETY_ENABLED"
        elif invalid_safety_interval and not missing_idempotency_key:
            error_code = "INVALID_SAFETY_INTERVAL"
        elif invalid_safety_event and not missing_idempotency_key:
            error_code = "INVALID_SAFETY_EVENT"
        elif usage_batch_too_large:
            error_code = "USAGE_BATCH_TOO_LARGE"
        elif is_usage_upload:
            error_code = "INVALID_USAGE_BATCH"
        elif invalid_usage_query:
            error_code = "INVALID_USAGE_QUERY"
        elif invalid_model_configuration and not missing_idempotency_key:
            error_code = "INVALID_MODEL_CONFIG"
        elif invalid_command_content and not missing_idempotency_key:
            error_code = "INVALID_COMMAND_CONTENT"
        else:
            error_code = "REQUEST_VALIDATION_ERROR"
        return JSONResponse(
            status_code=413
            if usage_batch_too_large
            else (
                400
                if (
                    missing_idempotency_key
                    or invalid_command_content
                    or invalid_model_configuration
                    or is_usage_upload
                    or invalid_usage_query
                    or invalid_safety_interval
                    or invalid_safety_configuration
                    or invalid_safety_event
                )
                else 422
            ),
            content={
                "error": {
                    "code": error_code,
                    "message": "请求参数不正确",
                    "request_id": request_id,
                }
            },
            headers={"X-Request-ID": request_id},
        )

    @app.get("/health", tags=["system"])
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    app.include_router(api_router)
    return app


app = create_app()


async def _safety_image_cleanup_loop(
    database: Database,
    settings: Settings,
    manager: ConnectionManager,
    storage: SafetyImageStorage,
) -> None:
    while True:
        try:
            async with database.session_factory() as session:
                await SafetyMonitoringService(
                    session,
                    database.safety_monitoring_lock,
                    settings,
                    manager,
                    storage,
                ).cleanup_expired_images()
        except Exception:
            pass
        await asyncio.sleep(settings.safety_image_cleanup_interval_seconds)
