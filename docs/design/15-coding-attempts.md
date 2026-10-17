# 設計: coding_attempts / coding_events(V14)

- 対象 Issue: kigawa-net/kivra#187(ローカル PC の常駐エージェントによるコーディング実行)、親 #56 #157
- 位置づけ: 設計案 PR 2(DB マイグレーションとリポジトリ層のみ)。設計案の §2.5(Run 状態との対応)と §2.6(永続化)が根拠。設計案はリポジトリ外のドキュメント。
- 状態: 実装済み(`CodingAttemptRepository`)。`CodingRuntimePort`、API、エージェントは未実装(後続の PR)。この時点では動作は変わらない。

## 1. 位置づけ

- Run の状態は増やさない。Run は `DEVELOPING` のまま、attempt の状態で進行を表す。
- attempt は、ローカル PC のエージェントが取りに来るキューである。kivra は PC に到達できないので、起動はしない(pull 型)。
- 全ての状態変化は 1 本の条件付き UPDATE(CAS)。影響行数が 1 のときだけ成功する。失敗は例外ではなく結果値(`CodingAttemptResult.Refused`)で返し、何も変えない。
- 期限は DB の時計(`NOW(6)`)で判定する。作成・更新時刻だけ注入された `Clock` を使う(`DevelopmentRunRepository` と同じ)。

## 2. `coding_attempts`

| 列 | 型 | 意味 |
|---|---|---|
| `attempt_id` | CHAR(26) 主キー | ULID |
| `run_id` | CHAR(26) | `development_runs.run_id` への外部キー |
| `attempt_no` | INT | Run 内の番号(1 から)。`UNIQUE(run_id, attempt_no)` |
| `state` | VARCHAR(16) | §3 の状態。CHECK 制約あり |
| `claimed_by` | VARCHAR(64) NULL | 取得したエージェントの識別子(認証情報ではない) |
| `lease_generation` | BIGINT | fencing 用。claim のたびに +1。heartbeat / start / complete / fail は一致する値だけ受け付ける |
| `lease_expires_at` | DATETIME(6) NULL | lease の期限(DB の時計) |
| `queued_expires_at` | DATETIME(6) | QUEUED の期限。過ぎた QUEUED は EXPIRED になる |
| `base_sha` | VARCHAR(64) | enqueue 時に固定するコミット(ブランチの先頭ではない) |
| `result_digest` | CHAR(64) NULL | 結果の SHA-256(16 進)。結果本体はここに保存しない |
| `exit_status` | INT NULL | CLI の終了コード |
| `error_code` | VARCHAR(64) NULL | 固定の理由コード(例: `TIMEOUT`)。自由記述は入れない |
| `session_id` | VARCHAR(128) NULL | Claude CLI のセッション識別子だけ |
| `active_flag` | TINYINT(生成列) | QUEUED / CLAIMED / RUNNING のとき 1、それ以外は NULL |
| `version` | BIGINT | 楽観ロック。更新のたびに +1 |
| `created_at` / `updated_at` | DATETIME(6) | UTC |

- **Run ごとに非終端は 1 つ**: `UNIQUE(run_id, active_flag)`。`active_flag` が NULL の行は一意性の対象にならない(MariaDB と H2 の両方で同じ)。部分インデックスが無い MariaDB で使う既存の V1 `active_flag` と同じ仕組み。
- 索引: `(state, lease_expires_at)`、`(state, queued_expires_at)`。期限切れの走査用。

## 3. 状態遷移

| から | へ | 操作 | 条件 |
|---|---|---|---|
| (なし) | QUEUED | `enqueue` | Run に非終端の attempt が無い。ある場合はそれを返す(冪等) |
| QUEUED | CLAIMED | `claim` | QUEUED かつ `queued_expires_at > NOW`。`lease_generation` +1、`lease_expires_at` = NOW + TTL |
| CLAIMED | RUNNING | `start` | 世代が一致し、lease が有効 |
| CLAIMED / RUNNING | (lease 延長) | `heartbeat` | 世代が一致し、lease が有効。延長だけ行う |
| CLAIMED / RUNNING | RESULT_RECEIVED | `complete` | 世代が一致し、lease が有効。結果の digest・終了コード・session_id を記録 |
| CLAIMED / RUNNING | FAILED / CANCELLED | `fail` | 世代が一致し、lease が有効。固定の `error_code` を記録 |
| QUEUED | EXPIRED | `expire` | `queued_expires_at` が過ぎた |
| CLAIMED / RUNNING | ORPHANED | `expire` | `lease_expires_at` が過ぎた |

- 終端(RESULT_RECEIVED、FAILED、CANCELLED、EXPIRED、ORPHANED)から戻る遷移は無い。
- ORPHANED の後は、Run に新しい attempt が作られる(attempt_no は次の番号)。Run は DEVELOPING のまま。この扱いは後続の PR(reconcile)で行う。
- 拒否の理由(`CodingRefusal`): `NOT_FOUND`、`NOT_QUEUED`、`QUEUE_EXPIRED`、`STALE_GENERATION`、`LEASE_EXPIRED`、`NOT_ACTIVE`。拒否は何も変えず、イベントも書かない。

## 4. `coding_events`

| 列 | 型 | 意味 |
|---|---|---|
| `attempt_id` | CHAR(26) | `coding_attempts` への外部キー |
| `seq` | INT | attempt 内の連番(1 から)。主キーは `(attempt_id, seq)` |
| `type` | VARCHAR(32) | 固定コード。状態遷移ごとに 1 件(QUEUED、CLAIMED、RUNNING、RESULT_RECEIVED、FAILED、CANCELLED、EXPIRED、ORPHANED) |
| `payload_json` | VARCHAR(512) NULL | 小さな JSON か固定フィールドだけ。現時点では書いていない |
| `created_at` | DATETIME(6) | UTC |

- 追記のみ。heartbeat はイベントを書かない(高頻度なため)。
- プロンプト、モデルの出力、標準出力・標準エラー、秘密は、どの列にも入れない。

## 5. マイグレーション上の注意

- V14 は expand-only(新しい 2 つの表だけ。既存の表は変更しない)。`MigrationExpandOnlyTest` の規則を満たす。
- DML 専用の接続ユーザー(`MigrationCredentialsTest` の構成)に新しい表への権限を付与する処理は、この PR に含まない。本番の付与(GRANT)は運用側の設定で行う必要があり、マージ前に確認する。
