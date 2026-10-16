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
    PYTHONUNBUFFERED=1
# Set these in the Space's Settings -> Secrets (never bake secrets into the image):
#   SAFETAP_SECRET_KEY  (random 32-byte hex)
#   SAFETAP_BASE_URL    (your Space URL, e.g. https://<user>-safetap-backend.hf.space)
CMD uvicorn app.main:app --host 0.0.0.0 --port 7860
