import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from starlette.middleware.base import RequestResponseEndpoint

from app.api.v1.router import api_router
from app.core.config import Settings, get_settings
from app.core.database import Database
from app.core.errors import ApiError
from app.websocket.manager import ConnectionManager


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or get_settings()
    database = Database(resolved_settings)

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        if resolved_settings.auto_create_schema:
            await database.create_schema()
        yield
        await database.dispose()

    app = FastAPI(
        title=resolved_settings.app_name,
        version="0.1.0",
        lifespan=lifespan,
        description=("银龄助手轻量中台。手机号首版按中国大陆规则规范化；业务部署必须使用 HTTPS。"),
    )
    app.state.settings = resolved_settings
    app.state.database = database
    app.state.connection_manager = ConnectionManager()

    @app.middleware("http")
    async def request_id_middleware(
        request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        if request.url.path == "/api/v1/devices/me/family-contacts":
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.exception_handler(ApiError)
    async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
        request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "error": {
                    "code": exc.code,
                    "message": exc.message,
                    "request_id": request_id,
                }
            },
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
        error_code = (
            "INVALID_COMMAND_CONTENT"
            if invalid_command_content and not missing_idempotency_key
            else "REQUEST_VALIDATION_ERROR"
        )
        return JSONResponse(
            status_code=400 if missing_idempotency_key or invalid_command_content else 422,
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
