# ADR 0024: M4 の対象を kivra-app の fusha ラインに限定する

- ステータス: 提案(Proposed)
- 日付: 2026-11-15
- 関連: ADR 0008(SelfDev の MVP)、ADR 0011(SelfDev Autopilot)、ADR 0022(プロジェクトのスコープ)

## 背景

M4 では kivra-app に orchestrator を導入する。既存の fusha ラインと selfdev ラインは目的と実行経路が異なり、両方を同じマイルストーンに含めると対象範囲と外部依存が不明瞭になる。また、recl、exkes、ai-scheduler など外部システムとの接続仕様には未確定の部分がある。状態の永続化と機能の有効化方法も、実装前に定める必要がある。

## 決定

1. **M4 の対象は kivra-app の fusha ラインとする。** 対象ディレクトリは `orchestrator/`、`planner/`、`reviewer/`、`agent/`。
2. **selfdev ラインは M4 に含めない。** `AutopilotPlanner` 等の selfdev 関連機能は別マイルストーンとして分離する。
3. **Planner と Reviewer は Direct LLM で実行する。** Model Gateway を介して呼び出し、exkes Agent Runtime には依存しない。
4. **未確定な外部システムとの接続はポートと Fake 実装で扱う。** recl、exkes、ai-scheduler の仕様が確定するまでは、インターフェースを定義し Fake 実装で開発・検証できるようにする。
5. **状態は `PlanStateStore` に保持する。** インメモリ実装と DB 実装を用意し、DB 実装では再起動後に状態を復元できるようにする。
6. **設定は `KIVRA_ORCHESTRATOR_*` 環境変数で制御する。** オーケストレーター機能の既定値は OFF とし、明示的に有効化された場合だけ動作させる。

## 影響

- M4 の設計・実装・検証範囲を fusha ラインに限定でき、selfdev の Autopilot 系機能は独立して計画できる。
- Planner と Reviewer の LLM 呼び出しは Model Gateway に集約され、exkes Agent Runtime の仕様変更や利用可否に左右されない。
- 外部システムの実接続前でも、ポートと Fake 実装を通じてオーケストレーターの振る舞いを開発・検証できる。実接続時には Fake を実装に置き換える必要がある。
- `PlanStateStore` の DB 実装を使うことでプロセス再起動後も状態を復元できる。インメモリ実装では再起動後の復元は行わない。
- 既定 OFF により、環境変数で有効化するまで既存環境でオーケストレーターは動作しない。
