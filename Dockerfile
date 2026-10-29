# ビルドステージ
FROM gradle:jdk21 AS builder

WORKDIR /src

# 依存解決のキャッシュ層: ラッパー・Gradle 設定と全モジュールの build.gradle.kts だけを先にコピーする。
# ソースを変えても、ビルドスクリプトが変わらない限りこの層と依存解決はキャッシュされる。
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY kivra-agent/build.gradle.kts kivra-agent/build.gradle.kts
COPY kivra-app/build.gradle.kts kivra-app/build.gradle.kts
COPY kivra-core/build.gradle.kts kivra-core/build.gradle.kts
COPY kivra-developer/build.gradle.kts kivra-developer/build.gradle.kts
COPY kivra-exkes/build.gradle.kts kivra-exkes/build.gradle.kts
COPY kivra-fusha-poller/build.gradle.kts kivra-fusha-poller/build.gradle.kts
COPY kivra-fusha-task/build.gradle.kts kivra-fusha-task/build.gradle.kts
COPY kivra-github/build.gradle.kts kivra-github/build.gradle.kts
COPY kivra-memory/build.gradle.kts kivra-memory/build.gradle.kts
COPY kivra-model-gateway/build.gradle.kts kivra-model-gateway/build.gradle.kts
COPY kivra-observability/build.gradle.kts kivra-observability/build.gradle.kts
COPY kivra-policy/build.gradle.kts kivra-policy/build.gradle.kts
COPY kivra-scheduler/build.gradle.kts kivra-scheduler/build.gradle.kts
COPY kivra-ui-api/build.gradle.kts kivra-ui-api/build.gradle.kts
COPY kivra-compose-ui/build.gradle.kts kivra-compose-ui/build.gradle.kts
COPY kivra-web/build.gradle.kts kivra-web/build.gradle.kts

# gradle-wrapper.properties で固定された Gradle を使うため、イメージ同梱の gradle ではなく ./gradlew を使う
RUN chmod +x gradlew && ./gradlew :kivra-app:dependencies --configuration runtimeClasspath --no-daemon > /dev/null

COPY kivra-agent/ kivra-agent/
COPY kivra-app/ kivra-app/
COPY kivra-core/ kivra-core/
COPY kivra-developer/ kivra-developer/
COPY kivra-exkes/ kivra-exkes/
COPY kivra-fusha-poller/ kivra-fusha-poller/
COPY kivra-fusha-task/ kivra-fusha-task/
COPY kivra-github/ kivra-github/
COPY kivra-memory/ kivra-memory/
COPY kivra-model-gateway/ kivra-model-gateway/
COPY kivra-observability/ kivra-observability/
COPY kivra-policy/ kivra-policy/
COPY kivra-scheduler/ kivra-scheduler/
COPY kivra-ui-api/ kivra-ui-api/
COPY kivra-compose-ui/ kivra-compose-ui/
COPY kivra-web/ kivra-web/

# shadowJar(ファット JAR)は Flyway のクラスパス走査と相性が悪く、db/migration 配下の SQL を認識できず
# 全マイグレーションが適用されない不具合があるため使わない。依存 JAR を個別に展開する installDist を使う。
RUN ./gradlew :kivra-app:installDist --no-daemon

# 実行ステージ
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# UID/GID 1000 は固定する: kigawa-net-k8s の Deployment が runAsUser: 1000 を指定している
RUN groupadd -g 1000 kivra && useradd -u 1000 -g kivra -r kivra

WORKDIR /app

COPY --from=builder /src/kivra-app/build/install/kivra-app/ ./

USER kivra

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseG1GC -XX:+UseContainerSupport"

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:8080/health || exit 1

ENTRYPOINT ["/app/bin/kivra-app"]
