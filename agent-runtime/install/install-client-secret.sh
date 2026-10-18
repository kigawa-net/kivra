#!/usr/bin/env bash
# client secret を /etc/kivra-agent/client-secret に配置する(runbook §6.14)。冪等。
# 取得は通常ユーザーが行い、配置だけを root が行う。秘密は sudo の引数や環境変数に載せない(標準入力のパイプだけで渡す)。
#   bws secret get <id> | jq -j .value | sudo bash install-client-secret.sh
#   <値をパイプで渡す> | bash install-client-secret.sh --dry-run   # テスト: DESTDIR の一時ディレクトリへ配置し、root・chown・docker を行わない
# 行うこと: /etc/kivra-agent(root:kivra-agent 0750)を作る/直す。client-secret を kivra-agent:kivra-agent 0600 で
#   一時ファイルから mv により原子的に置く(失敗時は既存の値を変えない)。配置後、rootless docker のデーモンから
#   secret がファイルとして見えるかを確かめ、見えなければ kivra-agent の docker.service を再起動する(install.sh と同じ条件)。
# 表示するもの: モード、所有者、バイト数、デーモンからの見え方だけ。値と長さのほかは表示しない。
# 行わないこと: 値の取得(bws)、Keycloak での再生成、kivra-agent の再起動、旧 secret の失効。runbook §6.14 を参照。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"

DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done
if [ "$DRY" = 1 ] && [ -z "$DESTDIR" ]; then
  DESTDIR="$(mktemp -d)"
  echo "dry-run: DESTDIR=$DESTDIR(テスト後に消してください)"
fi

if ! is_dry; then
  [ "$(id -u)" -eq 0 ] || die "sudo で実行してください(例: bws secret get <id> | jq -j .value | sudo bash $0)"
  id "$AGENT_USER" >/dev/null 2>&1 || die "専用ユーザー $AGENT_USER がありません(setup-kivra-agent-rootless-docker.sh を先に)"
fi
[ ! -t 0 ] || die "値は標準入力からパイプで渡してください(例: bws secret get <id> | jq -j .value | sudo bash $0)"

dir="$DESTDIR$ETC_DIR"
secret="$dir/client-secret"

echo "== config: $dir"
mkdir -p "$dir"
own root:"$AGENT_USER" "$dir"
chmod 0750 "$dir"

# 一時ファイルは同じディレクトリに作る(mv を原子的な rename にするため)。作成時点は root だけが読める。
tmp="$(umask 077; mktemp "$dir/.client-secret.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
cat > "$tmp"

size="$(stat -c %s "$tmp")"
# 空白だけの値、jq が値を取れなかったときの "null"、は拒否する(既存の値は変えない)。値そのものは表示しない。
stripped="$(tr -d ' \t\r\n' < "$tmp")"
if [ -z "$stripped" ]; then
  die "値が空です(bws と jq の段階を確認してください。既存の client secret は変更していません)"
fi
if [ "$stripped" = "null" ]; then
  die "値が null です(jq の .value の指定を確認してください。既存の client secret は変更していません)"
fi
unset stripped

own "$AGENT_USER:$AGENT_USER" "$tmp"
chmod 0600 "$tmp"
mv -f "$tmp" "$secret"
trap - EXIT

mode="$(stat -c %a "$secret")"
owner="$(stat -c %U:%G "$secret")"
echo "配置しました: $secret(モード $mode、所有者 $owner、$size バイト)"
if [ "$mode" != 600 ]; then
  die "モードが 600 ではありません($mode)。配置を確認してください"
fi
if ! is_dry && [ "$owner" != "$AGENT_USER:$AGENT_USER" ]; then
  die "所有者が $AGENT_USER:$AGENT_USER ではありません($owner)"
fi

if is_dry; then
  echo "dry-run: デーモンの確認と再起動は行いません"
  exit 0
fi

# デーモンから見えるか(中身は読まない)。見えなければ kivra-agent の docker.service を再起動する(install.sh と同じ条件)。
uid="$(agent_uid)"
docker_host="unix:///run/user/${uid}/docker.sock"
lib_installed="$PREFIX/agent-runtime/install/lib.sh"
if [ ! -f "$lib_installed" ]; then
  echo "注意: $lib_installed がありません。install.sh を先に実行してください。デーモンの確認を省きました。" >&2
  exit 0
fi
view="$(run_as_agent env DOCKER_HOST="$docker_host" bash -c '. "$1"; secret_view "$2"' _ \
  "$lib_installed" "$ETC_DIR/client-secret")"
case "$view" in
  file)
    echo "デーモンからファイルとして見えます(OK)"
    ;;
  dir)
    echo "デーモンからはディレクトリに見えます。kivra-agent の docker.service を再起動します(実行中の container は止まります)"
    run_as_agent systemctl --user restart docker.service \
      || die "docker.service の再起動に失敗しました(kivra-agent ユーザーで systemctl --user status docker.service を確認)"
    for _ in $(seq 1 30); do
      run_as_agent env DOCKER_HOST="$docker_host" docker info >/dev/null 2>&1 && break
      sleep 1
    done
    again="$(run_as_agent env DOCKER_HOST="$docker_host" bash -c '. "$1"; secret_view "$2"' _ \
      "$lib_installed" "$ETC_DIR/client-secret")"
    [ "$again" = file ] || die "再起動後もデーモンから secret がファイルに見えません(結果: $again)"
    echo "rootless docker を再起動し、デーモンからファイルとして見えることを確認しました"
    ;;
  *)
    echo "デーモンから secret を確認できませんでした(結果: $view)。イメージ kivra-agent-runtime:local の有無と、kivra-agent の docker を確認してください。" >&2
    echo "  見えない場合は: sudo runuser -u $AGENT_USER -- env XDG_RUNTIME_DIR=/run/user/$uid systemctl --user restart docker.service" >&2
    exit 1
    ;;
esac
echo "次は runbook §6.14 の手順 ④(kivra-agent.service と kivra-agent-proxy.service の再起動)へ進んでください"
