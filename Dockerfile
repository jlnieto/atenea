# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./

RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY src src

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs npm \
    && npm install -g @openai/codex@0.116.0 \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -r -u 1001 -g root appuser

COPY --from=build /workspace/target/atenea-0.0.1-SNAPSHOT.jar app.jar

USER appuser

EXPOSE 8081

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
