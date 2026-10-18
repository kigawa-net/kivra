# ADR 0011: 自己開発オートパイロットは、運用者の Goal から Plan を作り、承認と予算の範囲で Run に投入する

- ステータス: 提案(Proposed)。PR A〜D(基盤、Planner、Dispatcher、UI と HTTP)は main に実装済みだが、段階 1 の dev での有効化と E2E が未実施のため、Proposed のまま(「実装状況」を参照)
- 日付: 2026-10-18
- 関連: Issue #233(本 ADR の設計案の正本)、#229(依頼文の本文 body)、#230(CLI 経路の費用の予算 ledger 計上)、#231(ローカルエージェントの結線の残件。reconcile の配線)、#222(E2E fixture)、#187(ローカルエージェント)、#56(Self-Development Integration Runner)、#112(Task API)。ADR 0001(Run の正本)、ADR 0003(予算と UNKNOWN_COST)、ADR 0004(予算の正本は BudgetPort)、ADR 0005(結果の分類と UNKNOWN)、ADR 0008(PR 作成までの自己開発ループ、マージは人間)、ADR 0009(運用者による UNKNOWN と PULL_REQUEST の解放)、ADR 0010(CLI 経路の実行基盤)

## 背景

現在の自己開発(ADR 0008、ADR 0010)は、運用者が 1 件ずつ Task を投入する。投入した Run は PR を作るところまで自動で進み、マージは人が行う。
1 件の依頼文は title(200 文字)だけで、本文は CLI に渡らない(#229)。

運用者が求めているのは、kivra が自分の開発計画を立て、タスクに分解し、Run として順に投入することである。人が行うのは、PR のレビューとマージ、そして停止と承認の操作だけにする。
これを「オートパイロット」と呼ぶ。

オートパイロットは、費用と PR の数を自動で増やす。次の前提が無いまま自動化すると、費用の計上の外で使われ、レビューできない PR が積み上がる。

- CLI 経路の費用は、予算 ledger に計上されていない(#230)。自動投入は費用を自動で使うので、この前提が必須である。
- 計画の各タスクは、対象・手順・受け入れ条件を持つ必要がある。title だけでは足りない(#229)。
- 失敗を検出して止める経路(reconcile の配線、#231)が無いと、失敗が放置される。

本 ADR は、オートパイロットの設計を決める。実装は段階ごとの PR に分け、前提が満たされるまで有効化しない。

## 決定

### 1. 入力は、ダッシュボードの Goal フォームだけにする

- 入力は、既存の `/ui`(運用者のダッシュボード)に追加する **Goal フォーム** から行う。GitHub Issue のラベル、本文、コメントを計画の入力にしない。外部から書き込める経路を作らないためである。
- Goal の項目は次のとおり。
  - `title`(必須、1〜200 文字。既存の `validatedText` と同じ規則)
  - `body`(任意、0〜8000 文字。#229 の依頼文の上限と揃える。値は実装 PR で確定する)
  - 対象リポジトリ: 段階 1 では表示のみ(既定 `kigawa-net/kivra`、`KIVRA_SELFDEV_TARGET_REPO`)。画面から変えられるようにしない。変更できると、攻撃面が増えるためである。
- 認可は既存の Task API と同じにする。`TaskApiIdentity` の主体、OIDC の必須ロール(`admin-panel:admin`)、`requireJsonSameOrigin`(CSRF)。エージェントのロール(`kivra-agent`)の主体は拒否する(`AgentIdentity` の規則のまま)。未認証は 401。
- Goal の `title` と `body` は **UNTRUSTED** として扱う。Planner の入力では、データとして区切って渡す。命令として解釈させない。kivra はモデルの出力を実行しない。
- 無効時(キルスイッチ off)は、Goal を受け付けない(503)。無効のときにデータを増やさないためである。

#### 1.1 UI の配置(既存の `/ui` の作りに合わせる)

既存の `kivra-app/src/main/resources/ui/index.html` の `<div id="view-list">` は、「Task を投入」(`#submit-form`、`section.card`)と「Run の一覧」の 2 つの section からなる。`app.js` は `init()` で各ボタンにイベントを結び、`apiFetch`(JSON と同一オリジンの検査を含む)、`newKey()`(idempotencyKey の生成)、`h()`(DOM の組み立て。`textContent` のみ)を使う。

- `index.html`: 「Task を投入」section(既存の `#submit-form` を含む `<section>`)の直後に、次の section を足す。
  - `<section class="card" aria-labelledby="h-goal">` に `<form id="goal-form">`。入力は `#goal-title`(`maxlength="200"`)、`#goal-body`(`textarea`、`maxlength="8000"`)、`#goal-count`(文字数の表示)、送信は `#goal-submit`、結果は `#goal-result`(`role="status"`)。対象リポジトリは `#goal-repo`(読み取り専用の表示)。
  - その直後に `<section class="card" aria-labelledby="h-plans">`。全体の状態(`#autopilot-status`)、全体の一時停止と再開(`#autopilot-toggle`、既存の `#release-dialog` と同じ理由入力のダイアログを使う)、Goal の一覧(`#plans-list`)を置く。
  - この 2 つの section は、`hidden` を既定にし、`/api/selfdev/status`(現在は有効か、一時停止か、を返す)に「オートパイロットの有効」の項目を足し、それが真のときだけ表示する。
- `app.js`: `onSubmit`(既存、398 行付近)と同じ形で `onGoalSubmit` を足し、`init()` で `goal-form` の `submit` に結ぶ。`refreshPlans()` を足し、既存の `tick()`(873 行付近)の更新に載せる。`refreshStatus()`(581 行付近)は、`autopilot` の項目を読む。
- 承認(タスクごと)、Goal の一時停止と再開、再計画、中止は、Goal の詳細(`#detail-body`、既存の `renderDetail` と同じ位置)の中のボタンで行う。中止だけは理由を要求し、`openRelease` と同じダイアログの作りを使う。
- 表示する本文は、すべて `textContent` で入れる。PR の URL は既存の `safePrUrl` を通す。

#### 1.2 HTTP の面(提案)

| メソッド・パス | 役割 | 成功 | 主な失敗 |
|---|---|---|---|
| `POST /api/selfdev/goals` | Goal を受け付ける。本文は `{title, body?, idempotencyKey}`(厳格。ほかのフィールドは 400) | 202 | 400、401、403(CSRF)、409(同じ key で別内容)、503(無効) |
| `GET /api/selfdev/goals`、`GET /api/selfdev/goals/{id}` | 一覧、詳細(Plan とタスク。本文は投入者と運用者だけ) | 200 | 401、404 |
| `POST .../goals/{id}/plan/tasks/{n}/approve` | タスク n を承認する(`PENDING_APPROVAL` → `QUEUED`) | 200 | 404、409 |
| `POST .../goals/{id}/replan` | Plan を作り直す(上限 3 回。§2.1・§2.5) | 202 | 409、429 |
| `POST .../goals/{id}/pause`、`.../resume` | Goal の一時停止と再開 | 200 | 409 |
| `POST .../goals/{id}/stop` | 中止(理由 1〜500 文字。監査の payload にだけ入る) | 200 | 400、409 |
| `POST /api/selfdev/autopilot/pause`、`.../resume` | 全体の一時停止(UI のスイッチ) | 200 | 409 |

すべての書き込みは、`requireJsonSameOrigin` と、既存の運用者の認可を通す。理由や本文は、ログと outbox に出さない(§7)。

### 2. データモデル(V18)

V18 は、次の 4 つのテーブルを追加する(既存のテーブルは変えない。expand のみ)。V16 は本 ADR の初版の番号だが、実装時点で `coding_attempt_usage`(V16)に使われていたため、V18 に読み替えた(§2.8)。`development_runs` は変えない(ADR 0001 の Run の正本のまま)。

#### 2.1 `selfdev_goals`

| 列 | 型(案) | 意味 |
|---|---|---|
| `goal_id` | `varchar(26)` PK | ULID |
| `created_by` | `varchar(255)` | 投入した運用者(認証された主体) |
| `idempotency_key` | `varchar(128)`、`(created_by, idempotency_key)` の一意制約 | 二重送信の防止 |
| `title` | `varchar(200)` | 目的 |
| `body` | `text`(上限 8000 文字で検査) | 背景・制約・受け入れ条件。投入者と運用者だけが見る |
| `target_repo` | `varchar(200)` | 投入時点の対象リポジトリ |
| `state` | `varchar(32)` | §2.5 の状態 |
| `state_reason` | `varchar(64)` | 固定コード(自由記述は入れない) |
| `revision` | `int` | Plan の作成回数(上限 3) |
| `claim_token` | `varchar(64)`、null 可 | Planner の claim(§2.7) |
| `claimed_until` | `timestamp`、null 可 | claim の期限 |
| `version` | `bigint` | 楽観的ロックと fencing(§2.7) |
| `created_at` / `updated_at` | `timestamp` | |

#### 2.2 `selfdev_plans`

| 列 | 型(案) | 意味 |
|---|---|---|
| `plan_id` | `varchar(26)` PK | ULID |
| `goal_id` | `varchar(26)` FK | |
| `revision` | `int` | 1 から |
| `state` | `varchar(16)` | `PROPOSED` / `SUPERSEDED` / `REJECTED` |
| `summary` | `varchar(500)` | 検証後の要約(制御文字を除く) |
| `planner_model` | `varchar(64)` | 使ったモデルの id(例: `claude-haiku-5-5`) |
| `output_sha256` | `char(64)` | モデルの生の出力のダイジェスト(本文は保存しない) |
| `request_ledger_id` | `varchar(64)`、null 可 | ModelGateway の request ledger への参照 |
| `created_at` | `timestamp` | |

#### 2.3 `selfdev_plan_tasks`

| 列 | 型(案) | 意味 |
|---|---|---|
| `plan_id` / `ordinal` | PK(複合) | `ordinal` は 1 から |
| `title` | `varchar(200)` | 依頼文の title |
| `body` | `text`(上限 8000 文字) | 依頼文の本文(#229 の body。CLI に渡る) |
| `target_paths` | `text`(JSON 配列、上限 10 件) | 対象パスの見込み(検証済み) |
| `size_estimate` | `varchar(2)` | `S` / `M` / `L`(Planner の見積り。信頼しない。§4) |
| `depends_on` | `text`(JSON 配列 of ordinal) | 依存先(必ず `ordinal` より小さい値) |
| `requires_approval` | `boolean` | denylist に触れる、または段階の規則で承認が要る |
| `state` | `varchar(24)` | §2.5 のタスクの状態 |
| `state_reason` | `varchar(64)` | 固定コード |
| `idempotency_key` | `varchar(128)` 一意 | `ap:{plan_id}:{ordinal}`(§5.1) |
| `run_id` | `varchar(26)`、null 可 | 投入された Run(`development_runs.run_id`) |
| `attempts` | `int` | 投入の試行回数 |
| `version` | `bigint` | |

#### 2.4 `selfdev_autopilot_control`(単一行)

オートパイロット全体の状態を 1 行で持つ。全体の一時停止、連続失敗の計数(サーキットブレーカー)、当日の Run 数、最後の一時停止の主体と時刻を置く。

- `paused`(boolean)、`paused_by`、`paused_at`
- `consecutive_failures`(int)、`breaker_open`(boolean)
- `day_utc`(date)、`runs_today`(int)
- `version`

注: この表は Issue #233 の 3 表に、設計の都合で 1 つ加えたものである。単一行のスイッチを環境変数だけにすると、運用中に止められないので、加える。異論があれば未決の点 U-11 で確認する。

#### 2.5 状態機械

Goal(`selfdev_goals.state`):

| 状態 | 意味 |
|---|---|
| `GOAL_SUBMITTED` | 受け付けた。Planner はまだ動いていない |
| `PLANNING` | Planner が claim している |
| `PLAN_PROPOSED` | 検証を通った Plan があり、承認を待つ |
| `TASKS_QUEUED` | 承認されたタスクがあり、投入を待つ(投入はまだ) |
| `RUNNING` | 投入済みのタスクがある |
| `BLOCKED` | ガードで止まっている(`state_reason` に理由。§5.2) |
| `PAUSED` | 運用者が Goal を一時停止した |
| `DONE` | 全タスクが `COMPLETED`(PR がマージされた)。終端 |
| `STOPPED` | 運用者が中止した。または、全タスクが終わったが全部は `COMPLETED` でなかった(§2.8b)。終端 |

遷移表(Goal):

| 遷移元 → 遷移先 | 契機 | ガード | 固定の理由コード |
|---|---|---|---|
| (なし) → `GOAL_SUBMITTED` | `POST /api/selfdev/goals` | 有効、(同じ key なら Replayed) | — |
| `GOAL_SUBMITTED` → `PLANNING` | ワーカーが claim | 有効、全体一時停止でない、予算の予約が取れる、`version` が一致 | — |
| `PLANNING` → `PLAN_PROPOSED` | Planner の出力が検証を通る | `version` と `claim_token` が一致(fenced)。Plan とタスクを同じ transaction で書く | — |
| `PLANNING` → `BLOCKED` | 検証失敗が 2 回続く、または予算不足、または呼び出しの失敗 | 同上 | `PLAN_INVALID` / `PLAN_BUDGET_HOLD` / `PLAN_CALL_FAILED`(§2.8) |
| `PLANNING` → `GOAL_SUBMITTED` | claim の期限切れ(ワーカーの停止など) | `claimed_until` を過ぎた | `CLAIM_EXPIRED` |
| `PLAN_PROPOSED` → `TASKS_QUEUED` | 運用者が 1 件目を承認 | 承認はタスク単位 | — |
| `PLAN_PROPOSED` → `GOAL_SUBMITTED` | 運用者の再計画(Planner が claim し直す。§2.8) | `revision` < 3 | `REPLAN_REQUESTED` |
| `TASKS_QUEUED` → `RUNNING` | 最初のタスクの投入 | §5.1 のガードを通る | — |
| `BLOCKED`(計画段階の理由) → `GOAL_SUBMITTED` | 運用者の再計画(U-16) | 計画段階の理由だけ(§2.8) | `REPLAN_REQUESTED` |
| `RUNNING` / `TASKS_QUEUED` → `BLOCKED` | ガードで止まる | §5.2 | §5.2 の理由 |
| `BLOCKED` → `RUNNING` / `TASKS_QUEUED` | ガードが解消 | 自動で解消するもの(滞留、日次上限、月次予算の次の期間)。手動のもの(サーキットブレーカー、範囲外の変更)は運用者の再開が要る | `GUARD_CLEARED` / `OPERATOR_RESUMED` |
| `RUNNING` → `DONE` | 全タスクが `COMPLETED` | — | `ALL_TASKS_COMPLETED` |
| `TASKS_QUEUED` / `RUNNING` / `BLOCKED` ⇄ `PAUSED` | 運用者の一時停止と再開 | 再開は前の状態に戻す | `OPERATOR_PAUSED` / `OPERATOR_RESUMED` |
| 有効な状態 → `STOPPED` | 運用者の中止 | 理由 1〜500 文字(監査のみ) | `OPERATOR_STOPPED` |
| `RUNNING` / `TASKS_QUEUED` / `BLOCKED` → `STOPPED` | 全タスクが終端(`COMPLETED` / `FAILED` / `SKIPPED`)で、全部は `COMPLETED` でない(Dispatcher。§2.8b) | — | `TASKS_INCOMPLETE` |

`DONE` と `STOPPED` は終端である。`PLANNING` の claim が切れたときの `GOAL_SUBMITTED` への戻しは、revision を増やさない。

タスクの状態(`selfdev_plan_tasks.state`):

| 状態 | 意味 |
|---|---|
| `PENDING_APPROVAL` | 運用者の承認を待つ(段階 1 では全件) |
| `QUEUED` | 承認済み、または自動の対象。依存の完了を待つ |
| `SUBMITTING` | 投入の途中(CAS で取った。§5.1) |
| `SUBMITTED` | Run を作った(`run_id` を持つ) |
| `COMPLETED` | Run が `COMPLETED`(PR がマージされた) |
| `FAILED` | Run が `FAILED`、`POLICY_REJECTED`、`PR_ABANDONED` のいずれか |
| `CANCELLED` | Run が解放された(ADR 0009 の経路)。運用者が再投入するか、`SKIPPED` にする |
| `SKIPPED` | 運用者が飛ばした、または Goal の中止で残った |

タスクの遷移は、Run の状態を読んで反映する。**Run が正本**であり、タスクの状態は Run から導く射影である(ADR 0001)。タスク側から Run の状態を書き換えない。

Run の状態機械(`DevelopmentRunState`)は変えない。

#### 2.6 Run との対応づけ

- タスクの投入は、既存の `DevelopmentRunRepository.submitTask(principal, key, title, selfDev = true, maxActiveSelfDev)` を使う。Run の `taskId` は `selfdev:` で始まり、既存の同時 Run 上限と、既存の一覧・詳細 API の規則にそのまま従う。
- `principal` は、そのタスクを承認した運用者にする。`GET /api/selfdev/runs` は主体ごとに絞るので、運用者は自分の承認から出た Run を一覧で見られる。`autopilot:` などの別の主体は作らない。
- `run_id` を `selfdev_plan_tasks` に書くのは、`SUBMITTED` へ遷移するときだけ。書く前に Run ができても、同じ `idempotencyKey` で再投入すると既存の Run が返る(Replayed)。二重投入は起きない。
- Run の完了は既存の経路(`GitHubPrMergeAuthority` によるマージ検証、ADR 0008 の ⑩)に任せる。タスクは、その結果を読むだけである。

#### 2.7 lease と fencing の要否

- **Planner は fencing が要る。** 外部のモデル呼び出しは長く、その間に claim が切れて別のワーカーが同じ Goal を取ることがある。出力を書くときは、`version` と `claim_token` が一致したときだけ書く。一致しなければ捨て、監査に残す(`PLAN_DISCARDED_STALE`)。lease の既存の仕組み(`LeaseStore`、`Lease.generation`)は Run 向けで、Goal には使わず、同じ考え方を `claim_token` と `version` で実現する。
- **Dispatcher は lease が要らない。** 投入は DB の中だけで完結し、外部の呼び出しを含まない。二重投入は `idempotencyKey` の一意制約と、`QUEUED → SUBMITTING` の CAS で防ぐ。`SUBMITTING` のまま止まった行は、次の tick が同じ key で再投入して直す。

#### 2.8 実装時(PR A)の判断

PR A(V18、リポジトリ層、状態機械)で、本節の判断をした。いずれも ADR の本文と食い違う点、または本文に無い点であり、ここで記録する。

- **番号**: 初版の V16 は使用済み(`coding_attempt_usage`)のため、V18 とする。V17 は `task_body_and_reference`(#229)。
- **`selfdev_goals` に `resume_state` と `resume_reason` を足す**: §2.5 の「PAUSED は前の状態に戻す」を実現するには、一時停止の直前の状態を保存する必要がある。PAUSED の間だけ値を持つ。
- **再計画の遷移は `PLAN_PROPOSED → GOAL_SUBMITTED`**: §2.5 の `PLANNING` に戻すと、claim の無い `PLANNING` が生じる。`PLANNING` は「claim されている間」だけの状態とし、Planner が `GOAL_SUBMITTED` を claim して `PLANNING` に入る。
- **計画段階の `BLOCKED` の理由は `PLAN_INVALID` / `PLAN_BUDGET_HOLD` / `PLAN_CALL_FAILED`**: §2.5 の `BUDGET_HOLD` は、計画段階とディスパッチ段階の両方で使われていた。両方に同じ理由を使うと、計画段階で止まった Goal が、ディスパッチの自動解消(`GUARD_CLEARED`)で `RUNNING` へ進んでしまう。そのため計画段階は別の理由にした。`PLAN_CALL_FAILED` は PR B で足した(モデル呼び出しの失敗のうち、予算以外のもの。自動では再試行しない)。計画段階で `BLOCKED` になった Goal は、ディスパッチの解消では進めず、運用者の再計画(`BLOCKED → GOAL_SUBMITTED`、`REPLAN_REQUESTED`)だけで `GOAL_SUBMITTED` に戻る(U-16 の決定)。
- **タスクの終端は `COMPLETED` と `SKIPPED`**: §2.5 は Goal の終端だけを定めている。タスクでは、`COMPLETED` は PR のマージ済み、`SKIPPED` は運用者の判断で、どちらも再投入させない。`FAILED` / `CANCELLED` は、運用者の再投入(§5.4)で `QUEUED` に戻る。
- **依存の検査は repository の CAS の中で行う**: `QUEUED → SUBMITTING` の時点で、依存先の全てが `COMPLETED` であることを確かめる(§5.1)。Dispatcher を書く前に、この条件が破られないようにするため。
- **`REJECTED` の Plan は書かない(PR B)**: 検証に失敗した出力は行にせず、監査(件数とダイジェストのログ)に残すだけにする。`revision` は、計画が `PLAN_PROPOSED` として保存されたときだけ増える。したがって再試行は `revision` を消費せず、上限 3 は保存された Plan の数に対する上限になる。
- **オートパイロットの承認(PR B)**: `AUTO` のとき、§6.1 段階 2 の条件を満たすタスクだけ `QUEUED` で保存する(`docs/**`、`kivra-*/src/test/**`、`README.md` のみ、見積り `S`、3 件以下、denylist なし)。それ以外と denylist のタスクは `PENDING_APPROVAL`。`requires_approval` は、denylist に触れるか、段階 2 の条件を満たさないとき `true`。`requires_approval` はモデルの出力から読まず、計算で決める。**Goal は `PLAN_PROPOSED` のまま**にする。段階 2 は計画の承認を要する(§6.1)ため、`PLAN_PROPOSED → TASKS_QUEUED` は運用者の承認でだけ行う(PR C/D)。
- **モデルの固定(PR B)**: `Requirements.allowedModelIds`(既定 null)を gateway に足し、ルーティングで一致しないモデルを `MODEL_NOT_ALLOWED` として除外する。Planner は `claude-haiku-5-5` だけを許す。起動時に、そのモデルが registry にあり `ACTIVE` であることを検査する。
- **denylist の追加(PR B)**: Planner の検査だけ、`PatchPolicy` の既定 denylist に `**/db/migration/**`、`**/auth/**`、`.kivra/**` を足す(§4 の一覧のうち、既定に無いもの。U-6 の確定までの暫定)。PatchPolicy 本体の既定は変えない。
- **循環の検査**: 依存は「必ず前のタスクだけを指す」規則で検査する。この規則は循環を作れないため、別のトポロジカル順序の検査は置かない(§3 の検査 3 は、この規則に含まれる)。
- **Planner の入力(PR B で未実装)**: §3 の「対象リポジトリのファイルの一覧(パスだけ)」は PR B では渡さない。Goal の `title` と `body` だけを渡す。そのため、モデルが出すパスは推測になり、allowlist で拒否されることがある。ファイル一覧は後続の PR で足す(未決の点 U-17)。
- **監査の行(PR B で未実装)**: `audit_events` は Run 単位の `AuditRepository` の形のため、Goal の遷移は行に書かない。遷移は `state_reason` と、Planner のログ(ID と固定の理由コードだけ)に残る。Goal 単位の監査は後続の PR で決める(U-18)。
- **control 行は migration で 1 行入れる**: 行の無い状態を作らないため。`control_id = 1` の CHECK で 2 行目を拒否する。
- **日次カウンタは UTC の日付**: §U-3 の暫定どおり。`runs_today` と `day_utc` は、1 つの UPDATE の中で日付の変わり目を判定する。
- **連続失敗の計数は全体で 1 本**: §U-5 の暫定どおり。`breaker_open` は、計数が上限に達した UPDATE の中で立つ。成功は計数を 0 にするが、開いたブレーカーは閉じない(運用者の再開だけが閉じる、§5.4)。

#### 2.8b 実装時(PR E: Dispatcher の追加検査)の判断

PR E で、§2.9 の PR C への申し送り(1)〜(3)を実装した。V19 の後の追加で、マイグレーションは足さない。

- **投入の直前の Goal の検査**: `QUEUED → SUBMITTING` の CAS と同じトランザクションで、Goal の行をロック(`SELECT ... FOR UPDATE`。中止と一時停止も同じ行を更新する)して読み直す。Goal が `TASKS_QUEUED` / `RUNNING` でなければ投入しない(`GOAL_NOT_DISPATCHABLE`、ログには ID と理由コードだけ)。`SUBMITTING` のまま残ったタスクを再開するときも、同じ検査を行う(ロックは短い。その後に Run を作るまでの間は、§5.4 の「投入中のものには触れない」と同じ扱い)。
- **中止と投入の競合**: 中止は、未投入のタスク(`QUEUED`)の版を進めるので、Dispatcher の CAS が失敗する。Goal の検査は、一時停止のように、タスクの版を進めない操作への防御である。
- **投入の主体**: §2.6 の順序(タスク、Goal の承認者、Goal の作成者)。段階 2 の自動承認のタスクは、運用者が計画を承認していればその承認者、していなければ Goal の作成者の名で投入される。
- **全タスクが終端になった Goal**: `RUNNING` / `TASKS_QUEUED` / `BLOCKED` から、全タスクが `COMPLETED` / `FAILED` / `SKIPPED` になったとき、全部が `COMPLETED` なら `DONE`(`RUNNING` のみ、従来どおり)、そうでなければ `STOPPED` に `TASKS_INCOMPLETE` の理由で進める。`STOPPED` の辺の理由に `TASKS_INCOMPLETE` を足した(表と網羅テストを更新)。`FAILED` と `CANCELLED` は、§2.8 のとおり運用者が再投入できる状態だが、Goal を終わらせる判定では終端として数える(再投入の HTTP 操作は無く、Goal が `STOPPED` になった後に自動で投入されることは無い)。
- **終端化のしかた**: `STOPPED` を「中止」として使うのは、既存の状態の中で表すため(マイグレーションは足さない。`state_reason` は `VARCHAR(64)` で、新しい理由を足すのは Kotlin の列挙だけ)。
- **残る点(未決)**: 中止された Goal の `SUBMITTING` のタスクは、そのまま残る(§5.4)。運用者が見て判断する。

#### 2.8c 実装時(不具合の修正: サーキットブレーカーの再開と起動時の検査)の判断

PR E の E2E で見つかった 2 件の修正。マイグレーションは足さない。

- **CIRCUIT_OPEN の Goal の再開**: 運用者の再開(`reset-breaker`)は、制御行の計数と `breaker_open` を戻すだけだった。そのため、`BLOCKED`(`CIRCUIT_OPEN`)の Goal は、自動で解消する理由(`AUTO_CLEARABLE_REASONS`)に入らず、再開されなかった。修正として、Dispatcher の `resumeBlocked` が、`CIRCUIT_OPEN` の Goal を `OPERATOR_RESUMED` の理由で `RUNNING`(投入済みのタスクがあれば)か `TASKS_QUEUED` に戻す。`reset-breaker` の HTTP 経路は変えない。Dispatcher は、ブレーカーが閉じていて(`globalBlock` が無い)ときだけ戻すので、閉じていること自体が運用者の再開の結果である。遷移は版の CAS で、二重の再開は起きない。状態機械の辺は変えていない(`BLOCKED → RUNNING` と `TASKS_QUEUED` は、`OPERATOR_RESUMED` で `DISPATCH_BLOCK_REASONS` のすべてに許可されていた)。
- **再開しない理由**: 計画段階の理由(`PLAN_INVALID`、`PLAN_BUDGET_HOLD`、`PLAN_CALL_FAILED`)、`AUTO_SCOPE_EXCEEDED`、運用者の中止は、この経路で解消しない。`AUTO_SCOPE_EXCEEDED` は §5.4 で手動だが、この修正の対象は `CIRCUIT_OPEN` だけである。
- **起動時の検査(§5.3)**: `KIVRA_SELFDEV_AUTOPILOT_ENABLED=true` は、CLI 経路の費用の計上が有効なときだけ起動を許す。有効とは、`KIVRA_AGENT_API_ENABLED=true`、`KIVRA_FX_USD_JPY` と `KIVRA_FX_AS_OF` が設定されていること、`KIVRA_SELFDEV_MODEL_REGISTRY_PATH` が設定されていること。不足は、変数名を並べて拒否する(値は出さない)。FX の期限切れは拒否しない(既存の `FxExpiryWatcher` が警告する)。なお `SelfDevSettings` は、自己開発の有効化の時点で、この FX と registry を既に要求している。オートパイロットの検査は、その前提を自己完結させるための重ねである。
- **起動ログ**: 有効な上限(`maxActiveRuns`、`maxPendingPrs`、`maxConsecutiveFailures`、`maxRunsPerDay`、`budgetSharePercent`)を、数値だけで INFO に 1 行出す。`maxPendingPrs` が `maxActiveRuns` より大きいときは、PR の上限が同時 Run の上限で先に制限される旨の WARN を 1 行出す(§2.8 の E2E の発見)。

#### 2.8d 実装時(PR F: 段階 2 の差分の再検査)の判断

§6.2 の再検査を実装した。マイグレーションは足さない。

- **自動承認の印**: 段階 2 の自動承認のタスクは、`approved_by` が無い(計画の保存時に `QUEUED` で入るか、Dispatcher の `approveAuto` で `AUTO_APPROVED` の遷移をしたもの)。運用者の承認(`approveTask`)は必ず `approved_by` を入れる(V19)。そこで、「`run_id` があり、`approved_by` が無いタスク」を自動承認の Run とみなす。AUTO_APPROVED の履歴は、タスクの `state_reason` に残らない(投入の `QUEUED → SUBMITTING` が理由を null にする)ため、この判定は既存の列だけで行う。
- **遷移**: タスクに `SUBMITTED → PENDING_APPROVAL`(理由 `AUTO_SCOPE_EXCEEDED`)の辺を 1 本足した(`AutopilotStates.kt`、網羅テストを更新)。Goal は既存の `RUNNING` / `TASKS_QUEUED → BLOCKED`(`AUTO_SCOPE_EXCEEDED`、`DISPATCH_BLOCK_REASONS` に既に含む)を使う。`FAILED` にはしない(計数されてしまうため)。
- **順序(Run より先にタスクを動かす)**: 再検査の結果で、タスクと Goal を先に動かし、そのあと Run を `POLICY_REJECTED` で終える。逆の順序だと、Dispatcher が Run の結果を読んだときに、`POLICY_REJECTED` を失敗として数えてしまう。Run は正本のまま変えない(Run の状態は既存の遷移だけ)。タスクの状態は、再検査の判断を記録するためだけに、Run の終了の前に動く。この順序の残るリスクは後述。
- **計数(§5.2)**: `AUTO_SCOPE_EXCEEDED` は、連続失敗に**数えない**。範囲の超過は、モデルや実行の失敗ではなく、運用者の承認を要する状態であるため。タスクは `SUBMITTED` を出るので、Dispatcher の Run 結果の同期は、この Run を読まない(`recordRunOutcome` は呼ばれない)。テストで確かめる。
- **再承認と再開**: 運用者が、そのタスクを承認し直す(`PENDING_APPROVAL → QUEUED`、`OPERATOR_APPROVED`、`approved_by` を入れる)。承認されたタスクは再検査の対象外になる。Goal は、`approved_by` のある `QUEUED` のタスクがあるとき、`BLOCKED → RUNNING`(または `TASKS_QUEUED`)へ `OPERATOR_RESUMED` で戻る。他の運用者の操作は要らない。
- **投入キーの取り替え**: Run の作成は `(principal, idempotency_key)` で同一性を判定し、同じ鍵には既存の Run を返す(Replayed)。範囲外で終わったタスクをそのまま再投入すると、失敗した Run が返って、タスクが即座に失敗になる。そこで、`run_id` を持つタスクを承認するとき、鍵を `ap:{plan_id}:{ordinal}:a{attempts}` に取り替える(`uk_selfdev_plan_tasks_idem` の一意性は保たれる)。`run_id` が無いタスクの承認は、従来の鍵のまま。FAILED のタスクを戻す操作(`OPERATOR_REQUEUED`)は HTTP に無いため、この修正の対象外だが、同じ取り替えが要る(将来の申し送り)。
- **自動承認の再実行の防止**: 範囲外で戻されたタスクは、計画の見積り(docs、S)上は自動承認の条件に当たるままである。`approveAuto` は `AUTO_SCOPE_EXCEEDED` のタスクを飛ばす。飛ばさないと、戻したタスクが自動で承認され、同じ失敗を繰り返す。
- **ログ**: 範囲外の判定は `code=AUTO_SCOPE_EXCEEDED paths=<件数> lines=<行数>`、結果の記録は ID と固定コードだけを出す。パス、内容、例外のメッセージは出さない(テストで、パスと内容のマーカーがログに出ないことを確かめる)。
- **残るリスク(未決)**:
  1. Run の終了の前に、タスクを動かしている。その間に worker が落ちると、Run は `DEVELOPING` のまま残る。再試行では、同じ差分で再び範囲外と判定され、記録は冪等である(テストで確かめる)。ただし、その間に運用者が承認すると、その Run は承認済みとして扱われ、差分がコミットされうる。承認は運用者の判断なので許容するが、Run と承認の順序を DB で閉じる仕組みは無い。
  2. PatchPolicy が直接拒否する denylist のパス(例: `.github/**`、`kivra-*/src/main/resources/db/migration/**`)は、PatchPolicy の `POLICY_REJECTED` として扱われ、数える。再検査の範囲は PatchPolicy が受理したものだけである。コミットはされないため、安全側の動きである。
  3. 行数は、書き込む内容の行数である。既存の長い内容を短く書き換える変更は、削除行を数えないため、行数の上限では止まらない。ファイル数と経路の規則は効く。差分の正確な行数には、基準のコミットの内容が要る(未決)。
  4. 一時停止中、または中止された Goal の範囲外のタスクは、`BLOCKED` にならず `PENDING_APPROVAL` だけになる。Goal は動かないので、投入は起きない。

#### 2.9 実装時(PR D: UI と HTTP)の判断

PR D(V19、HTTP、画面)で、本節の判断をした。いずれも §1 と §4 の本文に沿い、食い違う点、または本文に無い点だけを記録する。

- **承認者の保存(V19)**: `selfdev_goals` と `selfdev_plan_tasks` に `approved_by`(VARCHAR(255)、認証された主体の owner key)と `approved_at`(DATETIME(6))を NULL 可で足す。expand のみ。PR C の申し送り(承認者がスキーマに無い)への対応。
- **承認の順序**: 計画の承認(`PLAN_PROPOSED → TASKS_QUEUED`)を Goal 単位で 1 回行い、その後にタスク単位の承認(`PENDING_APPROVAL → QUEUED`)を受け付ける。§2.5 の「1 件目の承認で遷移する」は、計画の承認に一本化した。タスクの承認は、Goal が `TASKS_QUEUED` / `RUNNING` / `BLOCKED` / `PAUSED` のときだけ、見送りは `PLAN_PROPOSED` でも可能。
- **古い Plan のタスクは操作できない**: タスクの Plan が、Goal の現在の revision で `PROPOSED` でなければ、承認も見送りも拒否する(再計画の後に古い Plan のタスクが投入されることを防ぐ)。
- **denylist のタスク(`requires_approval`)**: 計画の承認では QUEUED にならない。QUEUED にできるのは、そのタスク自身の承認だけである(状態機械の辺は `PENDING_APPROVAL → QUEUED` の `OPERATOR_APPROVED` の 1 本)。テストで、承認なしの経路が無いことを確かめる。
- **タスクの識別子(HTTP)**: `{planId}-{ordinal}`(ULID の 26 文字、ハイフン、番号)。§1.2 の `.../plan/tasks/{n}/approve` の形ではなく、§1 の `POST /api/selfdev/tasks/{id}/approve` に従う。
- **中止**: Goal を `STOPPED` にし、未投入のタスク(`PENDING_APPROVAL` / `QUEUED`)を `SKIPPED`(`GOAL_STOPPED`)にする。`SUBMITTING` 以降のタスクと、作成済みの Run と PR には触れない。Goal の行をロックしたうえで 1 つのトランザクションで行い、タスクの行が途中で動いたら全体を戻して 409 にする。
- **中止の理由(U-18)**: 1〜500 文字を必須で検査するが、保存もログへの出力もしない。Goal には監査の行が無いため(下記)。
- **再計画**: 計画段階の `BLOCKED`(U-16)だけを受け付ける。§1.2 の「Plan を作り直す」のうち、`PLAN_PROPOSED` からの再計画(§2.5 の辺)は HTTP に出していない。`revision` が上限(3)に達した Goal は拒否する(次の Plan が DB の制約に反するため)。
- **一時停止**: 全体の一時停止と再開のみ(`/api/selfdev/autopilot/pause` と `resume`)。Goal 単位の一時停止と再開(§1.2 の `.../pause`、`.../resume`)は未実装。
- **書き込みの版番号**: すべての Goal とタスクの変更は `{"version": n}` を要求する(読んだ版の CAS)。版が違えば 409、状態の遷移が無ければ 409、行が無ければ 404。
- **無効時の応答(U-12)**: 503 を採用する。ルートは Task API と同じ条件(`KIVRA_AUTH_MODE=oidc-jwt`)で登録し、認証(401、エージェントは 403)の後に 503 を返す。画面は、`GET /api/selfdev/autopilot` が 200 かつ `enabled: true` のときだけ、Goal の欄を出す。
- **エージェントの拒否**: オートパイロットが有効なときは `KIVRA_AGENT_REQUIRED_ROLE` が必須(無ければ起動に失敗)。`OidcJwtVerifier` は、このロールを持つトークンを、運用者ロールの有無にかかわらず 403(`AgentRefused`)にする。拒否ロールを渡さない既存の Task API の挙動は変えない。
- **本文の閲覧**: 一覧は本文を含まない。詳細は、認証された運用者にだけ返す。Task API と同じく、認証は運用者ロール(`admin-panel:admin`)で行うため、作成者と運用者の集合は同じになる。作成者だけに絞る区別は設けていない。
- **U-9(Goal 本文の保持と削除)**: **未決のまま**。本文は DB に保存し、削除の手段と保存期間は実装していない。
- **U-18(Goal 単位の監査)**: 監査の表(`audit_events`)は Run 単位のため、Goal の操作は構造化ログ(`net.kigawa.kivra.selfdev.autopilot.operator`)に残す。出すのは、操作の名前、Goal・Plan・タスクの ID、固定の理由コード、運用者の SHA-256 の先頭 12 文字(ダイジェスト)だけである。運用者の生の ID、本文、タイトル、中止の理由は出さない。Goal 単位の監査の表は未決のまま。
- **変更の範囲**: `OidcJwtVerifier` に、拒否ロールを任意で渡せるようにした(3 値の結果 `OperatorAuth`)。Goal、Plan の既存のリポジトリ(Planner と Dispatcher の書き込み)と、状態機械(`AutopilotStates.kt`)は変えていない。承認と中止の書き込みは、新しい `SelfDevOperatorRepository` にある。
- **PR C への申し送り**: (1) 中止は、未投入のタスクの版を進めるので、Dispatcher の `QUEUED → SUBMITTING` の CAS は失敗する。Dispatcher は、Goal が `STOPPED` / `PAUSED` でないことも投入の前に確かめる。(2) 承認は、タスクの版を進める。(3) 投入時の承認者は、タスクの `approved_by` を使う。


### 3. Planner(計画の作成)

- **呼び出し**: `ModelGateway` 経由だけ。モデルは Haiku 5.5(`claude-haiku-5-5`)に固定し、`KIVRA_SELFDEV_MODEL_REGISTRY_PATH` の registry に載っていることを起動時に検査する。ツールは渡さない。書き込み権限を持たせない。
- **予算ゲート**: 呼び出しの前に、最大出力トークン数から上限額を見積もり、予算(§5.3)から予約する。取れなければ `BLOCKED(BUDGET_HOLD)`。結果が返ったら確定する。返らなければ予約は解放しない(ADR 0003 の UNKNOWN_COST と同じ扱い)。
- **入力**: Goal の `title` と `body` を、区切り(開始と終了のマーカー)で囲んだデータとして渡す。加えて、対象リポジトリのファイルの一覧(パスだけ。内容は `KIVRA_SELFDEV_CONTEXT_PREFIXES` の範囲に限る)。秘密、環境変数、Run の本文、PR の本文は渡さない。
- **構造化出力のスキーマ**: 次の形だけを受け付ける。
  - `summary`: 文字列、500 文字以内
  - `tasks`: 1 件以上 **8 件以下**の配列。各要素は `title`(200 文字以内)、`body`(8000 文字以内)、`targetPaths`(1〜10 件の文字列)、`sizeEstimate`(`S` / `M` / `L`)、`dependsOn`(整数の配列)
  - 未知のフィールドは拒否する(既存の厳格なデコードと同じ)。
- **検証**(すべて通ったときだけ `PLAN_PROPOSED`):
  1. スキーマ、件数、長さ。
  2. **対象パスの検査**: 絶対パス、`..` を含む、バックスラッシュを含む、NUL を含む、空の文字列は拒否する。`allowlist`(例: `README.md`、`docs/**`、`kivra-*/src/**`)の外は拒否する。`denylist` に触れるものは拒否せず、`requires_approval = true` にする(§4)。
  3. **依存の検査**: `dependsOn` は必ず `ordinal` より小さい番号だけを指す。循環は、この規則で起きないが、トポロジカルソートでも確認する。
  4. **出力のサニタイズ**: 制御文字(改行とタブを除く)を取り除く。タイトルは改行を含めない。本文はそのまま保存するが、ログには出さない(§7)。
  5. 検証に失敗したら、同じ入力で **1 回だけ** 再試行する。それでも失敗なら `BLOCKED(PLAN_INVALID)`。
- **モデルの出力は命令として扱わない。** kivra は出力を実行しない。後段の CLI が読むのは `body` だけであり、その内容は既存の PatchPolicy(最終ゲート)で検査される。

### 4. 変更範囲の判定(allowlist、denylist、承認)

- **allowlist**(自動の対象になりうるパス): `README.md`、`docs/**`、`kivra-*/src/test/**`、`kivra-*/src/main/**`(既存の `ChangeRules` と `PatchPolicy` の規則に従う。両者の値は一致させる。ADR 0010 の未決の点)。
- **denylist**(触れたら必ず `requires_approval = true`。段階 3 でも自動にしない): `.github/**`、`**/Dockerfile*`、`gradle/**`、`**/*.gradle.kts`、`kivra-policy/**`、`**/auth/**`、`**/db/migration/**`、`.kivra/**`、秘密の名前を含むパス。一覧は未決の点 U-6 で確定する。
- `sizeEstimate` と `targetPaths` の数は **Planner の自己申告**であり、信頼しない。自動の判定(§6 の段階 2)には使うが、実際の変更は PR の差分で検査する(§6.2)。

### 5. Dispatcher(投入)

#### 5.1 投入の規則

- タスクは **番号順**に見て、`depends_on` の全てが `COMPLETED` のものだけを投入する。依存先は「PR が作られた」ではなく「マージされた」ことを条件にする。投入の基点は `main` であり、依存先の変更がマージされていなければ、後続の Run は依存先の変更を見られないためである。
- 投入の順序: `QUEUED` → `SUBMITTING`(CAS。`version` で取る)→ `submitTask`(`idempotencyKey = ap:{plan_id}:{ordinal}`、`selfDev = true`、主体は承認者)→ `SUBMITTED`(`run_id` を書く)。
- `submitTask` が `ActiveSelfDevLimitException` を投げたら、タスクは `QUEUED` に戻す。失敗としては数えない(サーキットブレーカーに入れない)。
- 同時に投入するのは、1 Goal につき 1 件だけ(段階 1 と 2)。段階 3 でも、同じ Goal のタスクは直列にする。
- 投入の主体(Run の owner、PR E で確定): タスクの `approved_by`、無ければ Goal の `approved_by`(計画の承認者)、どちらも無ければ Goal の作成者(`created_by`)。`autopilot` という主体は作らない(§2.6)。自動承認である事実は、`AUTO_APPROVED` の状態遷移で表す。

#### 5.2 ガード(投入の前に毎回確認する)

| ガード | 規則(暫定値) | 解消 | 理由コード |
|---|---|---|---|
| キルスイッチ | `KIVRA_SELFDEV_AUTOPILOT_ENABLED` が `true` でない | 設定を変えて再起動 | `AUTOPILOT_DISABLED`(投入しない。状態は変えない) |
| 全体の一時停止 | `selfdev_autopilot_control.paused` | UI の再開 | `OPERATOR_PAUSED`(投入しない。状態は変えない) |
| マージ待ち PR の上限 | 自己開発の PR のうち、マージ待ちのものが **3 件以上** | PR がマージまたは閉じられる | `PENDING_PR_LIMIT`(自動で解消) |
| 同時 Run 上限 | 既存の `KIVRA_SELFDEV_MAX_ACTIVE_RUNS`(既定 1)。オートパイロットは、この値を上げない | 既存の Run が終端になる | 状態は変えず、待つ |
| 1 日の Run 数 | `KIVRA_SELFDEV_AUTOPILOT_MAX_RUNS_PER_DAY`(既定 **5**、UTC の日付) | 翌日 | `DAILY_RUN_CAP`(自動で解消) |
| 月次の予算 | §5.3 | 次の月の期間 | `BUDGET_HOLD`(自動で解消) |
| サーキットブレーカー | 全体で、**連続 3 件**の失敗(`FAILED`、Run の `UNKNOWN`、`PR_ABANDONED`)。成功(`COMPLETED`)で 0 に戻る | 運用者の再開(計数を 0 に戻す) | `CIRCUIT_OPEN`(手動) |
| 範囲外の変更 | 段階 2 の自動の対象が、実際の差分で範囲を超えた(§6.2) | 運用者が、そのタスクを承認し直す(承認で Goal が再開する。§2.8d) | `AUTO_SCOPE_EXCEEDED`(手動) |
| 投入の前の Goal の状態 | Goal が `TASKS_QUEUED` / `RUNNING` でない(`STOPPED`、`PAUSED` など)。投入の直前に Goal の行をロックして読み直す(§2.8b、PR E) | 状態は変えず、投入しない | `GOAL_NOT_DISPATCHABLE`(ログのみ) |
| 承認 | `requires_approval` かつ未承認 | 運用者の承認 | 状態は `PENDING_APPROVAL` のまま |

「状態は変えず、待つ」の欄は、Goal を `BLOCKED` にしない。一時的な混雑で状態が揺れないようにするためである。

#### 5.3 予算

- オートパイロットの **月次の割当て**: `kivra.budget.monthlyLimitJpy`(既定 10,000 円)の一定割合(暫定 **50%**)。割当てを超えたら `BUDGET_HOLD`。全体の予算を超えないよう、既存の予算ゲート(ADR 0004 の `BudgetPort`)で予約する。
- CLI 経路の費用が計上される前提は、**#230 が必須**である。#230 が無い間は、オートパイロットの予算の割当てを有効にしない(起動時に検査する)。
- 1 タスクの上限は、ADR 0010 の `--max-budget-usd` と同じ値を使い、投入前に月次の残りと比べる。残りが足りなければ `BUDGET_HOLD`。

#### 5.4 再開の操作

- **自動で解消するもの**(滞留、日次上限、予算の期間): 次の tick で再評価される。運用者の操作は要らない。
- **手動のもの**(`CIRCUIT_OPEN`、`AUTO_SCOPE_EXCEEDED`): 運用者が理由を確認し、再開する。再開は連続失敗の計数を 0 に戻す(`CIRCUIT_OPEN` のとき)。原因が直っていないまま再開しても、再び 3 件で止まる。`AUTO_SCOPE_EXCEEDED` の再開は、そのタスクを運用者が承認し直すこと(§2.8d)。承認すると、タスクは新しい投入キーで投入され、Goal は再開する。
- **失敗したタスク**(`FAILED` / `CANCELLED`): 自動で再投入しない。運用者が `再投入`(`QUEUED` に戻す)か `SKIPPED` を選ぶ。自動の再投入は、同じ失敗の繰り返しを招くため採らない。
- **UNKNOWN の Run**: ADR 0009 の運用者の解放(`release` または `release-unbound-pr`)を使う。解放後、タスクは `CANCELLED` になり、運用者が上の再投入を選ぶ。
- **Goal の中止**: 残りのタスクを `SKIPPED` にし、既に作られた PR はそのまま残す。PR の扱い(閉じる、残す)は人が決める。kivra は PR を閉じず、マージもしない(ADR 0008、ADR 0009)。

### 6. 段階的な導入と昇格条件

#### 6.1 段階

- **段階 1(承認つき)**: Planner は Plan を作る。全タスクは `PENDING_APPROVAL`。運用者が UI でタスクを 1 件ずつ承認したものだけ、投入する。
- **段階 2(低リスクは自動)**: 次の全てを満たすタスクだけ、承認なしで投入する。
  - `targetPaths` が `docs/**` または `kivra-*/src/test/**` だけ、かつ `README.md`
  - `requires_approval = false`
  - `sizeEstimate = S`、かつ `targetPaths` が 3 件以下
  - その Plan 自体は運用者が承認済み(計画の承認は 1 回)
  それ以外のタスクは、段階 1 と同じく承認を待つ。
- **段階 3(原則自動)**: ガード(§5.2)の範囲で、承認なしで全て投入する。`requires_approval` のタスクは段階 3 でも承認を要する(§4)。マージは常に人が行う。

#### 6.2 段階 2 の検査(計画の自己申告を信頼しない)

段階 2 の自動の判定は計画の見積りで行うが、**実際の差分**で再検査する。Run が `PULL_REQUEST` に入ったとき、変更されたパスが段階 2 の範囲を超えていたら、Goal を `BLOCKED(AUTO_SCOPE_EXCEEDED)` にし、運用者に知らせる。PR は残し、マージは人が判断する。PatchPolicy は従来どおり最終ゲートである。

実装(PR F、§2.8d): 再検査は、CLI 経路で PatchPolicy が受理した後、コミットの前に行う。範囲外なら、コミットも PR 作成もしない。Run は既存の `POLICY_REJECTED` で終え、タスクは `PENDING_APPROVAL`(理由 `AUTO_SCOPE_EXCEEDED`)へ、Goal は `BLOCKED`(同じ理由)へ戻す。範囲の判定の条件は、§6.1 の段階 2 と同じ(docs、kivra テストソース、README だけ。denylist に触れない)に加え、変更パスが 3 件以下、変更行が 200 行以下である。運用者が承認したタスクは再検査の対象外である。

#### 6.3 昇格条件(暫定)

- **段階 1 → 2**: 承認したタスクのうち 10 件以上が `COMPLETED` になり、`POLICY_REJECTED` と範囲外の変更がゼロ。#230 が完了し、予算の計上が実機で確認できていること。運用者の判断で昇格する。
- **段階 2 → 3**: 段階 2 で 30 件以上の PR が作られ、マージされたものが 70% 以上、かつ `AUTO_SCOPE_EXCEEDED` と `CIRCUIT_OPEN` が 4 週間で 0 件。数値は未決の点 U-8 で確定する。
- いずれも、昇格は設定の変更(段階の値)で行い、コードの変更を伴わない。降格は、同じ設定を戻すだけでできる。

### 7. 監査と観測

#### 7.1 監査に記録するもの

監査(`audit_events`)には、次の情報だけを記録する。

- 主体(認証された運用者、または `system:autopilot`)、時刻、ID(`goal_id`、`plan_id`、`ordinal`、`run_id`)
- 状態の遷移(from、to)と固定の理由コード
- 件数(タスク数、パス数)、モデル id、トークン数、費用の数値、`output_sha256`
- 運用者の理由(中止と再開)の本文。監査の payload にだけ入る(ADR 0009 と同じ扱い)

#### 7.2 監査とログに記録しないもの

- Goal の `title` と `body`、Plan の `summary`、タスクの `title` と `body`
- 対象パスの文字列(件数だけ)
- モデルの生の出力、プロンプト、応答の本文
- 例外のメッセージ(クラス名だけ)、プロバイダーの詳細
- 認証の資格情報、秘密、環境変数の値

ログは固定の理由コードと ID だけを出す。既存の `SelfDevWorker` のログ方針(Run の本文や例外メッセージを出さない)と同じにする。

#### 7.3 観測

- ダッシュボードの `#autopilot-status` に、全体の状態(有効、一時停止、ブレーカー、マージ待ちの件数、当日の Run 数、予算の残り)を出す。
- 費用は、#230 の経路で計上された数値だけを表示する。推測の値を出さない。

### 8. 脅威分析

| 脅威 | 経路 | 対策 |
|---|---|---|
| Goal 本文によるインジェクション | 運用者の入力(うっかりを含む)が Planner の命令になる | 入力は UNTRUSTED のデータとして区切る。Planner はツールを持たない。出力はスキーマと §4 の検査を通す。kivra は出力を実行しない。CLI の変更は PatchPolicy が最終ゲートとして検査する。 |
| Planner の暴走(過剰な計画、危険なパス) | 出力が件数、サイズ、パスの上限を超える | タスク 8 件以下、パス 10 件以下、本文 8000 文字以内、allowlist、denylist は承認必須。検証失敗は 1 回だけ再試行して止める。 |
| 費用の暴走 | 失敗の繰り返し、Planner の再試行、再計画 | 月次の割当て、1 日の Run 数(既定 5)、Planner の再計画の上限(3 回)、最大出力トークンの固定、サーキットブレーカー(連続 3 件)。CLI 経路の費用は #230 の前提が必須。 |
| 承認の迂回 | 段階 2 の自動判定の悪用、計画の自己申告の偽り | 段階 2 は計画の承認を要する。実際の差分で再検査し(§6.2)、範囲外なら停止する。denylist は段階 3 でも承認を要する。 |
| 二重投入 | 再試行、クラッシュ後の再開、複数のワーカー | `idempotencyKey = ap:{plan_id}:{ordinal}` の一意制約。`QUEUED → SUBMITTING` の CAS。Planner の書き込みは `version` と `claim_token` で fencing する。 |
| PR の積み上がり | 生成が速く、レビューが追いつかない | マージ待ちの PR が 3 件以上で投入を止める。依存先のマージを待つ。 |
| 運用者以外の入力 | 認可の無い主体、エージェントの主体、GitHub Issue 経由 | 既存の OIDC と必須ロール。エージェントの主体は拒否する。GitHub Issue は入力にしない(§1)。CSRF は `requireJsonSameOrigin`。 |

### 9. 実装の分割と順序

前提: **#230**(CLI 経路の費用の計上)、**#229**(依頼文の本文)が満たされるまで、段階 1 も有効化しない。#231(reconcile の配線)と #222(E2E)は、段階 1 の有効化の前に必要とする。

1. **本 ADR と runbook の入口**(この PR)。
2. **V18 とリポジトリ層、状態機械**: 4 テーブル、遷移表の純粋関数(kivra-core)と、その単体テスト。
3. **Planner**: ModelGateway の経由、予算の予約、スキーマ、検証(§3)。fake モデルによるテスト。
4. **Dispatcher**: 投入の規則、CAS と idempotency、ガード(§5)、サーキットブレーカー、再開の操作。
5. **UI と HTTP**: Goal フォーム、Plan の一覧、承認、一時停止、中止(§1)。
6. **段階 2 の判定と差分の再検査**(§6.2)。段階 1 の運用で数値が揃ってから。
7. **E2E**(fake Planner で、Goal → Plan → 承認 → Run → PR 作成まで。#222 の fixture を使う)。

有効化は、段階 1 を既定 off のまま、運用者が設定で有効にする。段階 2 と 3 は、昇格条件(§6.3)を満たした後に、別の PR で設定を変える。

## 実装状況

2026-10-18 時点。PR A〜D は main に入っている。PR F(段階 2 の差分の再検査、§2.8d)は本 PR で実装し、未マージである。既定は無効(`KIVRA_SELFDEV_AUTOPILOT_ENABLED` が `false`)である。

| 範囲 | PR(main) | 内容 |
|---|---|---|
| 本 ADR の起草 | #234 | Proposed として起草 |
| PR A: 基盤 | #243 | V18(4 表。`selfdev_autopilot_control` の 1 行を含む)、リポジトリ層、状態機械(`kivra-core` の `AutopilotStates`) |
| PR B: Planner | #244 | §3 の検証、モデルの固定(`claude-haiku-5-5`)、予算の予約、計画段階の `BLOCKED`、段階 2 の判定(計画側の見積り) |
| PR C: Dispatcher | #245 | §5 の投入の規則とガード(マージ待ち PR の上限、日次の上限、ブレーカー、予算、依存)、Run からの状態の射影 |
| PR D: UI と HTTP | #250 | V19(`approved_by`、`approved_at`)、Goal フォーム、計画の承認、タスクの承認と見送り、中止、計画段階の再計画、全体の一時停止と再開、ブレーカーの再開、無効時の 503 |

**未実装(この ADR の範囲のうち、まだ無いもの)**

- ~~段階 2 の差分の再検査(§6.2)~~: PR F で実装した(§2.8d)。CLI 経路で、コミットの前に実際の差分を再検査する。範囲外は `AUTO_SCOPE_EXCEEDED` で止まる。API 経路はオートパイロットの対象外(起動時に CLI を要求する)。
- **再検査の限界(§2.8d)**: 変更行は、書き込む内容の行数と、削除するファイルのバイト数で数える。既存の内容のうち削除される行は、PatchSet が全文を持つため数えない。
- **Goal 本文の削除(U-9)**: 保存期間と削除の手段は未実装。
- **Goal 単位の監査(U-18)**: 構造化ログだけで、監査の表は無い。
- **Goal 単位の一時停止と再開(§1.2 の `.../pause`、`.../resume`)**: 全体の一時停止と再開だけ。また、`PLAN_PROPOSED` からの再計画は HTTP に出していない(計画段階の `BLOCKED` からの再計画だけ)。
- **Planner へのファイル一覧(U-17)**: PR B では渡していない。
- **dev での有効化と E2E(§9 の 7)**: 未実施。段階 1 の有効化は、#222 の前提と E2E の後に行う。
- **前提の #222(E2E の fixture)**: main に該当する PR が見当たらない(`git log origin/main` と `gh pr list` で確認)。§9 の前提のうち、#230(#236、#237、#242)、#229(#238、#239)、#231(#247、#248)は満たされている。

## 検討した代替案

- **A. GitHub Issue のラベルを入力にする**: 外部から書き込める経路が増え、インジェクションの入口になる。運用者の画面にも入力が無い。採らない。
- **B. Goal を自由形式で受けて、Planner が対象を決める(検証なし)**: 対象の範囲が見えず、PatchPolicy の手前で止める仕組みが無い。採らない。
- **C. 1 回の大きな Plan を、承認せずに全部投入する**: 費用と PR の積み上がりを止められない。段階 1 では採らない。段階 3 の一部として §5.2 のガードの下でのみ採る。
- **D. 計画の作成と投入を、1 つのワーカーで同期的に行う**: モデル呼び出しが長く、失敗の境界が曖昧になる。Planner と Dispatcher を分ける。
- **E. Dispatcher にも lease を使う**: DB の CAS と一意制約で足りる。外部の呼び出しを含まないため、lease の失効の扱いが増えるだけで得が無い。採らない(§2.7)。
- **F. タスクの状態を Run と別に正本として持つ**: Run と二重の正本になり、ADR 0001 に反する。タスクは Run から導く射影にする(§2.5)。
- **G. 自動の再投入**: 同じ失敗の繰り返しで費用とブレーカーを消費する。運用者の再投入を採る(§5.4)。

## 結果

- 運用者は、Goal を入力し、計画を承認するだけで、複数の Run を進められる。PR の作成とマージは、従来どおり人が行う。
- 費用は、月次の割当て、日次の上限、再計画の上限、サーキットブレーカーで抑える。ただし CLI 経路の費用は #230 が計上するまで、割当てを正確に測れない。
- 計画の正しさは保証しない。検証はスキーマ、範囲、依存の形を確かめるだけであり、タスクの中身が正しいかは CI と PR のレビューが確かめる。
- 段階 1 では、運用者の承認が必要なため、速度は上がりにくい。段階 2 の判定は、計画の見積りに頼るため、§6.2 の差分の再検査が要る。
- V18 は 4 つのテーブルを増やす。既存のテーブルと Run の状態機械は変えない。
- Goal の本文は DB に保存されるため、運用者の入力を消す手段が必要になる(未決の点 U-9)。

## 未決の点

以下は、ユーザーの確認が要るものである。各項目の暫定の既定値を括弧で示す。異論があれば、実装前に変更する。

- **U-1 承認の単位**: タスクごとに承認するか(暫定: タスクごと)。Plan 単位にすると、段階 1 の手間が減るが、denylist のタスクを別に扱う必要がある。
  - 決定: 計画の承認を Goal 単位で 1 回行い、その後にタスクごとの承認を行う。denylist のタスクは計画の承認では投入されず、タスクごとの承認でだけ投入される(PR #250、§2.9)。
- **U-2 月次の割当て**: オートパイロットに割り当てる割合(暫定: 50%、約 5,000 円)。#230 の完了後に実測して決める。
- **U-3 1 日の Run 数の上限と日付の境界**: 暫定 5 件、UTC の日付。JST の日付に揃えるかは要確認。
- **U-4 マージ待ち PR の上限**: 暫定 3 件(#233 と同じ)。
- **U-5 サーキットブレーカーの数え方**: 暫定は全体で連続 3 件。Goal ごとにするか、全体にするか。失敗の種類に `PR_ABANDONED` を含めるか。
- **U-6 denylist の一覧**: §4 の暫定の一覧で確定してよいか。特に `kivra-*/src/main/**` を自動の対象に含めてよいか。
- **U-7 依存の条件**: 依存先は「マージされたら」(暫定)。投入の基点が `main` であり、マージ前の PR に依存する後続の Run は依存先の変更を見られないため、「PR 作成で足りる」案は採らない。この点を確認する。
  - 決定: 依存先の全てが `COMPLETED`(Run が完了し、PR がマージされた)のときだけ投入する(PR #245、§2.8、`AutopilotDispatcher`)。
- **U-8 段階の昇格条件の数値**: §6.3 の 10 件、70%、30 件、4 週間は暫定。
- **U-9 Goal 本文の保持と削除**: 保存期間、削除の手段(運用者による削除を設けるか)、閲覧できる主体(投入者と運用者だけ)。
- **U-10 手動の投入との共存**: 既存の `/api/selfdev/tasks` と同じ同時 Run 上限を共有する(暫定)。手動の投入を優先させるかは要確認。
- **U-11 全体の一時停止の保存先**: 単一行のテーブル(§2.4、暫定)か、環境変数だけにするか。環境変数だけでは運用中に止められない。
- **U-12 Goal の受付の無効時の応答**: 無効時は 503 で受け付けない(暫定)。受け付けて `AUTOPILOT_DISABLED` で止める案もある。
  - 決定: 503 を採用する。認証(401、エージェントは 403)の後に 503 を返す(PR #250、§2.9)。
- **U-13 Planner の再計画と検証の回数**: 再計画は 3 回まで、検証の再試行は 1 回(暫定)。
- **U-14 Goal の本文の上限**: 8000 文字(暫定)。#229 の依頼文の本文の上限と揃える。
- **U-15 対象リポジトリの変更**: 段階 1 では画面から変えない(暫定)。複数リポジトリを扱う要件が出たら、別の ADR で扱う。
- **U-16 計画段階で止まった Goal の回復**: 決定済み(PR B)。計画段階の `BLOCKED` は、運用者の再計画(`BLOCKED → GOAL_SUBMITTED`、`REPLAN_REQUESTED`)でだけ `GOAL_SUBMITTED` に戻る。ディスパッチの解消では進めない。UI と HTTP の入口は PR D。
- **U-17 Planner へのファイル一覧**: §3 の入力のうち、対象リポジトリのパス一覧を渡すかどうか。PR B では渡さない(Goal だけを渡す)。渡すなら、パスだけで内容は渡さない、の方針を維持する。
- **U-18 Goal 単位の監査**: `audit_events` は Run 単位のため、Goal と Plan の遷移の監査の置き場所を決める。PR B では、遷移は行の `state_reason` とログに残るだけ。
  - 決定(暫定の実装): 運用者の操作は構造化ログ(`net.kigawa.kivra.selfdev.autopilot.operator`)で残す(PR #250、§2.9)。Goal 単位の監査の表は未決のまま。

## 見直しの条件

- #230 が完了せず、CLI 経路の費用が予算に計上されないまま、段階 1 の有効化が必要になったとき。この ADR の前提を改める。
- 段階 2 の差分の再検査で、範囲外の変更が 1 件でも自動で PR になったとき。段階 2 を止め、判定の方法を見直す。
- 費用の実測が、月次の割当ての 50% を大きく超えたとき。割当てと上限を見直す。
- 計画の品質(マージされた率)が低い状態が続いたとき。Planner の入力と検証を見直す。
