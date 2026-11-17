# ADR 0026: M5 Memory の範囲と完了条件

- ステータス: 採用 (Accepted)
- 日付: 2026-11-17
- 関連: ADR 0024、設計書 06-memory-architecture

## 背景

recl は実 REST / MCP を未提供であり、既存の ReclAdapter は仮の契約を実装している。
M5 の完了条件を明文化し、実接続を前提にせず結線と縮退経路を検証する必要がある。

## 決定

M5 の実装対象を M5-1（環境設定・結線）と M5-2（読み取り縮退の E2E）に限定する。
本番では既定 OFF のままとし、Fake / Mock による検証を実サーバーとの互換性確認とは扱わない。

- `ReclSettings.fromEnv` は既存 JVM モジュールの `System.getenv()` / 注入可能な環境 Map の方式を使う。
- `KIVRA_RECL_ENABLED` は既定 `false`。OFF の場合は他の recl 設定を読まず、provider / HTTP client を構築しない。
- ON の場合は `KIVRA_RECL_BASE_URL`（HTTPS）と `KIVRA_RECL_TOKEN`（空白を含まない bearer token）を必須とする。
- `KIVRA_RECL_READ_TIMEOUT_MS` は既定 2000、`KIVRA_RECL_CIRCUIT_BREAKER_THRESHOLD` は既定 3、
  `KIVRA_RECL_CIRCUIT_BREAKER_COOLDOWN_MS` は既定 30000。全て正の整数とし、不正値は起動時に失敗させる。
- `ReclWiring.fromEnvironment` は ON 時に ReclAdapter を MemoryService で包み、OFF 時は null を返す。
  Application 停止時に MemoryService と所有する adapter を閉じる。
- ReclContextSearch は MemoryService.recall を通し、health 確認を含む読み取り全体に timeout と circuit breaker を適用する。
  DOWN、停止・接続失敗、タイムアウト、遮断中は MEMORY_UNAVAILABLE と空の結果に縮退する。コルーチンのキャンセルは伝播する。
- 現行 Orchestrator に `plan()` はないため、ガードを通過したタスクの受領・admission 直前に検索を 1 箇所追加する。
  既存の設定済み tenant（budgetConfig.tenantId）を検索スコープとし、タスク本文から権限を組み立てない。
  実行前の相関 ID には admission の requestId を使う（実 agent execution ID ではない）。
- bundle は ID・件数・警告のみをログに記録し、内容はログやエージェントへ渡さない。
  OFF 時もタスク処理結果・外部通信は従来と等価であり、縮退警告と bundle メタデータのログのみ追加される。
- 書き込み（writeRunSummary）、WorkingMemory の結線は行わない。
  M5-3 以降の AccessContext の本格的な結線、Context Assembler、書き込みは別マイルストーンとする。
  本段階では既存の最小 AccessContext / ContextBundle.from のみを利用する。

## 完了条件

1. 環境変数によって ReclAdapter + MemoryService を構築でき、MockEngine / Fake で検証できる。
2. provider null / 停止 / DOWN（circuit breaker open）/ timeout の各場合に、Orchestrator の受領処理が正常に admission まで進み、
   MEMORY_UNAVAILABLE の警告ログを出す。正常時は検索結果の bundle メタデータをログに記録する。
3. 既定 OFF でビルドと既存テストを壊さない。Gradle を起動できない環境では未検証の理由を報告し、テストコードを残す。

## 影響

実 REST / MCP 未提供のままでも読み取り縮退を検証できる。実環境の互換性や認可の本格的な結線は後続で確認する。
この ADR は検証範囲を明確にするものであり、本番有効化の根拠にはならない。
