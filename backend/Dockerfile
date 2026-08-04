FROM gradle:8.12-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --home /app app \
    && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 update \
    && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/install/stepstracker-backend/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["/app/bin/stepstracker-backend"]
