"""SafeTap backend configuration. Everything comes from env with sane MVP defaults."""

import os
from pathlib import Path
from urllib.parse import unquote

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SECRET_KEY = os.environ.get("SAFETAP_SECRET_KEY", "dev-secret-change-me")
DATABASE_URL = os.environ.get("SAFETAP_DATABASE_URL", f"sqlite:///{BASE_DIR}/data/safetap.db")
BASE_URL = os.environ.get("SAFETAP_BASE_URL", "http://localhost:8000").rstrip("/")


def ensure_sqlite_database_directory(database_url: str) -> None:
    """Create the parent directory required by file-based SQLite URLs."""
    if not database_url.startswith("sqlite"):
        return

    _, separator, database = database_url.partition(":///")
    if not separator or not database or database == ":memory:" or database.startswith("file:"):
        return

    Path(unquote(database)).parent.mkdir(parents=True, exist_ok=True)


ensure_sqlite_database_directory(DATABASE_URL)

# Gateway behaviour. MOCK_GATEWAYS=1 (default) logs instead of calling providers,
# so the whole flow can be tested without spending a naira.
MOCK_GATEWAYS = os.environ.get("MOCK_GATEWAYS", "1") == "1"

TERMII_API_KEY = os.environ.get("TERMII_API_KEY", "")
TERMII_SENDER_ID = os.environ.get("TERMII_SENDER_ID", "SafeTap")
# Voice and WhatsApp endpoints are adapter-shaped so Termii (or a substitute
# Nigerian gateway) can be plugged in without touching the alert logic.
VOICE_API_URL = os.environ.get("VOICE_API_URL", "")
VOICE_API_KEY = os.environ.get("VOICE_API_KEY", "")
WHATSAPP_API_URL = os.environ.get("WHATSAPP_API_URL", "")
WHATSAPP_API_KEY = os.environ.get("WHATSAPP_API_KEY", "")

FREE_CONTACT_LIMIT = 2          # MVP: up to 2 emergency contacts free
MAX_ALERTS_PER_DAY = 7          # abuse prevention: rate-limit SOS triggers
TRACKING_LINK_HOURS = 12        # tracking links expire after 12 hours
LOCATION_PING_SECONDS = 15      # live location stream cadence during an alert
CHECKIN_GRACE_SECONDS = 10 * 60  # buzz loudly at check-in time, fire SOS 10 min later
