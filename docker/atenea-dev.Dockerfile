FROM eclipse-temurin:21-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends git openssh-client python3 postgresql-client-16 \
    && rm -rf /var/lib/apt/lists/*

RUN git config --system --add safe.directory '*'
