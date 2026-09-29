# F4ALL backend: one image, two roles.
#
#   API:     uvicorn app.main:app (the default command)
#   Worker:  celery -A app.worker:celery_app worker
#
# Both need the same code and the same models, and running them from one image
# means the verifier can never be a different version from the API that
# queued its work.

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# OpenCV and MediaPipe load these at import even when headless.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libglib2.0-0 libgl1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Dependencies first, so a code change does not reinstall them.
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY . .

# The pose, face-detection and face-embedding models are fetched at build time
# rather than committed. A build that cannot fetch them fails here, instead of
# producing an image whose verifier silently skips every check.
RUN python -m scripts.fetch_model

# Never run as root. The storage and upload staging paths are the only places
# the process writes.
RUN useradd --create-home --uid 10001 f4all \
    && mkdir -p var/storage var/uploads \
    && chown -R f4all /app/var
USER f4all

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4)"

# --proxy-headers: behind a load balancer, the client's address and scheme
# come from X-Forwarded-*.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
