# SPDX-License-Identifier: AGPL-3.0-or-later
# Sync + MCP server. Builds only the pure-JVM modules (no Android SDK needed).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY core core
COPY protocol protocol
COPY server server
RUN ./gradlew --no-daemon -Pquest.jvmOnly=true :server:installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /data quest && mkdir -p /data && chown quest /data
COPY --from=build /src/server/build/install/quest-server /opt/quest-server
USER quest
ENV DATA_DIR=/data \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
VOLUME ["/data"]
ENTRYPOINT ["/opt/quest-server/bin/quest-server"]
