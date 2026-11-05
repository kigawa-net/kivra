# 引き継ぎ: dev環境 V40 migration と旧自己開発データの確認

更新日: 2026-11-02 (Asia/Tokyo)

## 目的

`kigawa-net/kivra` dev環境で、V40 `flatten_tasks` 適用時に旧Goal/Planデータがどう扱われたかを確認し、必要なら安全に保全・復旧する。調査中にDB本体への変更、restore、migration再実行はしない。

## 現在の確認状況

- Kubernetes namespace: `kigawa-net-kivra-dev`。
- 調査時点のkivra Podは1/1 Running、CrashLoop Podは解消していた。稼働イメージは `main-a7a06e5...`。
- `KIVRA_SELFDEV_ENABLED=true`。
- DBはMariaDB 11.8。Flyway履歴はV43まで成功。
- 2026-10-30 18:33頃のアプリログに、開始時のschema version 41、out-of-order mode有効、V40/V42/V43の適用、v43到達が記録されている。
- V40 SQL (`kivra-app/src/main/resources/db/migration/V40__flatten_tasks.sql`) は旧Goal/Plan系テーブルをDROPし、新しい `selfdev_tasks` を作成する。旧レコードを新Taskへ移すINSERT/UPDATEはない。
- V40適用前のDBには、読み取り専用集計で `selfdev_goals=8`, `selfdev_plans=7`, `selfdev_plan_tasks=27`, `selfdev_plan_memory_refs=0`, `selfdev_memory_records=8`, `selfdev_orders=1`, `selfdev_order_cycle_spend=0` があった。
- 適用後、旧テーブルは存在せず、`selfdev_tasks` が存在することを確認。旧テーブルをDBから再集計できなかった。
- 2026-10-30 18:00取得の移行前バックアップ `kivra-20261030-180005.sql.gz` がPVC上にあり、サイズ456,270 bytes。V40適用の約33分前。
- 最新（移行後）バックアップ `kivra-20261101-180010.sql.gz` は既存の使い捨てMariaDB復元Jobで検証済み。41テーブル、Flyway履歴、全テーブル行数が本体と一致した。これは移行前データの検証ではない。
- restore-test CronJobは`suspend=true`で、既定スクリプトはPVC上の最新ダンプを選ぶ。履歴ダンプを調べるには、対象ファイルを固定した別の一回限りJobが必要。

## 次に行う安全な確認

1. V40前バックアップを**新しい一時MariaDB**に復元する。一時DBは`emptyDir`のみを使い、ネットワークを無効化し、本体DBへは接続させない。バックアップPVCはreadOnlyでマウントする。
2. 対象ファイルを `kivra-20261030-180005.sql.gz` に固定する。globでlatestを選ばない。
3. 復元後、旧テーブルの行数と、状態ごとの件数だけを取得する。タイトル・本文・owner等の内容は出力しない。
4. バックアップの `flyway_schema_history` と、V40後の現行 `selfdev_tasks` の状態別件数を比較する。ID/内容の対応確認が必要なら、値をログに出さず一時環境内で件数・対応率だけを計算する。
5. 一時Job完了後、ログに秘密や自由記述が含まれないことを確認する。Job/Podの削除は調査結果を記録した後に別途判断する。

## 判断上の注意

- 現時点でデータ損失は強く疑われるが、移行前ダンプ内のレコードと現行Taskの対応を照合していないため、損失範囲は未確定。
- V40を再実行、Flyway履歴を手修正、現行DBへ旧バックアップをrestore、またはout-of-order設定を変更しない。
- 本体DBへの復元は後続V42/V43やV40後の書込みを巻き戻しうる。検証DBで旧データを確認後、データ保全/再取り込みの設計と承認を別に行う。
- 最新バックアップが復元できたことは、移行前ダンプの復元可能性を証明しない。

## 関連ファイル・運用文書

- `kivra-app/src/main/resources/db/migration/V40__flatten_tasks.sql`
- `docs/runbook-db-backup-restore.md`
- `docs/runbook-selfdev.md`

## 実施していないこと

この引き継ぎ作成時点では、DB、Kubernetes Deployment/CronJob、バックアップファイルに変更を加えていない。履歴ダンプの復元検証Jobもまだ作成していない。
