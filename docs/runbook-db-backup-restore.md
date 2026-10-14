# Runbook: DB のバックアップ・リストアと migration のロールバック（dev）

対象: Kivra の dev 環境の MariaDB（Issue #88 の受け入れ条件「Migration Failure 時の backup/restore、並行 Flyway lock、rollback/roll-forward runbook」）。
この文書には秘密の値（パスワード、Secret の中身）を書かない。接続情報は Kubernetes Secret から読むこと。

## 1. 構成（dev で確認済み）

| 項目 | 値 |
|---|---|
| DB リソース | `kivra-db`（mariadb-operator の CR） |
| データベース | `kivra` |
| アプリ用ユーザー | `kivra`（DML のみ: SELECT, INSERT, UPDATE, DELETE） |
| migration 用ユーザー | `kivra-migrator`（ALL。Flyway だけが使う） |
| バックアップ用ユーザー | `kivra-backup`（SELECT, SHOW VIEW, EVENT, TRIGGER） |
| 日次バックアップ | CronJob `kivra-mariadb-backup-cj`（毎日 03:00 JST） |
| バックアップ保存先 | 別 PVC `kivra-mariadb-backup-pvc` の `/backup/kivra-YYYYmmdd-HHMMSS.sql.gz` |
| 保持 | 直近 7 世代 |
| リストア試験 | CronJob `kivra-mariadb-restore-test-cj` |

バックアップは `mariadb-dump --single-transaction` で取得する（InnoDB の一貫したスナップショット。テーブルのロックは取らない）。

`kubectl` の操作では namespace を明示する（以下の `<ns>` は dev の Kivra namespace に読み替える）。

## 2. バックアップの確認

```bash
# CronJob の状態（最終実行時刻、失敗の有無）
kubectl -n <ns> get cronjob kivra-mariadb-backup-cj kivra-mariadb-restore-test-cj

# 直近の Job
kubectl -n <ns> get jobs --sort-by=.metadata.creationTimestamp | grep kivra-mariadb-backup

# 保存先の一覧（Pod は一時的に起動して PVC を見る。読み取りのみ）
kubectl -n <ns> get pvc kivra-mariadb-backup-pvc
```

直近のダンプが 03:00 JST 付近に作られ、世代数が 7 を超えていないこと、サイズが 0 でないことを確認する。

## 3. 手動でバックアップを取る

定期実行を待たずに、CronJob から Job を作る。

```bash
kubectl -n <ns> create job --from=cronjob/kivra-mariadb-backup-cj kivra-mariadb-backup-manual-$(date +%Y%m%d%H%M)
kubectl -n <ns> logs job/kivra-mariadb-backup-manual-<時刻> -f
```

migration を流す前（§6 の手順 1）は、必ず手動でバックアップを取ってから実行する。

## 4. リストア試験（定期の確認）

`kivra-mariadb-restore-test-cj` は、使い捨ての MariaDB を立てて最新のダンプを流し込み、テーブル数と各テーブルの行数を本体（`kivra`）と比べる。

```bash
kubectl -n <ns> create job --from=cronjob/kivra-mariadb-restore-test-cj kivra-restore-test-manual-$(date +%Y%m%d%H%M)
kubectl -n <ns> logs job/kivra-restore-test-manual-<時刻> -f
```

ログの末尾で、テーブル数と行数の比較結果を確認する。差がある場合は、ダンプ取得時刻以降に書き込まれた行の分として説明できるか確認する（日次ダンプは直近の書き込みを含まない）。
この試験は 2026-10 時点で dev で成功済み。

## 5. リストア（本体への適用）

リストアは本体の `kivra` を上書きするので、**必ず関係者の確認を取ってから**行う。まずリストア先を別の空の DB にして内容を確認することを推奨する。

1. アプリを止める（新しい書き込みを止める）。Deployment を 0 replica にするか、ingress を止める。
2. 対象のダンプを確認する（ファイル名・サイズ・取得時刻）。ダンプを取り出して中身を直接表示しない。
3. リストアは migration 用ユーザーで行う（DDL が必要なため）。パスワードは Secret から環境変数に入れ、コマンドラインに直接書かない。

```bash
# バックアップ PVC を読める一時 Pod から、gzip を展開して MariaDB クライアントに流す（概念。接続先と認証は Secret から）
gunzip -c /backup/kivra-YYYYmmdd-HHMMSS.sql.gz | mariadb --host <kivra-db のホスト> --user kivra-migrator --password "$MIGRATOR_PASSWORD" kivra
```

4. リストア後、テーブル数と主要テーブルの行数を確認する（§4 と同じ比較）。
5. Flyway の履歴（`flyway_schema_history`）が、リストアしたダンプの時点のバージョンと一致することを確認する。
6. アプリを戻す。ダンプより新しいイメージが動いていた場合は、そのイメージの migration が適用済みかを確認してから起動する。

## 6. Migration の失敗時

Migration は forward-only の Expand/Contract で書く方針（V1〜V10 は編集しない。V2 のファイル先頭コメントは「未マージ・未適用のため in-place 編集した」と記すが、Issue #88 は PR #43 の V1/V2 を統合済みの前提で書かれている。どちらが正かは未確認）。

1. **適用前**: 手動バックアップ（§3）を取る。
2. **適用中に失敗**（Flyway が失敗を記録）: `flyway_schema_history` の該当行は `success = 0` で残る。**自動の repair や skip はしない**。失敗の原因（SQL エラー、権限不足、ロック待ち）を特定する。
3. **並行実行**: 複数の replica が同時に migration を実行しようとした場合、Flyway の DB ロックで直列化される想定。ただしこの runbook では並行実行の挙動を実機で確認していない（未対応）。デプロイは migration Job を 1 つだけ走らせる構成にする。
4. **復旧の選択肢**:
   - roll-forward（推奨）: 失敗した原因を直す新しい migration（次の番号）を追加する。失敗した行は、原因を確認したうえで手で整理する。
   - restore（最終手段）: §5 のリストア。書き込みを止めた状態で行い、リストア後に migration の状態を確認する。

## 7. ロールバックの考え方（Expand/Contract）

- **スキーマは巻き戻さない**。`DROP COLUMN` や `DROP TABLE` を伴う rollback はしない。
- 新しいイメージで追加された列やテーブルは、旧イメージから見ると存在しないだけで、旧イメージは動く想定で設計している（Expand-only）。したがって、イメージだけを旧版に戻せば、DB は壊れない。
- 旧イメージが新しい列を知らないことで不正動作しないか（Fail-Closed になるか）は、リリース前に確認する。
- 例外: 旧イメージが新しい NOT NULL 列（DEFAULT なし）を埋められない場合、その書き込みは失敗する。現時点で該当する列は `development_runs.completion_mode`（V2）で、旧 Writer を止めてから V2 を適用する必要がある。詳細は `kivra-app/src/test/kotlin/net/kigawa/kivra/db/MigrationExpandOnlyTest.kt` の `KNOWN_CONTRACT_REQUIRED` を参照。

## 8. 既知の限界

- **保存先 A の限界**: バックアップは本体と同じ Ceph クラスタの別 PVC に置いている。Ceph 全体の障害、クラスタ全体の削除、誤った namespace の削除では、本体と一緒に失われる。オフサイトへのコピーは未実装。
- 世代は 7 日分のみ。それより古い状態には戻れない。
- リストア試験は、ダンプの中身（テーブル数と行数）の一致を確認するが、アプリの動作確認まではしない。
- 並行 Flyway 実行の挙動は未検証（§6-3）。
