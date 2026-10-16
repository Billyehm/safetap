FROM python:3.12-slim

WORKDIR /srv

# Install dependencies first (better layer caching)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# App code and templates
COPY app/ ./app/
COPY templates/ ./templates/

# SQLite lives here; Railway's ephemeral disk is fine for testing.
# For production, switch DATABASE_URL to Postgres and add a volume.
RUN mkdir -p data

ENV MOCK_GATEWAYS=1 \
    PYTHONUNBUFFERED=1

# Railway injects $PORT
CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}
