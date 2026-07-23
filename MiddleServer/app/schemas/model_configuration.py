from datetime import datetime
from enum import StrEnum
from typing import Literal, Self
from urllib.parse import urlsplit
from uuid import UUID

from pydantic import Field, field_validator, model_validator

from app.schemas.common import StrictSchema

DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768


class ModelDialect(StrEnum):
    LLAMA_CPP = "llama_cpp"
    STANDARD = "standard"


class ModelSamplingConfiguration(StrictSchema):
    temperature: float = Field(ge=0, le=2, allow_inf_nan=False)
    top_p: float = Field(ge=0, le=1, allow_inf_nan=False)
    top_k: int = Field(ge=0, le=1000)


class ModelConfiguration(StrictSchema):
    schema_version: Literal[1]
    base_url: str = Field(min_length=1, max_length=500)
    model: str = Field(min_length=1, max_length=120)
    dialect: ModelDialect
    context_window_tokens: int = Field(ge=1024, le=2_000_000)
    max_output_tokens: int = Field(ge=64, le=8192)
    sampling: ModelSamplingConfiguration
    reasoning_enabled: Literal[False]

    @field_validator("base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        normalized = value.strip()
        if any(character.isspace() or ord(character) < 32 for character in normalized):
            raise ValueError("model base URL contains whitespace or control characters")
        try:
            parsed = urlsplit(normalized)
            _ = parsed.port
        except ValueError as exc:
            raise ValueError("model base URL is invalid") from exc
        if (
            parsed.scheme.lower() not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("model base URL is invalid")
        return normalized

    @field_validator("model")
    @classmethod
    def normalize_model(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("model name is empty")
        return normalized

    @model_validator(mode="after")
    def validate_token_windows(self) -> Self:
        if self.context_window_tokens < self.max_output_tokens:
            raise ValueError("context window must not be smaller than maximum output")
        return self


class ModelConfigurationUpdateRequest(ModelConfiguration):
    expected_revision: int | None = Field(default=None, ge=1)
    client_request_id: UUID


class ModelConfigurationResponse(StrictSchema):
    configuration: ModelConfiguration
    revision: int = Field(ge=1)
    updated_at: datetime
