# ADR 0023: プロジェクトチャットから運用者がボタンで操作する(段階 1: 規則に基づく提案、LLM なし)

- ステータス: 提案(Proposed)。運用者の承認待ち。承認まで実装の段階 2 以降は始めない(段階 1 の実装は本 ADR の範囲で完了している)
- 日付: 2026-11-12
- 関連: ADR 0003(UNKNOWN の費用は、監査された運用者の操作で解放する)、ADR 0013(MCP)、ADR 0015(order の連続ループ。§4.1 の承認者、§4.6 の AI の制限、2026-11-11 追補の周の放棄)、ADR 0020(担当と権限)、ADR 0021(LLM による停止要求の承認)、ADR 0022(プロジェクトのスコープ)。V55(プロジェクトチャット)、V56(本 ADR の提案の表)

## 背景

- プロジェクトのチャット(V55)は、運用者がメッセージを書くと Task として kivra Agent に投入する入口である。画面の操作(周の開始、周の放棄)は、チャットからは行えない。
- 運用者は、チャットで「次の周を始めて」「止まっている周を閉じて」と書きたい。ただし、AI が勝手に操作することは決めていない。
- 運用者の決定: AI は後から提案する。運用者が確定する。段階 1 は規則に基づく提案だけを行い、LLM は呼ばない。

## 決定

### 1. AI は実行しない

- チャットは操作を実行しない。提案は `project_chat_actions` の行であり、運用者の確定(`confirm`)で初めて実行される。
- AI の主体(`KIVRA_SELFDEV_AI_PRINCIPAL`)は、提案の確定も却下もできない。提案の一覧も、AI には返さない(ORDER_MANAGE を持つ運用者にだけ返す)。
- 段階 3 で AI が提案を書くようになっても、AI が確定・却下することは無い(§6)。

### 2. 提案は、サーバーの許可リストのコードだけ

- コードは `kivra-core` の `ChatActionCode` の列挙だけ(段階 1 は `ORDER_START_CYCLE` と `ORDER_ABANDON_CYCLE`)。DB の `action_code` は列挙の名前を入れ、自由な文字列を入れない。
- ラベル(「周を開始する」など)は、サーバーが列挙に持つ固定文言を返す。ラベルは要求の値、AI の文章、チャットの本文から作らない。
- 提案の対象は、プロジェクトの**ルート** order だけ。子 order は、この段階では提案しない。

#### 提案の規則(段階 1)

- `ORDER_START_CYCLE`: 次のすべてを満たす。ACTIVE、期限内、進行中の周が無い(`current_goal_id` が NULL)、`cycles_started < max_cycles`。**連続失敗の数は条件にしない**: `CYCLE_FAILURE_LIMIT` は自動の開始を止めるだけで、運用者の手動の開始は、これまでどおり許す(ADR 0015 §3.2 と、REST の開始の規則を変えない)。
- `ORDER_ABANDON_CYCLE`: ACTIVE で、進行中の周があり、その周が「止まっている」(下の規則)。PAUSED の order には放棄の提案をしない(画面の放棄ボタンと同じ条件)。
- 提案の版(`expected_version`)は、その時点の order の `version`。

#### 止まっている周の規則(保守的)

周の根の Task(`current_goal_id`)が自分で終わっておらず、その子の Task(`parent_task_id` が根)のうち、終わっていないものが**すべて** QUEUED で、かつ**それぞれが** FAILED または STOPPED の依存を持つ場合だけ、止まっているとみなす(`kivra-core` の `isCycleStuck`)。

- 子が 1 つでも RUNNING・SUBMITTED・BLOCKED・PLANNING などの状態なら、止まっていない。
- 子がいない、または全部が終わっていれば、止まっていない(評価が閉じる)。
- 規則は、止まった周を見逃すことはあっても、動いている周を放棄の対象にはしない。
- 既知の限界: 依存の先が SKIPPED や不明な ID の場合は、止まっているとみなさない。根 Task の状態(PLANNING など)は見ない。段階 2 以降で見直す。

### 3. 提案の寿命

- 提案は `PROPOSED` で作られる。`PROPOSED` は、同じ (プロジェクト, コード, 対象) で 1 件だけ(V56 の `active_key` の UNIQUE)。
- 提案の読み取り(`GET /api/projects/{id}/chat`、運用者だけ)が、規則を読み直す。規則から外れた `PROPOSED` は `EXPIRED`(結果コードは `VERSION_MOVED` か `NO_LONGER_ELIGIBLE`)になる。版が動いた order の提案は、新しい版で作り直す。
- 同じ (プロジェクト, コード, 対象, 版) の行が、どの状態でも既にあれば、再提案しない。`FAILED`・`DISMISSED`・`EXECUTED` の後で、同じ版の提案が再び出ることは無い。
- 提案の作成は、読み取りの副作用として行われる。ただし、提案の一覧は運用者にだけ返り、規則の失敗は例外の種類だけを記録して、チャットの読み取りは成功させる。

### 4. 確定と却下

- `POST /api/projects/{id}/chat/actions/{actionId}/confirm` と `.../dismiss`。本文は空か `{}`(サーバーが読むのは action ID だけ)。
- 権限: `Permission.ORDER_MANAGE`(対応する REST route と同じ)。AI の主体は、本文を読む前に 403 で拒否する。プロジェクトの読み取り範囲の外は 404(チャットの読み取りと同じ)。`requireJsonSameOrigin`、機能が無効なら 503。
- 確定は、提案の行の `code`・`target_id`・`expected_version` だけで実行する。実行は REST route と**同じ関数**(`SelfDevOrderRouteDeps.startCycleAsOperator`、`abandonCycleAsOperator`)で、REST route もこの関数を呼ぶ。ロジックは二重にしない。
- 対象の order が、提案のプロジェクトに属さなければ `ORDER_NOT_FOUND`(実行しない)。
- 放棄の理由は、サーバーの固定文言(「confirmed from the project chat」)。チャットの本文は監査に入らない。
- 結果は固定コードで行に残る(`result_code`)。版が違えば `EXPIRED`(`STALE_VERSION`)。拒否は `FAILED` と、リポジトリの拒否理由の名前(`OrderConflict` の名前)。自動の再試行は無い。
- 既に解決された行への確定・却下は 409(`ACTION_RESOLVED`)。二重に実行されない(`state = 'PROPOSED'` の条件付き更新)。
- 監査: 既存の運用者の監査行(`order operator action: ...`)と、新しい行 `project chat action: action=... actor=<operatorTag> actionId=... result=...`。ID と固定コードだけを記録し、本文やラベルは記録しない。

### 5. 画面

- チャットの節に、提案をカードで出す。カードには、サーバーのラベルと「確定」「却下」。確定は確認ダイアログ(実行する内容を先に書く)の後。却下は即時。結果は、サーバーの固定コードから作る文言で出す(未知のコードは、コードを表示せず、汎用の文言)。

### 6. 後の段階(この ADR では実装しない)

- **段階 2**: Task の承認・却下(`PENDING_APPROVAL` の Task)を提案に加える。コードを追加するだけで、同じ確定の経路を使う。
- **段階 3**: LLM が応答と提案を書く。出力は厳密な JSON で、提案は許可リストのコードだけを受け付ける。ラベルは引き続きサーバーが作る。LLM の呼び出しは予算台帳の用途(purpose)を持ち、プランナーと同じ注入の防御(入力の区切り、出力の検証)を使う。
- **段階 4**: UNPRICED の費用の確認(ADR 0003・ADR 0010 追補 4)。確認には、予約の全ての明細と、確定時に表示する必須の理由が要る。AI は提案だけができ、自動で押すことは無い。ADR 0003 は変えない。

## 結果

- 良い点: 操作の経路が REST と同じ関数になり、権限・監査・版の検査が重複しない。AI は提案の一覧も確定も見えない。
- リスク: 提案の作成が読み取りの副作用になる(書き込みが GET に乗る)。読み取りは運用者だけがトリガーし、規則は冪等。
- リスク: 止まっている周の規則は保守的で、見逃すことがある。見逃しの場合、運用者は既存の放棄ボタンを使う。
- リスク: 開始の提案は、予算や自律実行の停止で拒否されうる(`ORDER_BUDGET`、`AUTOPILOT_PAUSED`)。提案は見えるが、確定すると `FAILED` になる。提案の時点では予算を見ない(確定の検査に任せる)。

## 運用

手順は `docs/runbook-selfdev.md` の「12. プロジェクトチャットの提案(ADR 0023)」を参照。

## 追補 2026-11-12: TASK_REPLAN(段階 1 の第 3 のコード)

目標ページの再計画ボタンを、チャットからも押せるようにする。周の根 Task が、計画の失敗(`PLAN_INVALID`・`PLAN_BUDGET_HOLD`・`PLAN_CALL_FAILED`)で BLOCKED のとき、ACTIVE なルート order の提案として `TASK_REPLAN` を出す。段階 1 の範囲のまま(LLM は呼ばない。ラベルは固定文言)。

- **対象の種類**: `ChatActionCode.targetKind`(`ORDER` か `TASK`)で決める。`TASK_REPLAN` の `target_id` は根 Task の ID、`expected_version` は Task の版。既存の 2 つは order の ID と order の版のまま。V56 の表は変えない(移行は無い。`action_code` は自由な VARCHAR(64) で、CHECK は無い)。版の動きによる失効は、対象の種類で版を読む先を選ぶ(order の版か、Task の版)。
- **規則(純粋関数)**: `isReplannable(task)` = 状態が BLOCKED かつ理由が `PLAN_BLOCK_REASONS` の要素。目標ページの `canReplanGoal` と同じ集合。REST のルート自体は BLOCKED なら何でも受けるが、チャットは計画の失敗だけを提案する。
- **権限は、コードごと**(§4 の修正): 確定・却下の検査は、行のコードの権限で行う。`ORDER_START_CYCLE`・`ORDER_ABANDON_CYCLE` は ORDER_MANAGE、`TASK_REPLAN` は GOAL_WRITE(REST の再計画ルートと同じ)。どちらも、プロジェクトの範囲(`checkInProject`)で検査する。未知のコードは従来どおり ORDER_MANAGE。AI の主体は、どのコードでも 403。
- **表示も、コードごと**: チャットの読み取りは、そのコードの権限を持つ運用者にだけ、そのコードの提案を返す。提案の作成(規則の読み直し)は、いずれかのコードの権限を持つ運用者の読み取りで行う。
- **確定の操作**: `SelfDevGoalRoutes.kt` の `SelfDevOperatorRepository.replanAsOperator(taskId, version, principal)` を、REST の再計画ルートとチャットの確定が共有する(遷移と監査行 `autopilot operator action: action=REPLAN` は同じ)。チャットは、対象の Task が提案のプロジェクトに属さなければ `TASK_NOT_FOUND`(実行しない)。版が違えば `EXPIRED`(`STALE_VERSION`)。遷移の拒否は、まとめて `FAILED`(`TASK_NOT_REPLANNABLE`)。再試行はしない。
- **確認ダイアログ**: 再計画は Planner の呼び出しを起こすので、その旨(少額の予算を使う)を確定前に書く。
- **既知の限界**: 権限の表(ADR 0020)には、ORDER_MANAGE を持ち GOAL_WRITE を持たない役割が無い。したがって、現在の役割の組み合わせでは、コードごとの差は表示と確定の検査の関数(`ChatActionCode.permission()`)の単体テストでだけ確かめている。役割の表を変えれば、同じ検査がそのまま効く。

## 追補 2026-11-12(段階 2): Task の承認・見送り

承認待ちの Task(`PENDING_APPROVAL`)を、目標ページの承認・見送りボタンと同じ操作で、チャットからも確定できるようにする。段階 2 の範囲のまま(LLM は呼ばない。ラベルは固定文言)。

- **対象と規則**: 進行中の周の根 Task(ACTIVE なルート order の `current_goal_id`)の子 Task(`parent_task_id` が根)のうち、`PENDING_APPROVAL` で、計画の親でない(`revision = 0`。目標ページの「このタスクを承認」の条件と同じ。`isApprovable`)もの。子 1 件に、`TASK_APPROVE` と `TASK_REJECT` の 2 件の提案が出る。対象は子 Task の ID、版は子 Task の `version`。
- **代わりの関係**: 同じ Task の 2 件は排他。片方が `EXECUTED`・`FAILED`・`EXPIRED` で解決されると、もう片方は `EXPIRED`(`SIBLING_RESOLVED`)になる。遷移が拒否された(`FAILED`)ときは版が動かないため、版の動きによる失効だけでは足りない。そのため解決の時に明示的に閉じる。「提案を閉じる」(`DISMISSED`)は、もう片方を閉じない。Task の版が動いた場合(目標ページでの承認など)は、次の読み取りで両方とも `VERSION_MOVED` になる。Task が承認待ちでなくなり版が同じ場合は `NO_LONGER_ELIGIBLE`。
- **確定**: `approveAsOperator` / `rejectAsOperator`(`SelfDevGoalRoutes.kt`)を、REST の `POST /api/selfdev/tasks/{id}/approve` と `/reject` と、チャットの確定が共有する。遷移と監査行(`autopilot operator action: action=TASK_APPROVED|TASK_REJECTED`)は同じ。権限は `GOAL_WRITE`(REST と同じ)で、プロジェクトの範囲で検査する。AI の主体は 403。対象の Task が提案のプロジェクトに属さなければ `TASK_NOT_FOUND`(実行しない)。版が違えば `EXPIRED`(`STALE_VERSION`)。遷移の拒否は、承認が `FAILED`(`TASK_NOT_APPROVABLE`)、見送りが `FAILED`(`TASK_NOT_REJECTABLE`)。再試行は無い。
- **何を承認するかが分かる表示**: 承認の提案には、チャットの読み取り時に `selfdev_tasks` から読んだ次の 2 つを `detail` として付ける(提案の行には保存しない)。
  - `taskTitle`: Task の題名。planner が書いた文章なので、MCP の `safeTaskTitle`(200 コードポイントまで。制御文字と双方向制御文字を含めば固定の印 `[unsafe title omitted]`)を通す。クライアントは、AI が書いた文章としてプレーンテキストで出し、指示としては扱わない。
  - `reasonText`: 状態理由の固定文言(`approvalReasonText`)。`AUTO_SCOPE_EXCEEDED` は「自動実行の範囲を超える変更があったため、承認が必要です」、理由が無いものは「この Task は実行前に承認が必要です」。
  - Task の本文(body)と、対象のパスは返さない。ラベルには題名を入れない。
- **確認ダイアログ**: 承認は「Run が始まり、完了すると PR が作られる。PR は人がレビューしてマージするまで反映されない」を、見送りは「Task は見送られ(スキップ)、実行されない。この Task に依存する Task も実行されない」を、確定の前に書く。確定ボタンの文言は「承認」「見送り」(他のコードは「確定」)。
- **カードの却下**: ボタンの文言は「提案を閉じる」に変えた(全コード共通)。見送りとは別の操作であることを、文言で明確にする。
- **マイグレーション**: 無し。`action_code` は既存の VARCHAR(64) で、新しいコードはそのまま入る。`project_chat_actions` への新しい SQL は、既存の列の UPDATE と SELECT だけ。
- **段階 4 へ残すもの**: 予算の UNPRICED の確認(ADR 0003・ADR 0010 追補 4)は、この段階に含めない。全明細の確認と必須の理由が要るため、段階 4 で行う。AI は提案だけができる。

### 既知の限界(段階 2)

- 承認と見送りの提案は、周の根の子 Task だけを見る。周の外や、計画の親の子は対象外(目標ページで操作する)。
- 「提案を閉じる」で閉じた提案は、同じ版で出し直さない(段階 1 と同じ)。Task の版が動けば、新しい版で出る。
