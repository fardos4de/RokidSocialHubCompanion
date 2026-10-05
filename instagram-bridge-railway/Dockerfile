FROM python:3.12-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg git \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --create-home --uid 10001 bridge \
 && mkdir -p /data && chown bridge:bridge /data

WORKDIR /app
RUN git clone --depth 1 https://github.com/beyondlevi/lumen-instagram.git /tmp/lumen \
 && cp -r /tmp/lumen/bridge/* /app/ \
 && rm -rf /tmp/lumen \
 && pip install --no-cache-dir .

ENV BRIDGE_HOST=0.0.0.0 \
    BRIDGE_PORT=8787 \
    IG_SESSION_FILE=/data/session.json

EXPOSE 8787
USER bridge
CMD ["python", "-m", "lumen_ig_bridge"]
