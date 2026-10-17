"""Tests for fx-refresh.py. No network: the fetch function is replaced with a fake."""

import contextlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from datetime import date, datetime, timedelta, timezone

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("fx_refresh", os.path.join(_HERE, "fx-refresh.py"))
fx = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = fx  # dataclasses looks the module up by name
_spec.loader.exec_module(fx)

TODAY = date(2026, 10, 18)

MANIFEST = """\
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
        - name: kivra
          env:
            - name: KIVRA_SELFDEV_ENABLED
              value: "true"
            # 為替(USD→JPY)のコメント。値は変えない。
            - name: KIVRA_FX_USD_JPY
              value: "165"
            - name: KIVRA_FX_AS_OF
              value: "2026-10-16"
            - name: KIVRA_BUDGET_MONTHLY_LIMIT_JPY
              value: "10000"
"""


def latest_payload(rate=158.02, day=None, base="USD", today=TODAY):
    day = day if day is not None else today - timedelta(days=2)
    return {"amount": 1.0, "base": base, "date": day.isoformat(), "rates": {"JPY": rate}}


def range_payload(max_rate=163.91, points=255, base="USD", end=TODAY):
    rates = {}
    for i in range(points):
        d = end - timedelta(days=i * 1)
        # every ordinary day stays below max_rate, so the maximum is the one placed below
        rates[d.isoformat()] = {"JPY": max_rate - 1.0 - (i % 7) * 0.5}
    # the maximum is placed on a known date so tests can assert it
    rates[(end - timedelta(days=40)).isoformat()] = {"JPY": max_rate}
    return {"amount": 1.0, "base": base, "start_date": "2025-10-18", "end_date": end.isoformat(), "rates": rates}


def fake_fetch(latest, rng):
    def fetch(url):
        if "/latest?" in url:
            return latest
        return rng

    return fetch


class ComputeRateTest(unittest.TestCase):
    def test_current_above_max(self):
        self.assertEqual(fx.compute_rate(170.0, 160.0), 171)

    def test_max_above_current(self):
        self.assertEqual(fx.compute_rate(158.02, 163.91), 164)

    def test_integer_boundary(self):
        self.assertEqual(fx.compute_rate(163.999, 150.0), 164)
        self.assertEqual(fx.compute_rate(164.0, 100.0), 165)
        self.assertEqual(fx.compute_rate(163.0, 163.0), 164)

    def test_mutation_guard_plus_one_matters(self):
        # If "+ 1" were removed, 163.91 would give 163 instead of 164.
        self.assertNotEqual(fx.compute_rate(158.02, 163.91), 163)


class ValidationTest(unittest.TestCase):
    def test_latest_accepts_valid(self):
        d, rate = fx.parse_latest(latest_payload(), TODAY)
        self.assertEqual(rate, 158.02)
        self.assertEqual(d, TODAY - timedelta(days=2))

    def test_latest_rejects_bad_rates(self):
        for bad in [0, 0.5, 1000.5, 1500, "158", True, None, float("nan"), float("inf")]:
            with self.subTest(rate=repr(bad)):
                with self.assertRaises(fx.FxRefreshError):
                    fx.parse_latest(latest_payload(rate=bad), TODAY)

    def test_boundaries_1_and_1000_accepted(self):
        fx.parse_latest(latest_payload(rate=1.0), TODAY)
        fx.parse_latest(latest_payload(rate=1000), TODAY)

    def test_latest_rejects_stale_and_future_dates(self):
        with self.assertRaises(fx.FxRefreshError):
            fx.parse_latest(latest_payload(day=TODAY - timedelta(days=6)), TODAY)
        with self.assertRaises(fx.FxRefreshError):
            fx.parse_latest(latest_payload(day=TODAY + timedelta(days=1)), TODAY)
        fx.parse_latest(latest_payload(day=TODAY - timedelta(days=5)), TODAY)

    def test_latest_rejects_wrong_base_and_missing_jpy(self):
        with self.assertRaises(fx.FxRefreshError):
            fx.parse_latest(latest_payload(base="EUR"), TODAY)
        with self.assertRaises(fx.FxRefreshError):
            fx.parse_latest({"base": "USD", "date": TODAY.isoformat(), "rates": {"EUR": 1.0}}, TODAY)
        with self.assertRaises(fx.FxRefreshError):
            fx.parse_latest({"base": "USD", "date": "not-a-date", "rates": {"JPY": 158.0}}, TODAY)

    def test_range_rejects_too_few_points(self):
        with self.assertRaises(fx.FxRefreshError):
            fx.parse_range(range_payload(points=99), TODAY)
        fx.parse_range(range_payload(points=100), TODAY)

    def test_range_rejects_bad_value(self):
        obj = range_payload()
        obj["rates"]["2026-01-01"] = {"JPY": 5000}
        with self.assertRaises(fx.FxRefreshError):
            fx.parse_range(obj, TODAY)

    def test_range_rejects_stale_end(self):
        obj = range_payload(end=TODAY - timedelta(days=30))
        with self.assertRaises(fx.FxRefreshError):
            fx.parse_range(obj, TODAY)


class RewriteManifestTest(unittest.TestCase):
    def test_only_two_values_change(self):
        new, old_rate, old_as_of = fx.rewrite_manifest(MANIFEST, 164, "2026-10-18")
        self.assertEqual((old_rate, old_as_of), ("165", "2026-10-16"))
        expected = (
            MANIFEST.replace('value: "165"', 'value: "164"').replace(
                'value: "2026-10-16"', 'value: "2026-10-18"'
            )
        )
        self.assertEqual(new, expected)

    def test_same_values_are_unchanged(self):
        new, _, _ = fx.rewrite_manifest(MANIFEST.replace("165", "164").replace("2026-10-16", "2026-10-18"), 164, "2026-10-18")
        self.assertEqual(
            new,
            MANIFEST.replace("165", "164").replace("2026-10-16", "2026-10-18"),
        )

    def test_crlf_line_endings_are_kept(self):
        crlf = MANIFEST.replace("\n", "\r\n")
        new, _, _ = fx.rewrite_manifest(crlf, 164, "2026-10-18")
        self.assertEqual(new.count("\r\n"), crlf.count("\r\n"))
        self.assertIn('value: "164"\r\n', new)

    def test_missing_key_is_refused(self):
        text = MANIFEST.replace("KIVRA_FX_AS_OF", "KIVRA_OTHER")
        with self.assertRaises(fx.FxRefreshError):
            fx.rewrite_manifest(text, 164, "2026-10-18")

    def test_duplicate_key_is_refused(self):
        text = MANIFEST + MANIFEST
        with self.assertRaises(fx.FxRefreshError):
            fx.rewrite_manifest(text, 164, "2026-10-18")

    def test_unquoted_value_is_refused(self):
        text = MANIFEST.replace('value: "165"', "value: 165")
        with self.assertRaises(fx.FxRefreshError):
            fx.rewrite_manifest(text, 164, "2026-10-18")


class RunTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self._tmp.name, "kivra.yaml")
        with open(self.path, "w", encoding="utf-8", newline="") as f:
            f.write(MANIFEST)

    def tearDown(self):
        self._tmp.cleanup()

    def _read(self):
        with open(self.path, encoding="utf-8", newline="") as f:
            return f.read()

    def test_writes_new_rate_and_date(self):
        r = fx.run(self.path, fake_fetch(latest_payload(158.02), range_payload(163.91)), TODAY)
        self.assertTrue(r.changed)
        self.assertEqual(r.new_rate, 164)
        self.assertEqual(r.as_of, "2026-10-18")
        self.assertIn('value: "164"', self._read())
        self.assertIn('value: "2026-10-18"', self._read())
        self.assertIn("# 為替(USD→JPY)のコメント。値は変えない。", self._read())

    def test_same_values_report_unchanged(self):
        same = MANIFEST.replace('value: "165"', 'value: "164"').replace('value: "2026-10-16"', 'value: "2026-10-18"')
        with open(self.path, "w", encoding="utf-8", newline="") as f:
            f.write(same)
        r = fx.run(self.path, fake_fetch(latest_payload(158.02), range_payload(163.91)), TODAY)
        self.assertFalse(r.changed)
        self.assertEqual(self._read(), same)

    def test_invalid_response_writes_nothing(self):
        for latest, rng in [
            (latest_payload(rate=0), range_payload()),
            (latest_payload(), range_payload(points=10)),
            (latest_payload(), range_payload(base="EUR")),
        ]:
            with self.subTest():
                with self.assertRaises(fx.FxRefreshError):
                    fx.run(self.path, fake_fetch(latest, rng), TODAY)
                self.assertEqual(self._read(), MANIFEST)

    def test_big_move_warns(self):
        # 165 -> 148 is a drop of about 10.3%
        r = fx.run(self.path, fake_fetch(latest_payload(120.0), range_payload(147.0)), TODAY)
        self.assertEqual(r.new_rate, 148)
        self.assertTrue(r.warn_big_move)
        self.assertAlmostEqual(r.change_pct, (148 - 165) / 165 * 100)

    def test_small_move_does_not_warn(self):
        r = fx.run(self.path, fake_fetch(latest_payload(158.02), range_payload(163.91)), TODAY)
        self.assertFalse(r.warn_big_move)

    def test_exact_ten_percent_warns(self):
        with open(self.path, "w", encoding="utf-8", newline="") as f:
            f.write(MANIFEST.replace('value: "165"', 'value: "160"'))
        r = fx.run(self.path, fake_fetch(latest_payload(158.02), range_payload(175.0)), TODAY)
        self.assertEqual(r.new_rate, 176)
        self.assertTrue(r.warn_big_move)

    def test_body_has_formula_and_warning(self):
        r = fx.run(self.path, fake_fetch(latest_payload(120.0), range_payload(147.0)), TODAY)
        body = fx.render_pr_body(r)
        self.assertIn("floor(max(120, 147)) + 1 = 148", body)
        self.assertIn("警告", body)
        self.assertIn("7 日", body)
        self.assertTrue(body.rstrip().endswith("🤖 Generated with [Claude Code](https://claude.com/claude-code)"))

    def test_main_exit_codes(self):
        # main() uses the real clock, so the fake payloads are built from the real UTC today
        real_today = datetime.now(timezone.utc).date()
        original = fx.http_get_json
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                fx.http_get_json = fake_fetch(
                    latest_payload(158.02, today=real_today), range_payload(163.91, end=real_today)
                )
                self.assertEqual(fx.main([self.path]), fx.EXIT_CHANGED)
                self.assertEqual(fx.main([self.path]), fx.EXIT_UNCHANGED)
                fx.http_get_json = fake_fetch(
                    latest_payload(rate=0, today=real_today), range_payload(end=real_today)
                )
                self.assertEqual(fx.main([self.path]), fx.EXIT_FAILED)
        finally:
            fx.http_get_json = original


if __name__ == "__main__":
    unittest.main()
