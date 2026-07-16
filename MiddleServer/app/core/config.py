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
    verification_token_ttl_seconds: int = 600

    dev_verification_enabled: bool = False
    dev_verification_key: str = "local-development-verification-key"

    binding_code_ttl_seconds: int = 600
    binding_failure_window_seconds: int = 600
    binding_failure_limit: int = 5

    @model_validator(mode="after")
    def reject_development_secrets_outside_development(self) -> "Settings":
        if self.app_environment.lower() != "development":
            insecure_values = (
                self.jwt_secret.startswith("local-development-"),
                self.security_secret.startswith("local-development-"),
                self.dev_verification_enabled,
            )
            if any(insecure_values):
                raise ValueError("non-development environments require explicit secrets")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
