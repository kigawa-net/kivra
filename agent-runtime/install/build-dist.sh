#!/usr/bin/env bash
# kivra-agent の配布物を作る。一般ユーザーで実行する(sudo は不要。Gradle のキャッシュは実行者のものを使う)。
#   bash build-dist.sh [--ref <git ref>] [--out <dir>]
# 既定: ref=origin/main(作業ツリーではなく git archive の内容でビルドする)、out=$HOME/.cache/kivra-agent-dist
# 出力: <out>/app(./gradlew :kivra-agent:installDist の結果)、<out>/agent-runtime(archive の agent-runtime 一式)、<out>/VERSION
# 次の段階: sudo bash <out>/agent-runtime/install/install.sh --dist <out>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"

REF="origin/main"
OUT="${HOME}/.cache/kivra-agent-dist"
while [ $# -gt 0 ]; do
  case "$1" in
    --ref) REF="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

repo="$(git -C "$here" rev-parse --show-toplevel)"
sha="$(git -C "$repo" rev-parse --verify --short=12 "$REF^{commit}")" || die "ref が見つかりません: $REF(git fetch origin を先に)"
find_jdk 21 >/dev/null

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git -C "$repo" archive --format=tar "$sha" | tar -x -C "$tmp"

echo "== build kivra-agent ($sha)"
(cd "$tmp" && ./gradlew --console=plain :kivra-agent:installDist)
[ -x "$tmp/kivra-agent/build/install/kivra-agent/bin/kivra-agent" ] || die "installDist の結果に bin/kivra-agent がありません"

rm -rf "$OUT"
mkdir -p "$OUT"
cp -a "$tmp/kivra-agent/build/install/kivra-agent" "$OUT/app"
cp -a "$tmp/agent-runtime" "$OUT/agent-runtime"
echo "$sha" > "$OUT/VERSION"
echo "built: $OUT (version $sha)"
echo "next: sudo bash $OUT/agent-runtime/install/install.sh --dist $OUT"
