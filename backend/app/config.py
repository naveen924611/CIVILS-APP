from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_env: str = "dev"
    database_url: str = "sqlite:///./data/civils.db"

    # Single-user auth
    jwt_secret: str = "change-me"
    owner_username: str = "naveen"
    owner_password_hash: str = ""
    access_token_minutes: int = 30
    refresh_token_days: int = 60

    # Login rate limit: max failed attempts per IP per window
    login_max_failures: int = 5
    login_window_seconds: int = 900

    # Push (FCM)
    fcm_service_account_file: str = ""

    # Budget guard (used from M2)
    paid_usage_enabled: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
