#!/usr/bin/env bash
# kivra-agent の運用。root で実行する: sudo kivra-agentctl <command>
#   status   状態を表示する(3 ユニット、STOP の有無、docker への接続、secret のデーモンからの見え方、直近のログ 10 行)。秘密は出さない
#   start    ユニットを有効化して開始する(再起動後も上がる。linger が前提)
#   disable  ユニットを止めて無効化する(proxy と実行中の work 用容器も消える)
#   stop     キルスイッチ: STOP ファイルを作る。claim が止まり、実行中の attempt は CANCELLED で報告される。プロセスは残る
#   resume   STOP ファイルを消す
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
. "$here/lib.sh"
[ "$(id -u)" -eq 0 ] || die "sudo で実行してください"

WORK_DIR="$(sed -n 's/^KIVRA_AGENT_WORK_DIR=//p' "$ETC_DIR/agent.env" 2>/dev/null | tail -1 || true)"
WORK_DIR="${WORK_DIR:-$STATE_DIR/work}"
STOP_FILE="$(sed -n 's/^KIVRA_AGENT_STOP_FILE=//p' "$ETC_DIR/agent.env" 2>/dev/null | tail -1 || true)"
STOP_FILE="${STOP_FILE:-$WORK_DIR/STOP}"
UID_AGENT="$(agent_uid)"
DOCKER_HOST_VALUE="unix:///run/user/${UID_AGENT}/docker.sock"

cmd="${1:-status}"
case "$cmd" in
  status)
    echo "version: $(cat "$PREFIX/VERSION" 2>/dev/null || echo unknown)"
    for u in $UNITS; do
      active="$(run_as_agent systemctl --user is-active "$u" 2>/dev/null || true)"
      enabled="$(run_as_agent systemctl --user is-enabled "$u" 2>/dev/null || true)"
      printf '%-30s active=%s enabled=%s\n' "$u" "${active:-unknown}" "${enabled:-unknown}"
    done
    if [ -e "$STOP_FILE" ]; then echo "STOP: あり(claim 停止中。解除は kivra-agentctl resume)"; else echo "STOP: なし"; fi
    if run_as_agent env DOCKER_HOST="$DOCKER_HOST_VALUE" docker info >/dev/null 2>&1; then
      echo "docker: 接続 OK(ルートレス)"
      case "$(run_as_agent env DOCKER_HOST="$DOCKER_HOST_VALUE" bash -c '. "$1"; secret_view "$2"' _ \
          "$PREFIX/agent-runtime/install/lib.sh" "$ETC_DIR/client-secret")" in
        file) echo "client secret: デーモンからファイルとして見える(OK)" ;;
        dir) echo "client secret: デーモンからはディレクトリに見える。rootless docker を再起動してください(runbook §6.11 c の補足)" ;;
        nohost) echo "client secret: ホストに無い、またはファイルではない" ;;
        *) echo "client secret: デーモンから確認できません(イメージ kivra-agent-runtime:local の有無を確認)" ;;
      esac
    else
      echo "docker: 接続できません"
    fi
    # debug 保存(runbook §6.12)。件数と最古の日時だけを出す。中身は読まず、名前も出さない。
    debug_keep="$(sed -n 's/^KIVRA_AGENT_DEBUG_KEEP=//p' "$ETC_DIR/agent.env" 2>/dev/null | tail -1 || true)"
    case "$debug_keep" in
      1) echo "debug 保存(KIVRA_AGENT_DEBUG_KEEP): 有効" ;;
      ""|0) echo "debug 保存(KIVRA_AGENT_DEBUG_KEEP): 無効" ;;
      *) echo "debug 保存(KIVRA_AGENT_DEBUG_KEEP): 不正な値(kivra-agent は起動を拒否します。agent.env を直してください)" ;;
    esac
    DEBUG_DIR="$WORK_DIR/debug"
    if [ -d "$DEBUG_DIR" ]; then
      debug_count="$(find "$DEBUG_DIR" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l)"
      debug_oldest="$(find "$DEBUG_DIR" -mindepth 1 -maxdepth 1 -printf '%TY-%Tm-%Td %TH:%TM\n' 2>/dev/null | sort | head -n 1 || true)"
      echo "debug 保存ディレクトリ: ${debug_count} 件(最も古い更新: ${debug_oldest:-なし})"
    else
      echo "debug 保存ディレクトリ: なし"
    fi
    if [ "$debug_keep" = 1 ]; then
      echo "注意: debug 保存は有効です。保存分はタスク文とリポジトリ内容を含むので共有しないこと"
    fi
    echo "--- 直近のログ(kivra-agent.service、10 行)"
    run_as_agent journalctl --user -u kivra-agent.service -n 10 --no-pager -o short-iso 2>/dev/null || echo "(ログを読めません)"
    ;;
  start)
    run_as_agent systemctl --user enable --now $UNITS
    echo "started. kivra-agentctl status で確認してください"
    ;;
  disable)
    run_as_agent systemctl --user disable --now kivra-agent.service kivra-agent-proxy.service kivra-agent-network.service
    echo "disabled. 実行中の work 用容器は proxy の停止で消えます"
    ;;
  stop)
    [ -d "$WORK_DIR" ] || die "作業ディレクトリがありません"
    (umask 077; : > "$STOP_FILE")
    echo "STOP を作成しました(claim を止めました)。実行中の attempt は CANCELLED で報告されます。解除は kivra-agentctl resume"
    ;;
  resume)
    rm -f "$STOP_FILE"
    echo "STOP を消しました(claim を再開します)"
    ;;
  *)
    die "使い方: kivra-agentctl {status|start|disable|stop|resume}"
    ;;
esac
