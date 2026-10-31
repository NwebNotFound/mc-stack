FROM maven:3.9-eclipse-temurin-21 AS plugin
WORKDIR /build
COPY plugin/ ./
RUN mvn -q -B package

FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY --from=plugin /build/target/link-1.0.jar /app/link.jar
COPY bot.py stats_card.py changelog.py changelog.json shops.py ah.py features.py gui.py invites.py invite_perms.py buy.py ingest.py markt.py relay.py bid.py parkour.py pvp.py bank.py test_bot.py ./
CMD ["python", "-u", "bot.py"]
