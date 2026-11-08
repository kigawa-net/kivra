# ADR 0022: 全エンティティをプロジェクト配下にする(方針と段階。第1段は expand のみ)

- ステータス: 提案(Proposed)。この PR は文書と、第1段(PR-A)のスキーマ変更だけを含む。
- 日付: 2026-11-08
- 関連: ADR 0020(担当とロール)、ADR 0021(停止要求の承認)、V38(projects)、V42(orders と assignees の project_id)

## 背景

現状、`development_runs`、`selfdev_orders`、`selfdev_assignees` は `project_id` を持つ(V38、V42。いずれも `ON DELETE SET NULL`)。一方で `selfdev_tasks` と `selfdev_memory_records` にはプロジェクトの列がなく、プロジェクトの単位で見る・権限を分ける・予算を分けることができない。

## 決定

1. **デフォルトプロジェクトは作らない。** 「どこにも属さない」を表す特別なプロジェクトや `is_default` 列は持たない。属さない行は `project_id` が NULL のまま残す。
2. **既存データは後続の PR-B で振り分ける。** 既存の `projects` に対して、`project_repos`(repo_key から project_id への対応)を使って行を割り当てる。対応が不明な行は NULL のまま残し、推測で割り当てない。
3. **新規作成は将来、`projectId` を必須にする(PR-C で 400)。** 今の挙動は変えない。
4. **FK は `ON DELETE RESTRICT`。** 新しく付ける列では、プロジェクトに行が残っている間は削除できないようにする。行が黙って未割り当てに戻ることを防ぐ。(V38、V42 の `SET NULL` は既存のまま変えない。)
5. **すべて expand のみ。** 列とテーブルの追加だけ。既存の INSERT(新しい列を書かないもの)は通り、新しい列は NULL になる。

## 段階

| 段階 | 内容 | 挙動の変化 |
|---|---|---|
| PR-A(この PR) | V50: `selfdev_tasks.project_id`、`selfdev_memory_records.project_id`(NULL 可、FK RESTRICT、INDEX)。V51: `project_members`、`project_repos`、`project_budgets` | なし |
| PR-B | 既存行の振り分け(`project_repos` による backfill。対応不明は NULL のまま) | 読み出しの絞り込み |
| PR-C | 新規作成で `projectId` を必須にする(欠けたら 400) | 新規作成の API |
| PR-D | 権限の判定を `project_members` で行う | 権限 |
| PR-E | 予算を `project_budgets` で行う | 予算の判定 |
| PR-F | NULL の行を消す(Contract)。NULL のまま残す行がなくなった後にだけ行う | 不変条件の強化 |

PR-F まで NULL を残すのは、PR-B で対応が不明な行を推測で割り当てないためである。

## テーブル(V50、V51)

- `project_members(project_id, assignee_id, role, created_at)`: PK は `(project_id, assignee_id)`。role は `selfdev_assignees.role` と同じ集合。
- `project_repos(project_id, repo_key, created_at)`: `repo_key`(owner/name)は UNIQUE で、1 つのリポジトリは高々 1 つのプロジェクトに属する。
- `project_budgets(project_id, monthly_limit_jpy, soft_ratio, updated_at)`: PK は `project_id`。金額は `DECIMAL(12,0)`(JPY)。`soft_ratio` は 0 から 1 の警告しきい値(既定 0.800)。

型、文字コード、FK の方針は `projects` と `selfdev_assignees` に合わせる(CHAR(26) の ULID、`DATETIME(6)`、FK の名前は `fk_<テーブル>_<先>`)。

## 確認

- `MigrationChainTest` に V50、V51 を追加。`MigrationExpandOnlyTest` は全ファイルを走査し、非 expand の変更がないことを確かめる。
- 「V49 までの列だけで書く INSERT が V51 の後でも通り、`project_id` が NULL になる」と「値のあるプロジェクトを削除すると失敗する(RESTRICT)」を `MigrationChainTest` で確かめる。
