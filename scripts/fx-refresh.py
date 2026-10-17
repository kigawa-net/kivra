#!/usr/bin/env python3
"""kivra dev の USD→JPY 為替を更新する。

k8s マニフェスト(引数で受ける)の KIVRA_FX_USD_JPY と KIVRA_FX_AS_OF の value を書き換える。
他の行とコメントは変えない。Python 3 標準ライブラリだけを使う。

取得元: Frankfurter(ECB の参照レート。キー不要)
  現在値: https://api.frankfurter.dev/v1/latest?base=USD&symbols=JPY
  52 週:  https://api.frankfurter.dev/v1/<今日-364日>..<今日>?base=USD&symbols=JPY

式(費用を多めに見積もる。kivra 側がさらに余裕 10% と税 10% を上乗せする):
    rate = floor(max(現在値, 52 週最高値)) + 1      (整数、円)
    as-of = 今日の日付(UTC)

終了コード: 0 = 書き換えた / 3 = 変更なし / 1 = 失敗(何も書き換えない)
標準出力は KEY=VALUE の行(GitHub Actions の出力にそのまま使える)。
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import sys
import tempfile
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Callable, Dict, Optional, Tuple

API_BASE = "https://api.frankfurter.dev/v1"
TIMEOUT_SECONDS = 20
MAX_RESPONSE_BYTES = 2_000_000
MIN_RATE = 1.0
MAX_RATE = 1000.0
MAX_STALE_DAYS = 5
MIN_RANGE_POINTS = 100
WINDOW_DAYS = 364
BIG_MOVE_RATIO = 0.10

RATE_KEY = "KIVRA_FX_USD_JPY"
AS_OF_KEY = "KIVRA_FX_AS_OF"

EXIT_CHANGED = 0
EXIT_UNCHANGED = 3
EXIT_FAILED = 1

PR_BODY_FOOTER = "🤖 Generated with [Claude Code](https://claude.com/claude-code)"

Fetcher = Callable[[str], dict]


class FxRefreshError(Exception):
    """A refresh that must not write anything. The message never includes a response body."""


@dataclass
class Result:
    changed: bool
    old_rate: float
    new_rate: int
    as_of: str
    old_as_of: str
    current: float
    current_date: str
    max52: float
    max52_date: str
    warn_big_move: bool
    change_pct: float


def http_get_json(url: str) -> dict:
    req = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "kivra-fx-refresh"},
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
            if resp.status != 200:
                raise FxRefreshError(f"HTTP {resp.status} from rate API")
            body = resp.read(MAX_RESPONSE_BYTES + 1)
    except OSError as e:
        raise FxRefreshError(f"request to rate API failed ({type(e).__name__})") from e
    if len(body) > MAX_RESPONSE_BYTES:
        raise FxRefreshError("rate API response is too large")
    try:
        data = json.loads(body)
    except ValueError as e:
        raise FxRefreshError("rate API response is not JSON") from e
    if not isinstance(data, dict):
        raise FxRefreshError("rate API response is not an object")
    return data


def _parse_date(value: object, what: str) -> date:
    if not isinstance(value, str):
        raise FxRefreshError(f"{what} is not a date string")
    try:
        return date.fromisoformat(value)
    except ValueError as e:
        raise FxRefreshError(f"{what} is not a valid date") from e


def _rate_value(value: object, what: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise FxRefreshError(f"{what} is not a number")
    rate = float(value)
    if not math.isfinite(rate) or not (MIN_RATE <= rate <= MAX_RATE):
        raise FxRefreshError(f"{what} is outside {MIN_RATE:g}..{MAX_RATE:g}")
    return rate


def parse_latest(obj: dict, today: date) -> Tuple[date, float]:
    """Validates the 'latest' response and returns (date, USD/JPY)."""
    if obj.get("base") != "USD":
        raise FxRefreshError("latest response base is not USD")
    latest_date = _parse_date(obj.get("date"), "latest date")
    age = (today - latest_date).days
    if not 0 <= age <= MAX_STALE_DAYS:
        raise FxRefreshError(f"latest rate date is too old or in the future (age {age} days)")
    rates = obj.get("rates")
    if not isinstance(rates, dict) or "JPY" not in rates:
        raise FxRefreshError("latest response has no JPY rate")
    return latest_date, _rate_value(rates["JPY"], "current rate")


def parse_range(obj: dict, today: date) -> Dict[date, float]:
    """Validates the time-series response and returns {date: USD/JPY}."""
    if obj.get("base") != "USD":
        raise FxRefreshError("range response base is not USD")
    rates = obj.get("rates")
    if not isinstance(rates, dict) or len(rates) < MIN_RANGE_POINTS:
        raise FxRefreshError(f"range response has fewer than {MIN_RANGE_POINTS} points")
    series: Dict[date, float] = {}
    for key, day in rates.items():
        d = _parse_date(key, "range date")
        if not isinstance(day, dict) or "JPY" not in day:
            raise FxRefreshError("range response has a day without a JPY rate")
        series[d] = _rate_value(day["JPY"], "range rate")
    if (today - max(series)).days > MAX_STALE_DAYS:
        raise FxRefreshError("range response ends too long before today")
    return series


def compute_rate(current: float, max52: float) -> int:
    """rate = floor(max(current, 52-week max)) + 1. The +1 keeps the estimate on the high side."""
    return math.floor(max(current, max52)) + 1


_NAME_RE = re.compile(r"^\s*-\s*name:\s*(\S+)\s*$")
_VALUE_RE = re.compile(r'^(\s*)value:\s*"([^"]*)"\s*$')


def rewrite_manifest(text: str, rate: int, as_of: str) -> Tuple[str, str, str]:
    """Replaces the quoted value lines of the two FX keys. Returns (new_text, old_rate, old_as_of).

    Each key must appear exactly once, and its value must be a quoted string on the following line.
    Other lines (including line endings) are kept as they are.
    """
    lines = text.splitlines(keepends=True)
    targets = {RATE_KEY: str(rate), AS_OF_KEY: as_of}
    found: Dict[str, str] = {}
    for i, line in enumerate(lines):
        m = _NAME_RE.match(line.rstrip("\r\n"))
        if not m or m.group(1) not in targets:
            continue
        name = m.group(1)
        if name in found:
            raise FxRefreshError(f"{name} appears more than once in the manifest")
        if i + 1 >= len(lines):
            raise FxRefreshError(f"{name} has no value line")
        body = lines[i + 1].rstrip("\r\n")
        vm = _VALUE_RE.match(body)
        if not vm:
            raise FxRefreshError(f"{name} value is not a quoted string on the next line")
        eol = lines[i + 1][len(body):]
        lines[i + 1] = f'{vm.group(1)}value: "{targets[name]}"{eol}'
        found[name] = vm.group(2)
    missing = sorted(set(targets) - set(found))
    if missing:
        raise FxRefreshError(f"manifest has no {', '.join(missing)}")
    return "".join(lines), found[RATE_KEY], found[AS_OF_KEY]


def _write_atomic(path: str, text: str) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    fd, tmp = tempfile.mkstemp(dir=directory, prefix=".fx-refresh-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(text)
        shutil.copymode(path, tmp)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def run(manifest_path: str, fetch: Fetcher, today: date) -> Result:
    """Fetches the rates, validates them, and rewrites the manifest only if the result is valid."""
    latest_date, current = parse_latest(
        fetch(f"{API_BASE}/latest?base=USD&symbols=JPY"), today
    )
    start = today - timedelta(days=WINDOW_DAYS)
    series = parse_range(
        fetch(f"{API_BASE}/{start.isoformat()}..{today.isoformat()}?base=USD&symbols=JPY"), today
    )
    max_date = max(series, key=lambda d: series[d])
    max52 = series[max_date]
    new_rate = compute_rate(current, max52)
    as_of = today.isoformat()

    with open(manifest_path, encoding="utf-8", newline="") as f:
        text = f.read()
    new_text, old_rate_s, old_as_of = rewrite_manifest(text, new_rate, as_of)
    try:
        old_rate = float(old_rate_s)
    except ValueError as e:
        raise FxRefreshError(f"{RATE_KEY} in the manifest is not a number") from e

    change_pct = (new_rate - old_rate) / old_rate * 100 if old_rate > 0 else 0.0
    warn = old_rate > 0 and abs(new_rate - old_rate) / old_rate >= BIG_MOVE_RATIO
    changed = new_text != text
    if changed:
        _write_atomic(manifest_path, new_text)
    return Result(
        changed=changed,
        old_rate=old_rate,
        new_rate=new_rate,
        as_of=as_of,
        old_as_of=old_as_of,
        current=current,
        current_date=latest_date.isoformat(),
        max52=max52,
        max52_date=max_date.isoformat(),
        warn_big_move=warn,
        change_pct=change_pct,
    )


def render_pr_body(r: Result) -> str:
    lines = [
        "kivra dev の為替(`kivra/dev/kivra.yaml`)を自動更新しました。",
        "",
        "| 項目 | 旧 | 新 |",
        "|---|---|---|",
        f"| `KIVRA_FX_USD_JPY` | {r.old_rate:g} | {r.new_rate} |",
        f"| `KIVRA_FX_AS_OF` | {r.old_as_of} | {r.as_of} |",
        "",
        f"- 取得元: Frankfurter(ECB の参照レート): `{API_BASE}/`",
        f"- 現在値: {r.current:g} 円(基準日 {r.current_date})",
        f"- 52 週最高値: {r.max52:g} 円(日付 {r.max52_date})",
        f"- 計算式: `rate = floor(max(現在値, 52 週最高値)) + 1` = floor(max({r.current:g}, {r.max52:g})) + 1 = {r.new_rate}",
        f"- AS_OF: {r.as_of}(UTC、更新日)",
    ]
    if r.warn_big_move:
        lines += [
            "",
            f"**警告**: 新レートが旧レートから {abs(r.change_pct):.1f}% 動きました(10% 以上)。"
            "取得値を確認してからマージしてください。",
        ]
    lines += [
        "",
        "マージで反映されます。マージされないまま 7 日を過ぎると、USD の呼び出しが UNKNOWN_COST で止まります。",
        "",
        PR_BODY_FOOTER,
        "",
    ]
    return "\n".join(lines)


def main(argv: Optional[list] = None) -> int:
    parser = argparse.ArgumentParser(description="Refresh KIVRA_FX_USD_JPY and KIVRA_FX_AS_OF in a manifest.")
    parser.add_argument("manifest", help="path to kivra/dev/kivra.yaml in the manifest repository")
    parser.add_argument("--body-out", help="write the PR body (markdown) to this path")
    args = parser.parse_args(argv)
    try:
        today = datetime.now(timezone.utc).date()
        r = run(args.manifest, http_get_json, today)
        if args.body_out:
            with open(args.body_out, "w", encoding="utf-8") as f:
                f.write(render_pr_body(r))
    except (FxRefreshError, OSError) as e:
        print(f"fx-refresh failed: {e}", file=sys.stderr)
        return EXIT_FAILED
    print(f"CHANGED={'true' if r.changed else 'false'}")
    print(f"OLD_RATE={r.old_rate:g}")
    print(f"NEW_RATE={r.new_rate}")
    print(f"AS_OF={r.as_of}")
    print(f"CURRENT_RATE={r.current:g}")
    print(f"MAX52_RATE={r.max52:g}")
    print(f"WARN_BIG_MOVE={'true' if r.warn_big_move else 'false'}")
    return EXIT_CHANGED if r.changed else EXIT_UNCHANGED


if __name__ == "__main__":
    sys.exit(main())
