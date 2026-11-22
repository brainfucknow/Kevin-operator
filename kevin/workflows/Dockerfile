# Worker image for the Kevin workflows: Python, git, Babashka, and the Kevin
# runtime with its kevin on PATH. Build it from the repository root, so the
# image carries the runtime of this checkout:
#   docker build -f kevin/workflows/Dockerfile .
FROM python:3.12-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /tmp/bb.tar.gz \
       https://github.com/babashka/babashka/releases/download/v1.13.229/babashka-1.13.229-linux-amd64.tar.gz \
    && tar -xzf /tmp/bb.tar.gz -C /usr/local/bin \
    && rm /tmp/bb.tar.gz \
    && bb --version

COPY .claude-plugin /opt/kevin/.claude-plugin
COPY kevin /opt/kevin/kevin
RUN ln -s /opt/kevin/kevin/bin/kevin /usr/local/bin/kevin && kevin version

WORKDIR /app
COPY kevin/workflows/pyproject.toml kevin/workflows/uv.lock ./
# The project is installed from src/, so the sources must exist before uv sync.
COPY kevin/workflows/src ./src
RUN pip install uv && uv sync --frozen --no-dev
COPY kevin/workflows .

CMD ["uv", "run", "python", "-m", "kevin_pipeline.worker"]
