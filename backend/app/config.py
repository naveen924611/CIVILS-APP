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

    # Where generated files live (audio, voices, backups). Keep it next to the database.
    data_dir: str = "./data"

    # Push (FCM)
    fcm_service_account_file: str = ""

    # --- LLM gateway (M2) ---
    gemini_api_key: str = ""
    groq_api_key: str = ""
    # Ordered fallbacks, "provider:model" separated by commas. Model names checked 2026-09-20 (docs/decisions.md).
    llm_chain: str = "gemini:gemini-3.5-flash-lite,gemini:gemini-3.8-flash,groq:openai/gpt-oss-20b"
    # Own daily request budget per provider (the real free limits are shown in Google AI Studio / Groq console).
    gemini_daily_requests: int = 400
    groq_daily_requests: int = 1000
    # Degrade steps as the day's share of budget used grows (section 9 of the spec)
    degrade_skip_mcq_at: float = 0.60
    degrade_shorten_at: float = 0.75
    degrade_defer_at: float = 0.90
    paid_usage_enabled: bool = False  # there is no paid code path; kept as a visible safety switch

    # --- News and briefs (M2) ---
    timezone: str = "Asia/Kolkata"
    feeds_file: str = "../data/feeds.yaml"
    news_window_hours: int = 30
    news_max_articles_per_run: int = 25
    brief_max_items: int = 12
    brief_lead_minutes: int = 30
    scheduler_enabled: bool = True

    # --- Retrieval, files and jobs (M3+) ---
    embed_model: str = "gemini-embedding-2"
    embed_fallback_model: str = "gemini-embedding-001"
    embed_dims: int = 768
    max_upload_mb: int = 60
    job_retry_minutes: int = 10
    job_max_attempts: int = 4

    # --- Audio (M2) ---
    piper_voice: str = "en_GB-alan-medium"
    piper_bin: str = "piper"
    ffmpeg_bin: str = "ffmpeg"


@lru_cache
def get_settings() -> Settings:
    return Settings()
