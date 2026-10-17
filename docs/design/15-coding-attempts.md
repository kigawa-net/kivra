# 設計: coding_attempts / coding_events(V14)

- 対象 Issue: kigawa-net/kivra#187(ローカル PC の常駐エージェントによるコーディング実行)、親 #56 #157
- 位置づけ: 設計案 PR 2(DB マイグレーションとリポジトリ層のみ)。設計案の §2.5(Run 状態との対応)と §2.6(永続化)が根拠。設計案はリポジトリ外のドキュメント。
- 状態: 実装済み(`CodingAttemptRepository`、サービス層 `CodingRuntimeService`)。HTTP の `/api/agent/*`(PR 4a)とエージェント本体は未実装(別の PR)。既定の実行方式は `api` で、動作は変わらない。

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
| QUEUED | CANCELLED | `cancelQueued` | サービスの `cancel`(Run に取消要求があるとき)。CLAIMED / RUNNING は取り消さない |
| CLAIMED / RUNNING | ORPHANED | `expire` | `lease_expires_at` が過ぎた |

- 終端(RESULT_RECEIVED、FAILED、CANCELLED、EXPIRED、ORPHANED)から戻る遷移は無い。
- ORPHANED の後に、同じ Run へ新しい attempt を作る処理は**サービス層では行わない**(§6.4)。Run は UNKNOWN になり、運用者が判断する。リポジトリの `enqueue` は、そのような再投入を許す(`allowAfterSettled = true` の既定)。サービス層は `allowAfterSettled = false` で呼ぶ。
- 拒否の理由(`CodingRefusal`): `NOT_FOUND`、`NOT_QUEUED`、`QUEUE_EXPIRED`、`STALE_GENERATION`、`LEASE_EXPIRED`、`NOT_ACTIVE`。拒否は何も変えず、イベントも書かない。

## 4. `coding_events`

| 列 | 型 | 意味 |
|---|---|---|
| `attempt_id` | CHAR(26) | `coding_attempts` への外部キー |
| `seq` | INT | attempt 内の連番(1 から)。主キーは `(attempt_id, seq)` |
| `type` | VARCHAR(32) | 固定コード。状態遷移ごとに 1 件(QUEUED、CLAIMED、RUNNING、RESULT_RECEIVED、FAILED、CANCELLED、EXPIRED、ORPHANED)。進捗は `PROGRESS`(§6.2) |
| `payload_json` | VARCHAR(512) NULL | 小さな JSON か固定フィールドだけ。現時点では書いていない |
| `created_at` | DATETIME(6) | UTC |

- 追記のみ。heartbeat はイベントを書かない(高頻度なため)。進捗を付けた heartbeat だけ `PROGRESS` を 1 件書く。attempt あたり最大 100 件(`CodingAttemptRepository.MAX_PROGRESS_EVENTS`)。上限を超えても heartbeat は拒否されない。
- プロンプト、モデルの出力、標準出力・標準エラー、秘密は、どの列にも入れない。

## 5. マイグレーション上の注意

- V14 は expand-only(新しい 2 つの表だけ。既存の表は変更しない)。`MigrationExpandOnlyTest` の規則を満たす。
- DML 専用の接続ユーザー(`MigrationCredentialsTest` の構成)に新しい表への権限を付与する処理は、この PR に含まない。本番の付与(GRANT)は運用側の設定で行う必要があり、マージ前に確認する。

## 6. サービス層(`CodingRuntimeService`、設計案 PR 3)

### 6.1 位置づけ

- HTTP は無い。`CodingRuntimeService`(`kivra-app/.../coding/`)が attempt の遷移と、エージェントからの値の検査を行う。PR 4a の `/api/agent/*` は、この上に薄く載せる。エージェント本体は別の PR。
- `SelfDevRunner` の DEVELOPING 段は、`KIVRA_SELFDEV_RUNTIME=cli` のときだけ `CodingAttemptPatchSource`(`selfdev/CodingPatchSource.kt`)を通る。既定は `api` で、その経路は変わらない。本番では `cli` を起動時に拒否する(§6.8)。

### 6.2 操作

| 操作 | 内容 | 拒否・無視の条件 |
|---|---|---|
| `enqueue(runId, baseSha)` | DEVELOPING の Run に attempt を 1 つ積む(冪等) | Run が DEVELOPING でない、保留中、取消要求あり → `RunNotDeveloping`。非終端の attempt がある → `Active`。終端の attempt だけ → `Settled`(積み直さない。再試行ループを防ぐ) |
| `claim(agentId)` | 古い順に最大 `maxClaimCandidates`(10)件を見て、最初に取れたものを返す | 取る操作は repo の CAS。負けたら次の候補。Run が DEVELOPING でない、取消要求あり → その QUEUED を CANCELLED。保留中の Run の attempt は残す(期限切れまで)。タイトルが無い attempt は取らない |
| `heartbeat(attemptId, generation, progress?)` | lease を延ばす。進捗は固定の phase(`STARTED`、`EDITING`、`TESTING`、`ROUND_COMPLETED`、`FINALIZING`)だけ | 世代違い、lease 切れ、状態違い(リポジトリの拒否理由) |
| `submitResult(attemptId, generation, result)` | 結果を検査し、通れば保存して RESULT_RECEIVED にする(§6.4) | 世代違い、状態違い、lease 切れ、検査の拒否 |
| `fail(attemptId, generation, code)` | 固定コードで終端にする(§6.6) | 同上。自由文は受け取らない(`CodingErrorCode` の列挙だけ) |
| `cancel(runId)` | Run に取消要求を立てる。QUEUED の attempt は即 CANCELLED | Run が終端、または CANCELLED に進めない状態(UNKNOWN など) → `RunNotCancellable` |
| `reconcile()` | 期限切れを処理する(§6.7) | 冪等 |

- 設計案 §2.2 の `claim(agentId, now)` は、時刻を受け取らない形にした。期限は DB の時計(`NOW(6)`)で判定しており、呼び出し側の時計を入れると二重の基準になるため。
- 設計案 §2.2 の `heartbeat` の `progress` は、固定の phase だけを受け取り、`PROGRESS` イベントとして記録する(§4)。

### 6.3 エージェントに渡す入力(`CodingInput`)

- `runId`、`attemptId`、`leaseGeneration`(以後の全呼び出しで返す fencing の値)、`baseSha`(enqueue 時に固定。ブランチの先頭ではない)。
- `task`: タイトル。`UntrustedText`(origin `task_title`、trust `UNTRUSTED`)。2000 字で切り、切ったことを `truncated` で示す。`toString` に本文は出さない。
- 許可・拒否のパスパターン(PatchPolicy の既定と同じ元データ)。
- タイトルは**データとして**渡す。タイトルが「ポリシーを変えよ」と言っても効力は無い。最終的な防御は PatchPolicy と commitFiles の構造であり、プロンプトではない(ADR 0010 決定 7)。

既定の制限(`CodingRuntimeConfig` と `CodingLimits`):

| 項目 | 既定 | 出所・注 |
|---|---|---|
| 最大時間 | 30 分 | ADR 0010 の例。承認時に確定 |
| 最大ターン数 | 30 | `--max-turns` は未確認(設計案 §0)。エージェントが自分で数える |
| 出力上限 | 1 MiB | エージェントが CLI の出力から保持する量 |
| 変更ファイル数 | 20 | `PatchPolicyConfig.maxFiles`(同じ値をそのまま使う) |
| 1 ファイルのサイズ | 200 KiB | `PatchPolicyConfig.maxFileBytes` |
| 変更の合計サイズ | 1 MiB | `PatchPolicyConfig.maxTotalBytes` |
| 削除数 | 5 | `PatchPolicyConfig.maxDeletes` |
| lease | 2 分 | エージェントの heartbeat 間隔より長くする |
| queue の期限 | 24 時間 | 設計案 §2.5 の例。承認時に確定 |

- ファイル系の上限は `CodingLimits` が `PatchPolicyConfig` から作る。二つの数字がずれない。
- `DeveloperConstraints.maxFiles`(既定 10)との食い違いは、ADR 0010 の未決事項のとおり未解決(PR 9 で統一する)。

### 6.4 結果の検査(`submitResult`)

順序は次のとおり。

1. **事前確認**: attempt が CLAIMED か RUNNING で、世代が一致すること。違えば何も読まず、何も保存しない(`NOT_ACTIVE` / `STALE_GENERATION`)。
2. **件数**(中身を読む前): 変更件数が `maxChangedFiles` を超えれば `TOO_MANY_CHANGES`。
3. **形**: 各変更について、シンボリックリンク(`SYMLINK`)、submodule、実行ビットの変更(`EXECUTABLE_BIT`)、バイナリ(`BINARY_FILE`)を拒否。WRITE は内容が必須、DELETE は内容なし(`MALFORMED_CHANGE`)。終了コードは 0〜255、セッション ID は `[A-Za-z0-9_-]{1,128}`(`MALFORMED_RESULT`)。
4. **PatchPolicy**(リポジトリのスナップショットは無し): 秘密らしい内容、パス規則、サイズ、要約。拒否の理由は固定コードだけを返す。
5. 通れば、digest(SHA-256。長さを前置した各フィールドの列)を計算し、**本体を保存してから** `complete`(世代と lease を DB が CAS で確かめる)。

- 拒否は何も変えない。attempt は CLAIMED か RUNNING のままで、エージェントは直して送り直せる。
- 保存が失敗すれば `StoreFailed`。attempt は変わらない。
- 保存したあと `complete` が拒否されても(lease 切れなど)、保存された本体は参照されない。`RESULT_RECEIVED` の行だけが digest を持ち、その digest で本体を引く。参照されない本体の掃除は、保存先を決める時に決める。
- 本体は PatchSet(`Write` は全文、`Delete`)。パッチ形式は使わない(設計案 §1.4)。
- Run 側(`SelfDevRunner`)で、同じ PatchPolicy を**base のスナップショット付き**で再検査する(存在しない削除対象、大きな縮小)。エージェントは base の全体を見ているので、LLM の文脈の外にあるファイルの変更は UNSEEN_OVERWRITE にしない(§ `SelfDevRunner.proposeFromAgent`)。

### 6.5 結果本体の保存先(決定: V15 で別テーブル、別 PR。人の確認つき)

- 設計案 §2.6 は `patch_set_json` を attempt 行に持つとしていたが、V14 には列が無い。V14 は `result_digest` だけを持ち、本体は「別の場所」とする(V14 のコメント)。
- 候補:
  - (a) 本体の表を足す(V15、expand のみ)。サイズ上限(1 MiB 程度)と保存期間を決め、参照されない本体の掃除を入れる。
  - (b) attempt 行に JSON を持つ(V15 で列を足す)。行が大きくなるので、サイズ上限を強く守る必要がある。
  - (c) 本体を保存せず、commit の前にエージェントから取り直す。エージェントの作業ディレクトリは残らない設計なので、成り立たない。
- **決定(2026-10-17)**: (a) を採る。V15 で本体の別テーブルを追加する(expand-only)。attempt 行に JSON 列は足さない(サイズ上限と削除のしやすさのため)。
- **実装済み(V15、`DbCodingResultStore`)**。`CodingResultStore` の本番実装は `kivra-app/.../db/DbCodingResultStore.kt`。配線(`CodingRuntimeService` への注入と `cli` の起動拒否の解除)は PR 4a で行い、この PR では動作は変わらない。

#### 6.5.1 スキーマ(V15 `coding_results`)

| 列 | 内容 |
|---|---|
| `attempt_id` | CHAR(26)、主キー。`coding_attempts` への外部キー |
| `result_digest` | CHAR(64)。受理された結果の SHA-256 hex(`coding_attempts.result_digest` と同じ値) |
| `patch_json` | MEDIUMTEXT。PatchSet の JSON(`PatchSetParser` が読める形) |
| `created_at` | DATETIME(6)、UTC |

- 一つの attempt に一つの結果。主キーが `attempt_id` なので、同じ attempt に別の digest を保存することはできない。
- `patch_json` の上限は `PatchSetParser.MAX_INPUT_BYTES`(2 MiB)。PatchPolicy が通すのは生の内容で合計 1 MiB まで(`maxTotalBytes`)だが、JSON のエスケープ(制御文字は `\u00XX` の 6 バイト)で最大 6 倍に膨らむため、読み戻せる上限(2 MiB)で止める。上限を超えるものは保存せず拒否する。MEDIUMTEXT(MariaDB で 16 MiB まで)は 2 MiB の上限を十分に収める。LONGTEXT は読めない大きさまで許すだけなので使わない(V13 `response_json` と同じ型)。
- 保存するのは PatchPolicy を通った PatchSet だけ。stdout / stderr、プロンプト、モデルの本文、秘密は保存しない。`DbCodingResultStore` はログを出さず、例外メッセージに本文や digest を含めない。

#### 6.5.2 `DbCodingResultStore` の振る舞い

- `save(attemptId, digest, patch)`: 同じ attempt・同じ digest の再保存は成功して何もしない(冪等)。同じ attempt に異なる digest を保存しようとすると拒否する(結果の差し替え防止)。存在しない attempt は外部キーで拒否する。digest の形式が不正なら拒否する。
- `load(attemptId, digest)`: 保存された digest と一致する場合だけ本体を返す。一致しなければ null。保存された本体が読めない場合は例外(呼び出し側の `loadResult` が null にする)。
- **世代(fencing)**: store は世代を検査しない。`CodingRuntimeService.submitResult` が保存の前に世代と状態を検査し、保存の後に `complete` の CAS が世代を検査する。CLAIMED / RUNNING の間は世代が変わらない(世代を上げるのは claim だけで、claim は QUEUED からしか取れない)ので、古い保持者の保存は終端になった attempt の行にしか残らず、その行は参照されない。
- **digest の検査(未決)**: 設計どおり「保存時に attempt の `result_digest` と一致することを検査する」は、現在の順序(保存 → `complete` で `result_digest` を書く)では成り立たない。保存の時点で attempt の `result_digest` はまだ NULL である。この PR では保存時の検査を入れていない。読み出し時は、`load` が digest で行を選び、`loadResult` が attempt の `result_digest` を渡すので、一致しない本体は返らない。保存時に検査するには、順序を変えるか(RESULT_RECEIVED の前に digest を別の列に書く等)、保存の時点の検査を別の方法にするかの決定が要る。

#### 6.5.3 保持期間(未対応)

- この PR には削除の仕組みが無い。`coding_results` の行は消えない。
- 方針の案: Run が終端になった後、一定期間(例: 30 日)で本体を削除する。`complete` されず参照されない本体(保存後に拒否された結果)の掃除も同じ仕組みで行う。
- 削除は別の運用 PR で、人の確認を経て行う。この PR で削除の機能は作らない。

### 6.6 attempt の結果と Run の扱い

Run は DEVELOPING のまま attempt で進行を表す(設計案 §2.5)。attempt の終端の扱いは次の表のとおり。

| attempt の状態 | Run | 理由コード |
|---|---|---|
| RESULT_RECEIVED | 検査 → PATCH_READY → PULL_REQUEST(既存の経路)。検査で落ちれば FAILED | `POLICY_REJECTED` |
| FAILED(`AUTH_REQUIRED`、`NEEDS_HUMAN`) | UNKNOWN(運用者が判断) | `CODING_<code>` |
| FAILED(`TIMEOUT`、`OUTPUT_LIMIT`、`CLI_NONZERO`、`BAD_OUTPUT`、`RATE_LIMITED`) | FAILED | `CODING_<code>` |
| CANCELLED | CANCELLED | `CODING_CANCELLED` |
| ORPHANED | UNKNOWN(運用者が判断) | `CODING_ORPHANED` |
| EXPIRED | UNKNOWN(運用者が判断) | `CODING_QUEUE_EXPIRED` |

- `RATE_LIMITED` は、設計案の RETRY_WAITING ではなく FAILED にする。自動の再試行は、この PR では入れない。
- PATCH_READY の Run が再開されたとき、結果本体が取れなければ UNKNOWN(`CODING_RESULT_MISSING`)。再提案はしない(LLM を呼ぶ経路は CLI では使わない)。

### 6.7 reconcile(設計案 §2.7 からの変更)

- `expire`: QUEUED で期限切れなら EXPIRED。CLAIMED か RUNNING で lease 切れなら ORPHANED。
- ORPHANED または EXPIRED の attempt で、Run がまだ DEVELOPING のものは、Run を UNKNOWN にする(運用者の判断。解放は ADR 0009 の手順)。
- **設計案 §2.7 からの変更**: 設計案は「ORPHANED の後は次の claim を待つ(attempt_no + 1)」としていた。この PR では採らない。古いエージェントが CLI をまだ動かしているかもしれず、新しい attempt は二重の実行と二重の費用になる。二重実行の恐れがあるときは UNKNOWN に倒す、という方針による。
- 冪等。Run がすでに動いたもの、他の attempt が active なものには触らない。

### 6.8 配線と安全装置

- `KIVRA_SELFDEV_RUNTIME`: `api`(既定)か `cli`。`cli` は `SelfDevSettings` が起動時に拒否する(PR 4a まで。エージェントの claim 口が無く、積んだ attempt を誰も取らないため)。読むのは自己開発が有効なときだけ。
- `SelfDevRunner`: `runtime = CLI` のときに coding source が無ければ構築時に失敗する。`API` のときは coding source を使わない(既存のテストで固定)。
- 本番の配線(`buildSelfDev`)は変えていない。`CodingRuntimeService` を作る箇所は、この PR には無い。

### 6.9 ログ

- 出すのは、識別子(runId、attemptId)、固定の理由コード、件数だけ。
- タイトル、パス、要約、変更の内容、秘密は出さない。テストで、ログに値が出ないことを確認している(`CodingRuntimeServiceTest`)。

### 6.10 未実装(後続の PR)

- HTTP `/api/agent/*` と認可(PR 4a)。キャンセルをエージェントに伝える経路(heartbeat の応答、PR 4a)。
- エージェント本体(PR 5〜8)。結果本体の保存(§6.5)。`RATE_LIMITED` の待機と再試行。`cli` の起動許可(PR 9)。
