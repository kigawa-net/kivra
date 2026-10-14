# ADR 0002: 永続化の正本は当面 MariaDB とし、Fortis は将来の選択肢とする

- ステータス: 採用(Accepted)
- 日付: 2026-10-15
- 関連: Issue #109(予算、Fortis 依存は必須にしない)、#64 / #65 / #68 / #69(Fortis-first 移行の設計)、#72 / #73(判断待ちの論点)

## 背景

#64 / #65 / #68 / #69 は、Kivra の永続状態を Fortis(強整合の分散 KV)に統一し、MariaDB / Exposed / Flywayを退役させる
移行計画を提案していた。一方、実装は MariaDB 上で進んでいる(Flyway の V1〜V10、予算の永続ストア `DbBudgetStore` は V10)。
#109 は「ローカル起動の MVP で Fortis への依存は必須にしない。耐久的な admission は、単一の正本のトランザクショナルな
バックエンドを使う」とし、Fortis 移行の判断を保留にしていた。

## 決定

1. 当面、全ての永続化の正本は MariaDB とする(Flyway で管理し、Exposed で扱う)。#109 の予算ストア(V10)も含む。
2. Fortis-first(#64 / #65 / #68 / #69)は **設計のみ** を残し、実装には着手しない。Issue は閉じず「設計・保留」として残す。
3. MariaDB から Fortis へ移行する場合の最低条件は #68 に従う(Cutover、Writer Epoch、Freeze、検証、復旧)。

## 根拠

- 耐久的な admission(予算、Lease、Operation Journal)は単一の正本のトランザクションで扱う必要があり、MariaDB はそれを満たす。
- 実装・運用・バックアップ・リストア試験の実績が全て MariaDB にある(`docs/runbook-db-backup-restore.md` を参照)。
- dev 環境は単一の小さなクラスタであり、分散 KV の HA を今すぐ必要としない。
- 移行中に二つの正本を並存させると、整合性の証明と切り戻しの手順が大きな負担になる。

## 結果

- 影響: 新しい永続機能は MariaDB のテーブルとして追加する。Fortis 向けのアダプタは作らない。
- やらないこと: Fortis への移行作業、Fortis 向けの KV 設計の実装、MariaDB と Fortis の二重書き込み。

## 見直しの条件

次のいずれかが成り立ったら、この ADR を見直す。

- (a) 複数拠点の HA が必要になり(Karmada HA 計画)、MariaDB の単一ライターでは足りない。
- (b) MariaDB のスループットまたは遅延が、実測で上限に達する。
- (c) Fortis が実装され、運用実績とリストア手順がある。

## 実装状況

- 済: MariaDB への永続化(Flyway V1〜V10)。予算の永続ストア `DbBudgetStore`(V10、`BudgetPort` の実装)は実装と契約試験がある。
- 未確認: `DbBudgetStore` が本番の起動経路に配線されているか。今回の調査では、クラス定義とテスト以外の生成箇所を確認できなかった。
- 未実装(設計のみ): Fortis-first 移行(#64 / #65 / #68 / #69)。Fortis の永続化実装は無い。
