# Hugging Face Spaces (Docker SDK) deploy for the SafeTap backend.
# Spaces containers must listen on port 7860.
FROM python:3.12-slim

WORKDIR /srv

COPY backend/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY backend/app/ ./app/
COPY backend/templates/ ./templates/
RUN mkdir -p data

ENV MOCK_GATEWAYS=1 \
    PYTHONUNBUFFERED=1 \
    SAFETAP_SECRET_KEY=41d5ca28eb904b52cd8522fea90012119b56bceca161cb42db5465144fc0f56f

# Set SAFETAP_BASE_URL to your Space URL after creation, e.g.
# https://<username>-safetap-backend.hf.space, then rebuild.
CMD uvicorn app.main:app --host 0.0.0.0 --port 7860
