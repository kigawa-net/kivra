# kivra-agent 本体のイメージ(ADR 0010 追補 7 の PR 1)。
# リポジトリのルートをビルドコンテキストにする: docker build -f kivra-agent/Dockerfile .
# 資格情報・秘密はイメージに入れない。クライアント秘密などは実行時に外から渡す。

# ビルドステージ
FROM gradle:jdk21 AS builder

WORKDIR /src

# 依存解決のキャッシュ層: ラッパー・Gradle 設定と全モジュールの build.gradle.kts だけを先にコピーする。
# settings.gradle.kts が全モジュールを include しているため、設定フェーズには全モジュールのビルドスクリプトが要る。
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY kivra-agent/build.gradle.kts kivra-agent/build.gradle.kts
COPY kivra-app/build.gradle.kts kivra-app/build.gradle.kts
COPY kivra-compose-ui/build.gradle.kts kivra-compose-ui/build.gradle.kts
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
COPY kivra-web/build.gradle.kts kivra-web/build.gradle.kts
COPY kotlin-js-store/ kotlin-js-store/

# gradle-wrapper.properties で固定された Gradle を使うため、イメージ同梱の gradle ではなく ./gradlew を使う
RUN chmod +x gradlew && ./gradlew :kivra-agent:dependencies --configuration runtimeClasspath --no-daemon > /dev/null

COPY kivra-agent/ kivra-agent/

# 依存 JAR を個別に展開する installDist を使う(ファット JAR は使わない。Dockerfile の kivra-app と同じ理由)。
RUN ./gradlew :kivra-agent:installDist --no-daemon

# 実行ステージ
FROM eclipse-temurin:21-jre-jammy

# git: PublicGitMirror が公開 GitHub の mirror を取得するために使う。資格情報は持たせない。
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

# UID/GID 10001 は固定する: ADR 0010 追補 7 §4 の Job の securityContext(runAsUser: 10001)と揃える。
RUN groupadd -g 10001 kivra-agent && useradd -u 10001 -g kivra-agent -r -M kivra-agent

WORKDIR /app

COPY --from=builder /src/kivra-agent/build/install/kivra-agent/ ./

USER 10001:10001

ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

# 設定(KIVRA_AGENT_* の環境変数)が無ければ、起動時に設定エラーの終了コード 2 で止まる。
ENTRYPOINT ["/app/bin/kivra-agent"]
