from functools import lru_cache

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SILVERAGE_", extra="ignore")

    app_name: str = "Silver Age Assistant Middle Server"
    app_environment: str = "development"
    database_url: str = "sqlite+aiosqlite:///./silverage.db"
    auto_create_schema: bool = False

    jwt_secret: str = "local-development-jwt-secret-change-before-deployment"
    security_secret: str = "local-development-security-secret-change-before-deployment"
    access_token_ttl_seconds: int = 1800
    refresh_token_ttl_seconds: int = 2_592_000
    binding_code_ttl_seconds: int = 600
    binding_failure_window_seconds: int = 600
    binding_failure_limit: int = 5
    device_credential_ttl_seconds: int = 31_536_000
    command_default_timezone: str = "Asia/Shanghai"
    command_client_clock_skew_seconds: int = 300
    command_per_minute_limit: int = 10
    command_per_day_limit: int = 200

    @model_validator(mode="after")
    def reject_development_secrets_outside_development(self) -> "Settings":
        if self.app_environment.lower() != "development":
            insecure_values = (
                self.jwt_secret.startswith("local-development-"),
                self.security_secret.startswith("local-development-"),
            )
            if any(insecure_values):
                raise ValueError("non-development environments require explicit secrets")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
