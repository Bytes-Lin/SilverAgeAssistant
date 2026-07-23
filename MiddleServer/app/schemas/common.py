from pydantic import BaseModel, ConfigDict


class StrictSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ErrorBody(StrictSchema):
    code: str
    message: str
    request_id: str
    details: dict[str, object] | None = None


class ErrorResponse(StrictSchema):
    error: ErrorBody
