#!/usr/bin/env bash
# kivra-agent をこの PC に配置する(ADR 0010)。ユーザーが自分の端末で sudo して実行する。冪等。
#   sudo bash install.sh --dist <build-dist.sh の出力ディレクトリ> [--skip-images]
# 行うこと: JDK と専用ユーザーの確認、/opt/kivra-agent への配置(root 所有の読み取り専用)、
#   /var/lib/kivra-agent/work(kivra-agent 所有 0700)、/etc/kivra-agent の設定(秘密を含まない)、
#   systemd --user のユニット(/etc/systemd/user。root 所有)、docker イメージのビルド(kivra-agent ユーザーで)。
# 行わないこと: 常駐の開始・有効化(kivra-agentctl start で行う)、client secret の作成・変更・読み取り。
# DESTDIR=<dir> を付けると、テスト用のドライラン(root 確認・chown・systemctl・docker を行わない)。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"

DIST=""
SKIP_IMAGES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dist) DIST="$2"; shift 2 ;;
    --skip-images) SKIP_IMAGES=1; shift ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done
[ -n "$DIST" ] || die "--dist <dir> が必要です(先に build-dist.sh を実行)"
DIST="$(cd "$DIST" && pwd)"

# 1. 前提の確認
if ! is_dry; then
  [ "$(id -u)" -eq 0 ] || die "sudo で実行してください"
  id "$AGENT_USER" >/dev/null 2>&1 || die "専用ユーザー $AGENT_USER がありません(setup-kivra-agent-rootless-docker.sh を先に)"
  groups_of_agent="$(id -nG "$AGENT_USER")"
  for g in sudo docker adm; do
    case " $groups_of_agent " in *" $g "*) die "$AGENT_USER が $g グループに入っています。外してください: gpasswd -d $AGENT_USER $g" ;; esac
  done
  [ -e "/var/lib/systemd/linger/$AGENT_USER" ] || die "linger が無効です: loginctl enable-linger $AGENT_USER"
fi
[ -x "$DIST/app/bin/kivra-agent" ] || die "配布物がありません: $DIST/app/bin/kivra-agent(build-dist.sh を実行)"
for d in network proxy image verify install; do
  [ -d "$DIST/agent-runtime/$d" ] || die "配布物に agent-runtime/$d がありません"
done
JAVA_BIN="$(find_jdk 21)"
JAVA_HOME_DIR="$(dirname "$(dirname "$JAVA_BIN")")"
if ! is_dry; then
  case "$JAVA_HOME_DIR" in
    /home/*|/root/*) die "JDK が $JAVA_HOME_DIR にあります。kivra-agent ユーザーが読めるシステムの JDK(例: /usr/lib/jvm/...)を使ってください" ;;
  esac
fi
DOCKER_BIN="$(readlink -f "$(command -v docker || true)" 2>/dev/null || true)"
[ -x "${DOCKER_BIN:-}" ] || die "docker CLI が見つかりません(ルートレス docker の CLI を導入してください)"
GIT_BIN="$(readlink -f "$(command -v git || true)" 2>/dev/null || true)"
[ -x "${GIT_BIN:-}" ] || die "git が見つかりません"
echo "JDK: $JAVA_HOME_DIR"

AGENT_UID="$(agent_uid)"
DOCKER_HOST_VALUE="unix:///run/user/${AGENT_UID}/docker.sock"
SRC_VERSION="$(cat "$DIST/VERSION" 2>/dev/null || echo unknown)"

# 2. /opt/kivra-agent(root 所有。kivra-agent は読み取りと実行だけ)
echo "== files: $DESTDIR$PREFIX"
mkdir -p "$DESTDIR$PREFIX"
rm -rf "$DESTDIR$PREFIX/.new"
mkdir "$DESTDIR$PREFIX/.new"
cp -a "$DIST/app" "$DESTDIR$PREFIX/.new/app"
cp -a "$DIST/agent-runtime" "$DESTDIR$PREFIX/.new/agent-runtime"
echo "$SRC_VERSION" > "$DESTDIR$PREFIX/.new/VERSION"
rm -rf "$DESTDIR$PREFIX/app" "$DESTDIR$PREFIX/agent-runtime"
mv "$DESTDIR$PREFIX/.new/app" "$DESTDIR$PREFIX/app"
mv "$DESTDIR$PREFIX/.new/agent-runtime" "$DESTDIR$PREFIX/agent-runtime"
mv "$DESTDIR$PREFIX/.new/VERSION" "$DESTDIR$PREFIX/VERSION"
rmdir "$DESTDIR$PREFIX/.new"
own -R root:root "$DESTDIR$PREFIX"
chmod -R a+rX "$DESTDIR$PREFIX"
chmod -R go-w "$DESTDIR$PREFIX"

mkdir -p "$DESTDIR$PREFIX/bin"
ln -sfn "$PREFIX/agent-runtime/install/kivra-agentctl.sh" "$DESTDIR$PREFIX/bin/kivra-agentctl"
mkdir -p "$DESTDIR$BIN_DIR"
ln -sfn "$PREFIX/bin/kivra-agentctl" "$DESTDIR$BIN_DIR/kivra-agentctl"
chmod 0755 "$DESTDIR$PREFIX/agent-runtime/install/kivra-agentctl.sh" "$DESTDIR$PREFIX/agent-runtime/install/preflight.sh" \
  "$DESTDIR$PREFIX/agent-runtime/install/uninstall.sh"

# 3. 作業ディレクトリ(kivra-agent 所有 0700)。STOP ファイルは work 直下(既定)。
echo "== state: $DESTDIR$STATE_DIR"
mkdir -p "$DESTDIR$STATE_DIR"
own root:root "$DESTDIR$STATE_DIR"
chmod 0755 "$DESTDIR$STATE_DIR"
mkdir -p "$DESTDIR$STATE_DIR/work"
own "$AGENT_USER:$AGENT_USER" "$DESTDIR$STATE_DIR/work"
chmod 0700 "$DESTDIR$STATE_DIR/work"

# 4. 設定(秘密を含まない)。既存の値は上書きしない。
# ルートレス docker は起動時に /etc を写す。この実行で /etc/kivra-agent を新しく作ったなら、デーモンはそれを見えない。
etc_new=0
[ -d "$DESTDIR$ETC_DIR" ] || etc_new=1
echo "== config: $DESTDIR$ETC_DIR"
mkdir -p "$DESTDIR$ETC_DIR"
own root:"$AGENT_USER" "$DESTDIR$ETC_DIR"
chmod 0750 "$DESTDIR$ETC_DIR"
if [ ! -e "$DESTDIR$ETC_DIR/agent.env" ]; then
  cat > "$DESTDIR$ETC_DIR/agent.env" <<ENV
# kivra-agent の設定(非秘密)。値の意味は docs/runbook-selfdev.md §6.5・§6.10。秘密は入れない(client secret はファイル)。
KIVRA_AGENT_KIVRA_URL=https://kivra-dev.kigawa.net
KIVRA_AGENT_TOKEN_URL=https://user.kigawa.net/realms/kigawa-net/protocol/openid-connect/token
# この PC の Keycloak client(例: kivra-agent-<device>)。空のままだと起動は拒否される。
KIVRA_AGENT_CLIENT_ID=
KIVRA_AGENT_CLIENT_SECRET_FILE=$ETC_DIR/client-secret
KIVRA_AGENT_WORK_DIR=$STATE_DIR/work
KIVRA_AGENT_STOP_FILE=$STATE_DIR/work/STOP
KIVRA_AGENT_POLL_INTERVAL_MS=5000
KIVRA_AGENT_HEARTBEAT_INTERVAL_MS=30000
# stub(既定): claim した attempt を NEEDS_HUMAN で返すだけ。real にする手順は runbook §6.10 / README を参照。
KIVRA_AGENT_EXECUTOR=stub
# --- real のときだけ読まれる(以下のコメントを外して値を確かめる) ---
#KIVRA_AGENT_DOCKER_BIN=$DOCKER_BIN
#KIVRA_AGENT_DOCKER_HOST=$DOCKER_HOST_VALUE
#KIVRA_AGENT_DOCKER_NETWORK=${KIVRA_AGENT_PREFIX:-kivra-agent}-internal
#KIVRA_AGENT_DOCKER_IMAGE=kivra-agent-runtime:local
#KIVRA_AGENT_DOCKER_PROXY_URL=http://${KIVRA_AGENT_PREFIX:-kivra-agent}-proxy:8080
#KIVRA_AGENT_GIT_BIN=$GIT_BIN
#KIVRA_AGENT_REPO=owner/repo
#KIVRA_AGENT_MODEL=claude-haiku-5-5
#KIVRA_AGENT_MAX_BUDGET_USD=0.50
# 失敗した attempt の出力(stream、task、結果コード)を作業ディレクトリ下の debug/ に残す。既定は無効。内容は秘密を含みうる(runbook §6.12)。
#KIVRA_AGENT_DEBUG_KEEP=1
# 専用のエラーコード(EMPTY_PATCH、BUDGET_EXCEEDED、OVERLOADED)を送る。kivra の対応版をデプロイした後にだけ 1 にする(runbook §6.10d)。
#KIVRA_AGENT_NEW_ERROR_CODES=1
# systemd が JVM の起動スクリプトに使う JDK
JAVA_HOME=$JAVA_HOME_DIR
ENV
  echo "created $ETC_DIR/agent.env"
fi
if [ ! -e "$DESTDIR$ETC_DIR/proxy.env" ]; then
  cat > "$DESTDIR$ETC_DIR/proxy.env" <<'ENV'
# egress proxy の設定(非秘密)。値は Anthropic Console の WIF 設定で発行されたもの。空のままでは proxy は起動しない。
KIVRA_PROXY_WIF_FEDERATION_RULE_ID=
KIVRA_PROXY_WIF_ORGANIZATION_ID=
KIVRA_PROXY_WIF_SERVICE_ACCOUNT_ID=
KIVRA_PROXY_WIF_WORKSPACE_ID=
ENV
  echo "created $ETC_DIR/proxy.env(WIF の ID を記入してください)"
fi
own root:"$AGENT_USER" "$DESTDIR$ETC_DIR/agent.env" "$DESTDIR$ETC_DIR/proxy.env"
chmod 0640 "$DESTDIR$ETC_DIR/agent.env" "$DESTDIR$ETC_DIR/proxy.env"

# client secret: 中身は読まない。モードだけを見る。
if [ -e "$DESTDIR$ETC_DIR/client-secret" ]; then
  mode="$(stat -c %a "$DESTDIR$ETC_DIR/client-secret")"
  if [ $((8#$mode & 077)) -ne 0 ]; then
    echo "警告: $ETC_DIR/client-secret のモードが $mode です。kivra-agent の JVM は、グループ・その他が読めるファイルを拒否します。" >&2
    echo "      次を実行してください: sudo chown $AGENT_USER:$AGENT_USER $ETC_DIR/client-secret && sudo chmod 0600 $ETC_DIR/client-secret" >&2
    echo "      (install-kivra-agent-secret.sh を再実行すると 0640 に戻るので、そのスクリプトも直すこと)" >&2
  else
    echo "client secret のモード: $mode(OK)"
  fi
else
  echo "警告: $ETC_DIR/client-secret がありません。配置してから起動してください。" >&2
fi

# 5. systemd --user のユニット(root 所有。kivra-agent からは書けない)
echo "== units: $DESTDIR$UNIT_DIR"
mkdir -p "$DESTDIR$UNIT_DIR"
for u in $UNITS; do
  install -m 0644 "$DIST/agent-runtime/install/units/$u" "$DESTDIR$UNIT_DIR/$u"
done
if ! is_dry; then
  if run_as_agent systemctl --user daemon-reload 2>/dev/null; then
    echo "daemon-reload(kivra-agent の user manager)済み"
  else
    echo "警告: kivra-agent の user manager に接続できません。user@${AGENT_UID}.service と linger を確認してください。" >&2
  fi
fi

# 6. docker イメージ(kivra-agent のルートレス docker で)。--skip-images で省略可。
if [ "$SKIP_IMAGES" = 0 ] && ! is_dry; then
  echo "== images"
  run_as_agent env DOCKER_HOST="$DOCKER_HOST_VALUE" docker info >/dev/null 2>&1 \
    || die "kivra-agent のルートレス docker に接続できません(${DOCKER_HOST_VALUE})。setup-kivra-agent-rootless-docker.sh を確認"
  run_as_agent env DOCKER_HOST="$DOCKER_HOST_VALUE" docker build -q -t kivra-agent-proxy:local "$PREFIX/agent-runtime/proxy" >/dev/null
  run_as_agent env DOCKER_HOST="$DOCKER_HOST_VALUE" docker build -q -t kivra-agent-runtime:local "$PREFIX/agent-runtime/image" >/dev/null
  echo "built kivra-agent-proxy:local, kivra-agent-runtime:local"

  # デーモンの /etc の写しに /etc/kivra-agent が無いと、secret は空のディレクトリとしてマウントされる。
  # この実行で /etc/kivra-agent を作った場合、または secret がディレクトリに見える場合は docker.service を再起動する。
  need_restart=0
  if [ "$etc_new" = 1 ]; then
    need_restart=1
  elif [ "$(run_as_agent env DOCKER_HOST="$DOCKER_HOST_VALUE" bash -c '. "$1"; secret_view "$2"' _ \
      "$PREFIX/agent-runtime/install/lib.sh" "$ETC_DIR/client-secret")" = dir ]; then
    need_restart=1
  fi
  if [ "$need_restart" = 1 ]; then
    echo "rootless docker を再起動します(kivra-agent の docker.service。デーモンに $ETC_DIR を見えるようにするため。実行中の container は止まります)"
    run_as_agent systemctl --user restart docker.service \
      || die "docker.service の再起動に失敗しました(kivra-agent ユーザーで systemctl --user status docker.service を確認)"
    for _ in $(seq 1 30); do
      run_as_agent env DOCKER_HOST="$DOCKER_HOST_VALUE" docker info >/dev/null 2>&1 && break
      sleep 1
    done
    run_as_agent env DOCKER_HOST="$DOCKER_HOST_VALUE" docker info >/dev/null 2>&1 \
      || die "再起動後も kivra-agent のルートレス docker に接続できません(${DOCKER_HOST_VALUE})"
    echo "rootless docker を再起動しました"
  fi
fi

echo
echo "== 配置が完了しました(常駐は開始していません)。次の順に進めてください:"
echo "  1. $ETC_DIR/proxy.env と $ETC_DIR/agent.env の空の値(WIF の ID、KIVRA_AGENT_CLIENT_ID)を記入する"
echo "  2. client secret のモードを 0600 にする(上の警告が出ていれば)"
echo "  3. sudo kivra-agentctl status で確認する。常駐の開始(kivra-agentctl start)は runbook §6.11 の順序 ④ 以降"
echo "  詳細は docs/runbook-selfdev.md §6.11 を参照(検証 run-all.sh を先に行う)"
