#!/usr/bin/env bash
# 検査: 作業ディレクトリの .kivra/ が 0777、.kivra/task.json が 0644 で、コンテナの uid 10001 から読めること(PR #227 の再発防止)。
# 実機の docker が必要(run-all.sh から呼ぶ。rootless なら DOCKER_HOST を設定して実行する)。
# 使い方: test-taskfile-perms.sh [<workdir>]
#   <workdir> を省くと、一時ディレクトリに .kivra/task.json を作って検査する。作り方は TaskFile.write と同じ
#   (umask 077 の下で作り、ディレクトリは 0777、ファイルは 0644 を明示する。systemd の UMask=0077 を再現する)。
#   <workdir> を渡すと、その下の .kivra/task.json を検査する(実行中の attempt の作業ディレクトリなど)。
# 検査しないこと: Kotlin 側の書き方そのもの(TaskFileTest が見る)。ここは「実 docker で、コンテナから読めるか」を見る。
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"

IMAGE="${KIVRA_AGENT_IMAGE:-kivra-agent-runtime:local}"
tmp=""
cleanup() { [ -n "$tmp" ] && rm -rf "$tmp"; }
trap cleanup EXIT

if [ "${1:-}" != "" ]; then
  work="$1"
  [ -d "$work/.kivra" ] || { echo "  FAIL: $work/.kivra がありません" >&2; exit 1; }
else
  tmp="$(mktemp -d)"
  work="$tmp/work"
  mkdir "$work"
  (
    umask 077
    mkdir "$work/.kivra"
    chmod 0777 "$work/.kivra"
    printf '%s\n' '{"schemaVersion":1,"trust":"UNTRUSTED","title":"perm check"}' > "$work/.kivra/task.json"
    chmod 0644 "$work/.kivra/task.json"
  )
fi

dir_mode="$(stat -c %a "$work/.kivra")"
file_mode="$(stat -c %a "$work/.kivra/task.json" 2>/dev/null || echo missing)"
[ "$dir_mode" = 777 ] && ok ".kivra/ is 0777" || ko ".kivra/ is $dir_mode (want 777)"
[ "$file_mode" = 644 ] && ok "task.json is 0644" || ko "task.json is $file_mode (want 644)"

# コンテナの uid 10001 から読めるか(run-agent.sh と同じ uid、同じ読み取り専用の root ファイルシステム、ネットワーク無し)。
if docker run --rm --pull=never --network none --user 10001:10001 --read-only --tmpfs /tmp:rw,nosuid,size=16m \
  --cap-drop=ALL --security-opt=no-new-privileges --entrypoint sh \
  -v "$work:/work:ro" "$IMAGE" \
  -c 'test -x /work/.kivra && test -r /work/.kivra/task.json && head -c 1 /work/.kivra/task.json >/dev/null' >/dev/null 2>&1; then
  ok "uid 10001 in the container can read .kivra/task.json"
else
  ko "uid 10001 in the container cannot read .kivra/task.json (image $IMAGE)"
fi

summary
