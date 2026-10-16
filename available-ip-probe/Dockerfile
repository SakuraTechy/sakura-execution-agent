FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends arp-scan iproute2 iputils-ping \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server.py /app/server.py

ENV PYTHONDONTWRITEBYTECODE=1
EXPOSE 19092
ENTRYPOINT ["python", "/app/server.py"]
