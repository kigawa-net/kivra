# ADR 0015: order を、Goal を周回させる長く生きる指示として定義し、終了は上位者の承認制にする(安全装置による一時停止は承認を待たない)

- ステータス: 提案(Proposed)。未決の点(U-1〜U-16)の決定を経てから、段階ごとの PR に分けて実装する。本 ADR は設計のみで、コード、設定、k8s、Keycloak は変えない
- 日付: 2026-10-19
- 関連: Issue #273(本 ADR の対象。要件と解釈)、#233(オートパイロット)、#229(依頼文の本文)、#230(CLI 経路の費用の計上)、#222(E2E fixture)、#231(reconcile の配線)、#181(MCP)。提案中の ADR 0014(低リスクの自動マージ。本 ADR はその内容に依存しない)。ADR 0001(Run の正本)、ADR 0003(予算と UNKNOWN_COST)、ADR 0004(予算の正本は BudgetPort)、ADR 0008(PR 作成までのループ)、ADR 0009(運用者による UNKNOWN の解放)、ADR 0010(CLI 経路)、ADR 0011(オートパイロット)、ADR 0012(実行イベントと停止)、ADR 0013(MCP)

## 背景

- ADR 0011 の Goal は、1 回の「計画 → タスク → Run → PR → マージ」で終わる。終端は DONE(全タスクが COMPLETED)と STOPPED(中止。または全タスクが終端になったが COMPLETED でなかった)である。周を重ねる概念は無い。
- 1 件の Goal の中で作り直せる Plan は 3 回までである(`selfdev_goals` の `CHECK (revision BETWEEN 0 AND 3)`、V18)。ループを 1 件の Goal の中で回すと、2 周目以降がこの上限に当たる。
- 運用者が求めているのは(#273)、order を定義して継続的に開発すること、order がループすること、ループを止めるときは上位者の承認をもらうこと、の 3 点である。
- 現在の止め方は、運用者 1 人で完結する。
  - `POST /api/selfdev/goals/{id}/stop` は、運用者の認証だけで受け付ける。理由は検査するが、保存しない(route のコメントによる)。
  - `POST /api/selfdev/autopilot/pause` は承認を要さない。保存するのは `paused_by` と `paused_at` だけで、理由は保存しない(route は主体だけを `setPaused` に渡している)。
- ループにすると、止め方が 2 種類必要になる。
  - (a) 周を止めて、後で再開できる。安全装置の自動の停止と、運用者の一時停止がこれに当たる。
  - (b) order を終わらせる。承認を要する。
  この 2 つを 1 つの状態に混ぜると、安全装置が承認待ちになって暴走を止められないか、承認なしで order が終わるかのどちらかになる。本 ADR は両者を別の状態として定義する。

## 決定

### 1. order と Goal の関係: order は新しい概念とし、Goal は「1 周」の単位として再利用する

検討した案(事実に基づく比較):

| 案 | 内容 | 評価 |
|---|---|---|
| A. Goal を拡張し、Goal の中で周回する | `selfdev_goals` に周回の列を足し、DONE の後に次の周を始める | 採らない。revision の上限(3)が 2 周目以降に当たる。DONE と STOPPED が「1 周の終わり」と「ループの終わり」を兼ねる。Dispatcher の終端判定(全タスクが終端なら DONE か STOPPED)を周回ごとに変える必要がある |
| B. order を新設し、Goal を 1 周として order に属させる(推奨) | `selfdev_orders` を新設する。`selfdev_goals` に `order_id`(NULL 可)と `cycle_no` を足す。周回の管理と order の状態は order 側に置く | 推奨。Goal の状態機械、Planner(`AutopilotPlanner`)、Dispatcher(`AutopilotDispatcher`)、Run の投入(`submitTask`)、PR の経路は変えない。Goal の revision の上限は 1 周ごとに効く |
| C. order が Plan を直接持つ(Goal を使わない) | Plan とタスクの表を order 用に複製する | 採らない。Planner と Dispatcher を複製することになり、既存のテストと CAS の仕組みが使えない |

案 B を採る。V21(expand のみ。現在の最新は V20 であることを確認済み)で次を追加する。

- 新しい表: `selfdev_orders`(§2.1)、`selfdev_order_stop_requests`(§4.2)、`selfdev_order_events`(§4.3。追記専用の監査)。
- 既存の表への列の追加(NULL 可): `selfdev_goals.order_id`、`selfdev_goals.cycle_no`、`selfdev_plans.fingerprint`(§3.2 の重複の検出)。
- 既存の状態機械(`GoalState`、`TaskState`、`DevelopmentRunState`)は変えない。

### 2. order の定義(データモデル)

#### 2.1 `selfdev_orders`

| 列 | 型(案) | 意味 |
|---|---|---|
| `order_id` | `CHAR(26)` PK | ULID |
| `title` | `VARCHAR(200)` | 一覧での呼び名。ログには出さない |
| `policy` | `TEXT`(上限 2,000 文字。値は実装で確定) | 方針の文。「何を良くし続けるか」。運用者の入力であり、UNTRUSTED のデータとして扱う(§8)。作成後は変えない(§2.6) |
| `target_repo` | `VARCHAR(200)` | 作成時の `KIVRA_SELFDEV_TARGET_REPO` の値。画面から変えられない(ADR 0011 U-15 と同じ) |
| `scope_profile` | `VARCHAR(32)`(固定の列挙) | 範囲の区分。例: `DOCS_AND_TESTS`(`docs/**`、`README.md`、`kivra-*/src/test/**`)、`ALLOWLIST_DEFAULT`(ADR 0011 §4 の allowlist)。自由記述のパスは入れない。どちらも既存の allowlist の部分集合であり、order で範囲を広げることはできない |
| `max_cycles` | `INT`(1〜10。既定 1) | 総周回数の上限 |
| `cycle_min_interval_seconds` | `INT`(既定 3600) | 周の開始の最小間隔 |
| `order_budget_jpy` | `DECIMAL(12,0)`(既定 5,000) | order の累計の費用の上限。ADR 0011 の月次割当(既定 50%)の範囲内に収まる値 |
| `cycle_budget_jpy` | `DECIMAL(10,0)`(既定 1,000) | 1 周の費用の上限 |
| `expires_at` | `DATETIME(6)` | 期限(作成から 14 日。U-12) |
| `state` | `VARCHAR(24)` | §2.4 の状態 |
| `state_reason` | `VARCHAR(64)` | 固定の理由コード(§5.2) |
| `cycles_started` | `INT` | 開始した周の数。`max_cycles` と比べる |
| `current_goal_id` | `CHAR(26)`、NULL 可 | 今の周の Goal |
| `consecutive_failed_cycles` | `INT` | order 単位で、連続して失敗した周の数 |
| `spent_jpy` | `DECIMAL(12,0)` | 累計の費用。#230 で計上された数値だけを入れる。推測の値は入れない |
| `next_cycle_at` | `DATETIME(6)`、NULL 可 | 次の周を始めてよい時刻 |
| `created_by` | `VARCHAR(255)` | 作成した運用者(owner key) |
| `approved_by` | `VARCHAR(255)`、NULL 可 | 作成の承認者(U-10 で承認が要る場合のみ) |
| `version` | `BIGINT` | 楽観的ロックと fencing |
| `created_at` / `updated_at` | `DATETIME(6)` | |

上位者は列として持たない。上位者は、Keycloak のロールの保持者である(§4.1)。order には指名しない。

#### 2.2 含めない項目

- 秘密と認証情報: GitHub App の秘密鍵、Anthropic の Workload Identity Federation の資格情報、Keycloak の client secret、OIDC と MCP のトークン、環境変数の値。order に書き込む経路を作らない。
- Run の本文と題名、PR の本文、Planner の生の出力、Goal の本文の複製。order は Goal の本文を持たない。Goal は、order の方針の文から作られるとき(§3.1 ①)だけ、方針の文を入力として受け取る。
- 運用者の生の ID(owner key)。ログと `selfdev_order_events` には、SHA-256 の先頭 12 文字だけを出す(ADR 0011 U-18 と同じ)。
- 費用の推定値。費用は #230 で計上された数値だけを入れる。

#### 2.3 機微な内容の扱い

- 保存する: `title`、`policy`、Goal の本文、停止と一時停止の理由(1〜500 文字)。いずれも DB にだけ置く。
- 出さない: アプリのログ、一覧 API、MCP の応答。一覧は `title` と `policy` を含めない。詳細は運用者にだけ返す(ADR 0011 §1.1 と同じ)。
- 監査: `selfdev_order_events` の payload には、停止と一時停止の理由の本文を入れてよい(ADR 0011 §7.1 と同じ扱い。監査の payload にだけ入る)。`title` と `policy` は入れない。
- ログは、固定のコード、ID、ダイジェストの先頭だけを出す(ADR 0011 §7.2 と同じ)。

#### 2.4 状態

`selfdev_orders.state`:

| 状態 | 意味 | 終端 |
|---|---|---|
| `ACTIVE` | 周回を続けてよい。ガード(§5.2)を通れば、次の周を始める | 否 |
| `PAUSED` | 周を始めない。`state_reason` を持つ。再開できる | 否 |
| `STOP_REQUESTED` | 停止を要求中。新しい周を始めない。上位者の承認を待つ | 否 |
| `STOPPED` | order を終えた。停止の承認でだけ入る | 終端 |

「完了」の状態は作らない。上限(周回数、期限、累計の費用)に達したときは `PAUSED` にする(§5.2 の 10)。order を終わらせるには、必ず停止の承認を経る。

全体の一時停止(`selfdev_autopilot_control.paused`)は、order の状態を変えない。周を止める効果だけを持つ。全体で 1 つの値である。

#### 2.5 遷移表

| # | 遷移 | 契機 | 主体 | ガード | 理由コード | 承認 |
|---|---|---|---|---|---|---|
| 1 | (なし) → `ACTIVE` | 作成(`POST /api/selfdev/orders`) | 運用者(`admin-panel:admin`) | オートパイロットが有効。`max_cycles` が 1 のとき、かつ既定を超える予算でないときは承認を要さない | — | 要否は U-10 |
| 2 | `ACTIVE` → `PAUSED` | 安全装置の発動(§5.2) | 安全装置(system) | 該当する条件 | §5.2 の理由 | 不要 |
| 3 | `ACTIVE` → `PAUSED` | 運用者の一時停止 | 運用者 | 理由 1〜500 文字(監査のみ) | `OPERATOR_PAUSED` | 不要 |
| 4 | `PAUSED` → `ACTIVE` | 運用者の再開 | 運用者 | 運用者の再開で解消する理由だけ(§5.2)。自動で解消する理由は解消済みであること | `OPERATOR_RESUMED` | 不要 |
| 5 | `PAUSED` → `ACTIVE` | 自動の解消 | system | 自動で解消する理由だけ(§5.2) | `GUARD_CLEARED` | 不要 |
| 6 | `ACTIVE` / `PAUSED` → `STOP_REQUESTED` | 停止の要求(§4.2) | 運用者(HTTP)、MCP(スコープ `kivra.selfdev.stop`) | 未決の停止要求が無いこと。理由 1〜500 文字 | `STOP_REQUESTED` | 要求には不要 |
| 7 | `STOP_REQUESTED` → `STOPPED` | 停止の承認(§4.2) | 上位者(要求者と別人) | 要求が未期限切れで未消費、かつ `order.version` が要求時と一致すること | `OPERATOR_STOPPED` | **必要** |
| 8 | `STOP_REQUESTED` → `PAUSED` | 却下、取り消し、期限切れ | 上位者(却下)、要求者か上位者(取り消し)、system(期限切れ) | — | `STOP_NOT_APPROVED` | 不要 |

制約:

- `ACTIVE` と `PAUSED` から `STOPPED` へ直接入る辺は無い。
- 遷移 8 の後は、自動で `ACTIVE` に戻さない。戻すには運用者の再開(遷移 4)が要る。承認が得られなかったことを、運用者が見て判断するためである。
- 遷移 7 は、承認と消費を 1 つの transaction で行う(§4.2)。`APPROVED` という中間の状態は作らない。

#### 2.6 上限と方針の変更

order の方針、範囲、上限は、作成後に変えない。承認の対象が途中で変わると(TOCTOU)、承認の意味が失われるためである。上限を上げたいときは、新しい order を作る(新しい order の作成は、U-10 の規則で承認が要る)。旧 order は、運用者が停止を要求して閉じる。承認つきの更新は採らない(U-11)。

### 3. ループの設計

#### 3.1 1 周の中身

| step | 内容 | 既存の仕組み |
|---|---|---|
| ① 開始 | order が `ACTIVE`、全体の一時停止が無い、`current_goal_id` が無い、`next_cycle_at` を過ぎている、`cycles_started < max_cycles`、期限内、の全てを満たすとき、Goal を作る(`order_id`、`cycle_no = cycles_started + 1`)。同じ transaction で `cycles_started` と `next_cycle_at` を進める | `SelfDevGoalRepository` |
| ② 計画 | Planner が Plan を作る。入力は、order の方針(区切りで囲んだデータ)と、前の周の固定コードと件数だけ。Run の本文、PR の本文、例外の文は渡さない | `AutopilotPlanner`、ADR 0011 §3 |
| ③ 承認 | 段階 2 の自動承認(ADR 0011 §6.1)の条件を満たすタスクだけ、自動で投入の対象にする。`requires_approval` のタスクがある周は、運用者が承認するか見送るまで、次へ進まない(「保留の周」) | `AutopilotDispatcher.approveAuto`、ADR 0011 §6.1 |
| ④ 投入 | 既存の Dispatcher が、番号順に、1 件ずつ、依存先の全てが COMPLETED のタスクだけ投入する | ADR 0011 §5.1 |
| ⑤ Run と PR | 既存の経路で Run と PR を作る。kivra は PR を閉じず、マージもしない | ADR 0008、ADR 0010 |
| ⑥ マージの確認 | 周のタスクが全て COMPLETED(PR がマージされ、マージの検証を通った)になるまで、次の周を始めない | `GitHubPrMergeAuthority`、ADR 0008 |
| ⑦ 評価 | 機械的な集計だけで判定する。結果コードは、`ALL_COMPLETED`、`TASKS_INCOMPLETE`、`RUN_FAILED`(FAILED、UNKNOWN、PR_ABANDONED を含む)の 3 つ。モデルの自己評価は、入力にも判定にも使わない | `Goal.state_reason` |
| ⑧ 次の周 | 評価が成功し、上限の内側なら ① に戻る(`cycle_min_interval_seconds` の後)。失敗なら `consecutive_failed_cycles` を増やす | §3.2、§5.2 |

#### 3.2 暴走を防ぐ仕組み

数値は提案であり、既定値は U-6 と U-12 で確定する。

| 仕組み | 規則(提案) | 効果と既存との関係 |
|---|---|---|
| 周の間隔 | 周の開始は、前の開始から 60 分以上あける(`cycle_min_interval_seconds`) | 費用と PR の作成の速さを抑える。Dispatcher の tick とは別に、order の `next_cycle_at` を見る |
| 1 日の周の数 | 1 order につき、UTC の 1 日に 3 周まで | 間隔の下限とは別に、1 日の上限を置く |
| 1 日の Run 数 | 既存の `KIVRA_SELFDEV_AUTOPILOT_MAX_RUNS_PER_DAY`(既定 5)をそのまま使う | order はこの値を上げられない |
| 1 周の規模 | タスクは 8 件まで(ADR 0011 §3)。1 周の費用は `cycle_budget_jpy`(既定 1,000 円) | 投入の前に、残りの 1 周の予算が次のタスクの上限(ADR 0010 の `--max-budget-usd` の値)以上であることを確かめる |
| 累計の上限 | `order_budget_jpy`(既定 5,000 円)。ADR 0011 の月次割当(既定 50%)の範囲内 | 両方を満たすときだけ投入する。既存の `autopilotBudgetAllows` は残す。予算の正本は ADR 0004 の BudgetPort のまま |
| 総周回数 | `max_cycles`(既定 1、上限 10) | 到達で `PAUSED(LIMIT_REACHED)` |
| 期限 | 作成から 14 日 | 到達で `PAUSED(EXPIRED)` |
| 連続失敗 | order で 2 周続けて失敗(§3.1 ⑦)したら `PAUSED(CYCLE_FAILURES)` | 既存の全体のブレーカー(連続 3 件、`CIRCUIT_OPEN`)は残す。order の方が先に止まる |
| 保留 PR の上限 | 既存の 3 件(全体)。order は増やさない | 上限に達したら待つ(状態は変えない)。72 時間続いたら `PAUSED(STALLED)` |
| 前の周の確認 | 次の周は、前の周のマージの確認(§3.1 ⑥)の後だけ | 成果を確かめないまま、周を増やさない |
| 重複の検出 | 計画の fingerprint は、正規化した title の集合と `target_paths` の集合(ソート済み)の SHA-256。直近 5 周の fingerprint と一致したとき、または同じ `target_paths` の集合が 3 周続いたとき、`PAUSED(DUPLICATE_PLAN)` | 完全一致と、同じパス集合だけを検出する。言い換えによる意味の重複は検出できない(未確認: 意味の重複を機械的に判定する方法は無い) |
| 同時の order | 1 件まで(U-7) | 既存の同時 Run の上限(既定 1)と合わせる |
| 周ごとの監査 | `selfdev_order_events` に、開始、計画の検証、投入、評価を記録する(§4.3) | 件数、ID、コード、費用の数値だけ |

### 4. 停止の承認

#### 4.1 誰が要求し、誰が承認するか

- **要求できる者**: 運用者(`admin-panel:admin`)。MCP からは、スコープ `kivra.selfdev.stop` を持つトークンでも要求できる(U-9)。要求は新しい周を止めるだけなので、危険は小さい。
- **承認できる者(上位者)**: 新しい Keycloak の client role(仮称 `kivra-selfdev:supervisor`、U-1)を持つ人間で、要求者と別人であること。
- **既存のロールで表せるか**: 表せない。
  - `admin-panel:admin` は運用者の要件であり、上位者とは区別できない。運用者が承認者にもなると、要求者と承認者が同じになる。
  - `kivra-agent` のロールは、既存の `AgentRefused`(403)で、オペレーターの経路から拒否される。承認者にはしない。
  - 新しいロールは、Keycloak の realm の設定の変更になる。これは kivra の PR だけでは完結せず、共有基盤(k8s-system 側の Keycloak の設定)の変更を伴う。また、現在の `OidcJwtVerifier` は 1 つの required role を検査する構造のため、承認の経路では追加のロール検査が要る。コードの変更は小さい。
- **未確認**: Keycloak の realm に、`admin-panel:admin` 以外の運用者ロールがあるかどうか。運用者が同じ人の 2 つのアカウントでない別人かどうか。上位者を別人として置けるかは、利用者の組織の事実であり、本 ADR では判断できない(U-1)。

#### 4.2 承認の流れ

`selfdev_order_stop_requests` の状態: `PENDING`、`REJECTED`、`CANCELLED`、`EXPIRED`、`CONSUMED`(承認と消費を同時に行うので、`APPROVED` は作らない)。

1. **要求**: `POST /api/selfdev/orders/{id}/stop-requests`(運用者)。要求の行を作り、order を `STOP_REQUESTED` にする。同じ order に未決の要求が 1 件でもあれば拒否する。`active_key` 列(`PENDING` のときだけ `order_id` を入れ、それ以外は NULL)に UNIQUE 制約を置いて、DB でも強制する。要求の行は、要求時の `order.version` と、要求の理由の SHA-256、要求者の owner key を持つ。
2. **承認待ち**: 上位者は、承認一覧で、要求の理由、要求者の表示名、要求時の order の版、周の状況と、要求の digest を見る。
3. **承認**: `POST /api/selfdev/stop-requests/{id}/approve`(上位者)。リクエストは digest を含む。サーバーは次を 1 つの transaction で行う。
   - (a) `requireJsonSameOrigin`(CSRF)。
   - (b) 上位者のロールがあり、要求者と別人であること(DB の CHECK と、アプリの検査の両方)。
   - (c) order の行を `FOR UPDATE` で取り、`order.version` が要求時と一致すること。
   - (d) 要求が `PENDING` で、期限内であること。
   - (e) 送られた digest が、サーバーの計算した digest と一致すること。digest は `SHA-256` of canonical JSON `{"action":"ORDER_STOP","order_id","order_version","request_id","reason_sha256"}`。
   
   全部を満たしたら、要求を `CONSUMED` にし、order を `STOPPED`(`OPERATOR_STOPPED`)にする。周の未投入のタスクは `SKIPPED`(`GOAL_STOPPED`)にする。投入済みの Run と PR には触れない(ADR 0011 §5.4 と同じ)。
4. **却下**: `POST /api/selfdev/stop-requests/{id}/reject`(上位者)。要求は `REJECTED`、order は `PAUSED(STOP_NOT_APPROVED)`。
5. **取り消し**: `POST /api/selfdev/stop-requests/{id}/cancel`(要求者か上位者)。要求は `CANCELLED`、order は `PAUSED(STOP_NOT_APPROVED)`。
6. **期限切れ**: 要求から 24 時間で `EXPIRED`(tick が判定する)。order は `PAUSED(STOP_NOT_APPROVED)`。

承認の再利用(リプレイ)は、`CONSUMED` が 1 回しか作れないこと(条件付き UPDATE。ADR 0011 の `ApprovalRepository.consume` と同じ方式)で防ぐ。

#### 4.3 記録(監査)

`selfdev_order_events`(追記専用。アプリは UPDATE と DELETE をしない):

- 列: `event_id`、`order_id`、`cycle_no`(NULL 可)、`kind`(`ORDER_CREATED`、`CYCLE_STARTED`、`PLAN_VALIDATED`、`TASKS_SUBMITTED`、`CYCLE_EVALUATED`、`PAUSED`、`RESUMED`、`STOP_REQUESTED`、`STOP_APPROVED`、`STOP_REJECTED`、`STOP_CANCELLED`、`STOP_EXPIRED`、`STOPPED`)、`from_state`、`to_state`、`reason_code`、`actor_digest`(要求者または操作者の owner key の SHA-256 の先頭 12 文字)、`approver_digest`(承認者)、`request_id`、`ids`(goal_id、plan_id、run_id の JSON。ID だけ)、`payload`(理由の本文だけ)、`occurred_at`。
- ハッシュの連鎖を付けるかどうかは U-15。
- 既存の `audit_events` は流用しない。`audit_events` は Run 単位の連鎖(`run_id` と `seq`)を検証する設計であり、order の遷移を混ぜると、その検証の意味が変わるおそれがある。混ぜない方が安全である(未確認: 混ぜた場合の既存の検証への影響は調べていない)。

### 5. 安全装置との関係(最重要)

#### 5.1 3 つの状態を区別する

- **一時停止(`PAUSED`)**: 周を始めない。order は生きている。主体は運用者か安全装置である。承認は要らない。解除の方法は、理由ごとに §5.2 で決める。
- **停止要求(`STOP_REQUESTED`)**: 周を始めない点は一時停止と同じ。ただし、解除できるのは、承認(`STOPPED`)か、却下、取り消し、期限切れ(`PAUSED`)だけである。
- **停止(`STOPPED`)**: order を終える終端。上位者の承認でだけ入る。

安全装置は、いつも一時停止を発動する。停止や承認を経由しない。承認制がかかるのは「終わらせる」だけであり、「止める」にはかからない。

#### 5.2 安全装置の表

| # | 安全装置 | 検出 | order への作用 | 理由コード | 解除 | 解除の主体 | 承認 |
|---|---|---|---|---|---|---|---|
| 1 | 全体の一時停止(緊急) | 運用者が `POST /api/selfdev/autopilot/pause` を呼ぶ | 全 order の周を止める。order の状態は変えない | `OPERATOR_PAUSED` | 全体の再開 | 運用者 | 不要 |
| 2 | キルスイッチ(環境変数) | `KIVRA_SELFDEV_AUTOPILOT_ENABLED=false` または `KIVRA_SELFDEV_ENABLED=false` で再起動 | ワーカーが動かない。API は 503。order の状態は変えない | `AUTOPILOT_DISABLED` | 有効化して起動し、行 14 の後に再開 | 運用者 | 不要 |
| 3 | 月次の割当ての枯渇 | `autopilotBudgetAllows` が偽 | 周を始めない。order を `PAUSED` にする | `BUDGET_HOLD` | 月が変わる、または割当てが戻る | system | 不要 |
| 4 | order の累計の上限 | `spent_jpy` が `order_budget_jpy` に達する | 周を始めない | `ORDER_BUDGET` | 上限を上げるには新しい order を作る(U-11) | — | 新しい order の承認 |
| 5 | 1 周の費用の上限 | 投入の前に、残りの 1 周の予算が次のタスクの上限に届かない | 次の投入を止める。order を `PAUSED` にする | `CYCLE_BUDGET` | 運用者の再開(原因の確認の後) | 運用者 | 不要 |
| 6 | 連続失敗(order 単位) | `consecutive_failed_cycles` が 2 以上 | 周を始めない | `CYCLE_FAILURES` | 運用者の再開(原因の確認の後) | 運用者 | 不要 |
| 7 | 連続失敗(全体、既存) | `breaker_open` | 周を始めない | `CIRCUIT_OPEN` | 既存の reset-breaker | 運用者 | 不要 |
| 8 | UNKNOWN の Run、UNKNOWN_COST | 既存の状態 | 周を始めない | `UNKNOWN_RUN` | ADR 0009 の運用者の解放 | 運用者 | 不要 |
| 9 | FX の期限切れ | `KIVRA_FX_AS_OF` が古く、費用を換算できない | 周を始めない | `FX_EXPIRED` | 為替の更新(runbook §4.8) | system | 不要 |
| 10 | 周数または期限の上限 | `cycles_started >= max_cycles`、または期限切れ | 周を始めない | `LIMIT_REACHED` / `EXPIRED` | 上限の変更は新しい order(U-11) | — | 新しい order の承認 |
| 11 | 重複 | fingerprint の一致(§3.2) | 周を始めない | `DUPLICATE_PLAN` | 運用者の再開(原因の確認の後) | 運用者 | 不要 |
| 12 | 保留 PR の上限 | 既存の 3 件 | 待つ。状態は変えない | `PENDING_PR_LIMIT` | PR がマージまたは閉じられる | system | 不要 |
| 13 | 滞留 | 行 12 が 72 時間続く | 周を始めない | `STALLED` | 保留 PR の解消(自動) | system | 不要 |
| 14 | 有効化された後の起動 | 起動時に `ACTIVE` の order がある | 全 `ACTIVE` を `PAUSED` にする(U-14) | `STARTUP_HOLD` | 運用者の再開 | 運用者 | 不要 |
| 15 | 停止要求の未承認 | 却下、取り消し、期限切れ | `PAUSED` に戻す | `STOP_NOT_APPROVED` | 運用者の再開 | 運用者 | 不要 |

補足:

- 行 3 は、ADR 0011 では Goal の「待ち」(状態は変えない)として定めている。order では、周を止めるだけでなく order の状態を `PAUSED` にする。`ACTIVE` のまま待っていると、画面が「動いている」と表示し続けるためである。
- 行 4、10 の上限は、ゆるめる方向の変更であり、既存の order の変更では行わない。新しい order を承認つきで作る(§2.6)。
- 解除の主体が system の行(3、9、12、13)だけが、運用者の操作なしに `ACTIVE` へ戻る。他の行は、運用者が原因を確認してから再開する。

#### 5.3 緊急時の止め方

- **緊急の一時停止(行 1)**: 運用者が即座に行う。承認は要らない。暴走のときに承認を待たせると、止まらなくなるためである。
- **キルスイッチ(行 2、環境変数)**: 最後の手段(break-glass)として位置づける。再起動が要り、数分かかる。承認制の例外である。この経路は order を終わらせない。有効化し直したときは、行 14 により全 `ACTIVE` が `PAUSED` になる。
- **終了(`STOPPED`)**: 緊急時も承認を要する。緊急の止め方は一時停止で足りるように設計する。終了を急ぐ必要があるときも、周は一時停止で止まっているので、承認が遅れても被害は増えない。
- **上位者が応答しないとき**: 周は止まったままである。終了が遅れるだけであり、被害は増えない。一時停止と終了を分けたことの利点はここにある。

#### 5.4 停止要求中の扱い(U-4)

- 推奨: 停止要求中は、新しい周を始めない。未投入のタスクも投入しない。投入の直前に order の状態を読み直す(ADR 0011 §2.8b の `GOAL_NOT_DISPATCHABLE` と同じ検査)。
- 進行中の Run と PR は既存の経路で進む。kivra は止めない。Run を取り消す手段は、現在の実装では、ADR 0009 の運用者の解放(`POST /api/admin/selfdev/runs/{id}/release`)だけである。ADR 0012 §6 の取消し要求は設計のみで、実装されていない(確認済み)。
- 理由: 承認を待つ間に新しい PR が増えると、承認の後に残る作業が増える。承認の期限(24 時間)の間は、周を始めないことが安全である。

### 6. API と画面(概要)

| 操作 | 経路 | 認可 | 備考 |
|---|---|---|---|
| order の作成 | `POST /api/selfdev/orders` | 運用者(`admin-panel:admin`) | `requireJsonSameOrigin`。Idempotency-Key を必須にする。無効時は 503 |
| 一覧 | `GET /api/selfdev/orders` | 運用者 | `title` と `policy` を含めない |
| 詳細 | `GET /api/selfdev/orders/{id}` | 運用者 | `policy` を含む。状態、理由、周の数、費用 |
| 周の状況 | `GET /api/selfdev/orders/{id}/cycles` | 運用者 | 周番号、結果コード、件数、PR の数、費用の数値。本文は含めない |
| 一時停止、再開 | `POST .../pause`、`POST .../resume` | 運用者 | 一時停止の理由は 1〜500 文字(監査のみ) |
| 停止の要求 | `POST /api/selfdev/orders/{id}/stop-requests` | 運用者 | 理由を入力する |
| 承認一覧 | `GET /api/selfdev/stop-requests?state=PENDING` | 上位者 | 理由と digest を表示する |
| 承認、却下 | `POST /api/selfdev/stop-requests/{id}/approve`、`.../reject` | 上位者(要求者と別人) | ブラウザの UI からだけ。MCP には出さない |
| 取り消し | `POST /api/selfdev/stop-requests/{id}/cancel` | 要求者か上位者 | |

画面: 既存の `/ui` の Goal の欄の隣に、order の欄を置く(ADR 0011 §1.1 と同じ作り)。一時停止の理由は、理由コードを固定の日本語の文言に変換して表示する。機械の文言や自由記述をそのまま出さない。承認一覧は、上位者にだけ表示する。

MCP(ADR 0013 の仕組みを使う):

- 既存の読み取り(`kivra_run_list`、`kivra_run_get`、スコープ `kivra.runs.read`)に、order の読み取り(`kivra_order_list`、`kivra_order_get`。スコープは `kivra.runs.read` を流用する)を足す。
- 停止の要求(`kivra_order_stop_request`、スコープ `kivra.selfdev.stop`)を足す。`kivra.selfdev.stop` は、既に `scopes_supported` に出ているが、それを使うツールはまだ無い(確認済み)。スコープの検査は、ツールごとに `McpRoutes` が行う(確認済み)。
- 作成、再開、一時停止、承認は MCP に出さない(U-9)。承認を MCP に出すと、AI クライアントが承認者になりうる。

### 7. 段階的な導入

#### 段階 0(前提。実装の前に満たす)

- ADR 0011 の段階 1 の dev での有効化と E2E(ADR 0011 §9 の 7)。
- #230(CLI 経路の費用の計上)は完了している(ADR 0011 の実装状況による)。
- #222(E2E fixture)は、ADR 0011 の実装状況では main に見当たらないとされている。本 ADR では再確認していない(未確認)。
- 上位者のロールが Keycloak にある(U-1)。

#### 段階 1(1 周の order と、停止の承認)

- 作るもの: `selfdev_orders`(`max_cycles` は 1 に固定)、一時停止と再開、停止の要求、上位者の承認、却下、取り消し、期限切れ、`selfdev_order_events`。周の自動の開始はまだ無い。1 周は運用者が作る。
- 進む条件(dev。テストと手動の確認):
  - 要求者と同じ主体による承認が、100% 拒否される(同じ `sub` と同じ owner key の場合を含めてテストする)。
  - 同じ承認を 2 回使う試みが、2 回目で拒否される。
  - 承認の期限切れで、order が `PAUSED(STOP_NOT_APPROVED)` になり、`ACTIVE` に自動で戻らない。
  - 停止の承認の後、投入済みの Run と PR に変更が無い。
- ロールバック: 新しい order を作らない(`KIVRA_SELFDEV_AUTOPILOT_ENABLED=false`)。全体の一時停止を使う。行は消さない(V21 は expand のみ)。

#### 段階 2(2〜3 周。マージの待ち、重複と失敗の検出)

- 作るもの: 周の自動の開始(§3.1)、`max_cycles` は 3 まで、重複の検出、連続失敗の検出、周の間隔。
- 進む条件: dev で 3 件以上の order、合計 10 周分を実行する。想定外の `PAUSED`(`DUPLICATE_PLAN`、`CYCLE_FAILURES`、`CYCLE_BUDGET`)が 0 件。order の費用の集計と #230 の実費の差が 10% 以内。`POLICY_REJECTED` を抜けて PR になったものが 0 件。
- ロールバック: 環境変数 `KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP`(新規。既定 1)を 1 に戻す。周を開始するときに `min(max_cycles, cap)` で検査する。コードの変更は要らない。

#### 段階 3(継続。最大 10 周)

- 進む条件: 4 週間の運用で、承認なしの `STOPPED` が 0 件、累計の予算の超過が 0 件、停止の承認の全件が `selfdev_order_events` に残っている。マージされた PR の割合が 70% 以上(ADR 0011 §6.3 と同じ。U-8 で確定する)。
- ロールバック: `KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP=1`。実行中の order は、次の周を始める時点で `PAUSED(LIMIT_REACHED)` になり、止まる。

### 8. 脅威の検討

| 脅威 | 経路 | 対策 |
|---|---|---|
| 自己承認 | 運用者が上位者のロールも持つ。または同じ人が 2 つのアカウントを使う | 要求者と承認者を別の主体にする(DB の CHECK とアプリの検査)。上位者のロールは運用者とは別に付与する(U-1)。2 つのアカウントの対策は運用で行う(未確認: Keycloak のアカウントの実態) |
| 承認のリプレイ | 同じ承認を繰り返す。古い承認を使う | 承認と消費を 1 つの transaction で行う。`CONSUMED` は 1 回だけ(条件付き UPDATE)。期限は 24 時間。承認は `request_id` と `order.version` に結ぶ |
| 承認待ちの間の状態の変化(TOCTOU) | 要求から承認までの間に order が変わる | 方針と上限は作成後に変えない(§2.6)。承認の時に order の行を `FOR UPDATE` で取り、版が違えば拒否する。承認待ちの間は新しい周を始めない(§5.4) |
| 承認の横取り(AI が承認する) | MCP のクライアントが承認の API を呼ぶ | 承認は MCP に出さない。承認の API は同一オリジンのブラウザの経路に限る。上位者のロールと `kivra-agent` のロールを併せ持つトークンは、既存の `AgentRefused` の規則で拒否する |
| 要求の連打による承認の妨害 | 運用者が要求を繰り返す | 未決の要求は 1 件まで(`active_key` の UNIQUE)。要求の頻度に上限を置く(提案: 1 分に 5 件まで。投入の規則と同じ) |
| 方針の文によるプロンプトインジェクション | 方針の文に「範囲を広げよ」などを書く | 方針は区切って渡すデータである。範囲は構造化された `scope_profile` で決まり、方針の文は範囲を変えられない。Planner はツールを持たない。PatchPolicy が最終ゲートである(ADR 0011 §8) |
| 方針の文の長期の影響 | 同じ方針が毎周 Planner に渡る | 方針は作成時に固定し、周の間に変えられない。周ごとの計画は、重複の検出(§3.2)と範囲の検査(ADR 0011 §4)で抑える。方針の文は監査にもログにも入れない |
| order の乗っ取り(他人の order の操作) | 別の運用者の order に停止や一時停止をかける | 操作は運用者のロールで行う。運用者が複数いる場合の閲覧と操作の範囲は U-12 で決める |
| 権限の拡大 | MCP のスコープで、書き込みを増やす | MCP は、読み取りと停止の要求だけ。作成、再開、承認は出さない。新しいスコープは追加しない(`kivra.selfdev.stop` は既存) |
| 停止要求を、安全装置の回避に使う | 停止の要求で、安全装置による一時停止を解く | 停止の要求は、一時停止を解除しない。却下、取り消し、期限切れは `PAUSED` に戻すだけである。安全装置の状態は、停止の要求の前後で変えない |
| 期限切れの後の自動の再開 | 期限切れで `ACTIVE` に戻る | `PAUSED` に戻す(§2.5 の遷移 8)。再開は運用者が行う |
| 上位者のアカウントの侵害 | 上位者のトークンで承認する | Keycloak で上位者に多要素認証を課す(未確認: 現在の realm の設定)。承認は同一オリジンのブラウザだけ。承認は監査に残る |
| ログと監査への漏えい | 方針、理由、タイトルがログに出る | §2.3 の扱い。テストで、マーカー文字列がログに出ないことを確認する(ADR 0011 §2.8b と同じ方法) |

### 9. 未決の点

以下は、ユーザーの判断が要る。各項目に推奨を 1 つ示す。

- **U-1 上位者の定義**。推奨: 新しい Keycloak の client role(仮称 `kivra-selfdev:supervisor`)を作り、運用者(`admin-panel:admin`)とは別の人に付与する。承認は、このロールを持ち、要求者と別人のときだけ行える。代替: 既存の運用者の中から選ぶ(ロールの追加は無し)。ただし運用者が 1 人だけなら、上位者を置けず、停止の承認は実行できない。その場合は、本 ADR の前提を見直す。
- **U-2 承認者と要求者を分けるか**。推奨: 分ける(DB と API で強制する)。分けないと承認が形式になる。
- **U-3 承認の期限**。推奨: 24 時間。期限切れは `PAUSED` に戻す。
- **U-4 停止要求中の扱い**。推奨: 新しい周を始めない。進行中の Run と PR は既存の経路で進める(§5.4)。
- **U-5 承認の方式**。推奨: kivra の UI の承認一覧だけにする。通知の仕組みは既存に無い。メールや Slack などの別のチャネルは、新しい仕組みになる。
- **U-6 周の間隔と 1 日の周の数**。推奨: 間隔は 60 分、1 日は 3 周まで。
- **U-7 同時の order の数**。推奨: 1 件まで。既存の同時 Run の上限(既定 1)と合わせる。
- **U-8 段階 3 への昇格の数値**。推奨: ADR 0011 §6.3 と同じ(4 週間、70%)。
- **U-9 MCP からの操作**。推奨: 読み取りと停止の要求だけにする。作成、再開、一時停止、承認は HTTP と UI だけに置く。
- **U-10 作成の承認**。推奨: `max_cycles` が 2 以上の order、または既定を超える予算(累計 5,000 円超、1 周 1,000 円超)の order は、上位者の承認を要する。`max_cycles` が 1 で既定内の order は承認を要しない。
- **U-11 上限の変更**。推奨: 作成後の変更は認めない。上限を上げるには、承認つきの新しい order を作る。承認つきの更新は採らない。
- **U-12 既定の上限と閲覧の範囲**。推奨: 総周回 10、期限 14 日、累計 5,000 円、1 周 1,000 円。運用者が複数いる場合、一覧は全運用者に見せ、方針と理由は作成者と上位者にだけ表示する。
- **U-13 上限到達の扱い**。推奨: `PAUSED(LIMIT_REACHED)` のままにし、終わらせるには停止の承認を経る。上限到達で自動的に `STOPPED` にする案は採らない。
- **U-14 有効化された後の起動**。推奨: 全 `ACTIVE` を `PAUSED(STARTUP_HOLD)` にし、運用者が再開するまで周を始めない。
- **U-15 監査の表**。推奨: `selfdev_order_events` を追記専用にし、ハッシュの連鎖は付けない。連鎖が要るなら、既存の `AuditRepository` の方式を真似る。
- **U-16 滞留と重複の閾値**。推奨: 保留 PR の待機は 72 時間。重複は、直近 5 周の fingerprint の一致、または同じ `target_paths` の集合が 3 周続くとき。

ADR 0011 の U-9(Goal の本文の保持と削除)は、本 ADR でも未決のまま残る。order の `policy` にも同じ問題がある。

### 10. 検討して却下した案

- **A. 停止に承認を要さず、運用者が即座に停止できる**: 要件に反する。
- **B. すべての停止(一時停止を含む)に承認を要求し、安全装置の発動も承認待ちにする**: 承認者が不在のとき、暴走を止められない。承認の期限が切れると、一時停止も解けなくなる。
- **C. 安全装置の発動も承認制にする**: B と同じ理由で採らない。
- **D. 上位者の承認を、既存の運用者ロール(`admin-panel:admin`)で代用する**: 要求者と承認者が同じになり、承認の意味が無くなる。
- **E. 承認を既存の `approvals` 表(V3)と `ApprovalRepository` に流用する**: 表は `run_id` の外部キーを持つ。order は Run に紐づかず、周の前にも停止を要求できる必要がある。`requester_agent_id` は Agent の概念である。`ApprovalRepository` は、HTTP からも `HumanAssertionVerifier` の実装からも呼ばれていない(確認済み)。仕組み(四つの目の CHECK、単回の消費、期限)は真似るが、表は流用しない。
- **F. Goal を延命して周を回す(案 A)**: revision の上限と終端の意味が衝突する。
- **G. 周の評価を LLM に任せ、次の計画の可否を決める**: 自己評価がゲートになる。機械的な集計だけにする。
- **H. 上限に達したら `STOPPED`(完了扱い)にする**: 終わらせるには承認が要るという要件に反する。
- **I. 却下、取り消し、期限切れの後に自動で `ACTIVE` に戻す**: 停止を止めたという運用者の判断が、知らないうちに覆る。`PAUSED` に戻す。
- **J. 方針と上限を、作成後に変更できるようにする**: 承認の対象が途中で変わる(TOCTOU)。新しい order を作る。
- **K. 承認を MCP に出す**: AI クライアントが承認者になりうる。

### 11. 結果

- 運用者は、方針と上限を持つ order を作り、周を回せる。PR のマージは従来どおり人が行う。
- 終了には上位者の承認が要るため、終わらせるまでの速さは落ちる。止めることは速いままである。
- 安全装置は、承認を待たずに周を止める。終了だけが承認を要する。状態は 2 種類に分かれる。
- 表と遷移が増える(新しい表 3 つ、列の追加 3 つ)。V21 は expand のみ。
- order の上限の多くは、#230 の費用の数値に依存する。#230 の計上が実機で確かめられるまで、order の累計の上限は効かないものとして扱う。
- 上位者のロールの追加は、共有基盤(Keycloak)の変更を伴う。

### 12. 確認できなかったこと(未確認)

- Keycloak の realm にある運用者のロールの全体と、上位者として使える別の人間の有無。
- 上位者への多要素認証の有無。
- #222(E2E fixture)が main にあるか。ADR 0011 の実装状況の記述に従い、本 ADR では再確認していない。
- 1 周の費用が #230 の計上でどれだけ正確か(実測は行っていない)。
- 言い換えによる意味の重複を、機械的に検出する方法。
- ADR 0014 の内容(提案中で main に無い。本 ADR は依存しない)。

### 13. 見直しの条件

- 運用者が 1 人だけで、上位者を置けないと分かったとき。停止の承認の仕組みを見直す。
- 承認なしで周が 1 件でも増えたとき、または承認なしの `STOPPED` が 1 件でもあったとき。
- order の費用の集計が、実費と 10% を超えて食い違うとき。
- 同じ内容の計画が、重複の検出をすり抜けて 2 周以上続いたとき。
- 安全装置による一時停止が、承認を待たされて効かなかったとき。
