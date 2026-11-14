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

## 追補 2026-11-14: チャットの AI 返信(ストリーミング)

- **方式**: 返信は model gateway を直接呼ぶ(`ProjectChatReplier`)。Task も Run も作らない。ツールは渡さず、モデルは何も実行できない。返信の文は指示として扱わず、画面でも文章として出す。
- **経路**: `POST /api/projects/{id}/chat/stream`(server-sent events)。本文とチェックは既存の `POST /chat` と同じ(ORDER_MANAGE、同一オリジン、プロジェクトの読み取り範囲)。運用者の文を先に保存し、その後に返信を流す。フレームは `user` → `delta`… → `done`(保存した返信)か `error`(固定コード)。
- **保存の決まり**: モデルが完了した返信は、完全な本文を保存する。途中で終わった返信(モデルの途中失敗・gateway の途中失敗・完了の無い流れ・クライアントの切断・呼び出しのキャンセル)は、それまでに生成された部分の文を assistant メッセージとして保存する。空白だけの部分は保存しない。保存はキャンセルされた後でも行う(`NonCancellable`)。次の場合は何も保存しない: テキストが出る前の拒否(gateway の拒否・テキスト前の失敗)、空の返信。運用者の文は、いずれの場合も残る。切断の検出は次のフレームの書き込み時なので、完了の後に切断されても返信は保存される(`done` の前に保存するため)。
- **履歴**: 直近 20 件(運用者の文を含む)。先頭が assistant になる窓は、先頭の assistant を落とす。
- **予算**: 予算の scope はテナント 1 つ(開発エージェントと同じ)。principal は固定の `project-chat`、データ分類は INTERNAL、出力上限は 2,000 トークン、全体の期限は 120 秒。
- **無効時**: self-development が無効で gateway が無い環境では経路を登録しない(従来どおり保存のみ)。
- **Task の投入はしない**: チャットの送信は、Task の投入(`SELF_DEV`)を行わない。送信は返信だけを起こす。送信中も入力を受け付け、新しい送信は進行中の turn を cancel してから再実行する(cancel された turn は上の決まりで部分の文を保存する)。

## 追補 2026-11-14(2): 難易度分類ルーティングと、ローカルからの外部フォールバックの例外

- **判定**: `DifficultyRouter`(kivra-model-gateway)が、直近の運用者の文(チャット)の要約だけを、ローカルの同じモデルに短く分類させる(非ストリーム、出力 8 トークン、10 秒)。答えが `EASY` のときだけローカル優先。`HARD`、不正な答え、分類の失敗・タイムアウト、ローカルが無い、能力を宣言したローカルが無い、の場合は Anthropic のみ。分類の呼び出しは gateway 経由(予算・台帳)で、ローカルにしか送らない。
- **能力**: 能力のハードフィルタは緩めない。要求が必要とする能力(TOOL_CALLING、STRUCTURED_OUTPUT_NATIVE、STREAMING など)を、registry で宣言したローカルのモデルが持つときだけ分類する。持たなければ分類の呼び出しを省く。
- **フォールバックの例外**: `Requirements.allowExternalFallbackFromLocal`(既定 false)。ローカル優先の要求にだけ立て、ローカルが主のときに EXTERNAL へ 1 回だけ移れる。PUBLIC と INTERNAL のみ。RESTRICTED と SECRET は不可。LOCALITY の LOCAL_ONLY と、外部の data_handling 未確認は従来どおり除外される。出力の開始後の失敗は再実行もフォールバックもしない(既存の部分保存の挙動)。
- **ローカルの HTTP エラー**: 408、409、429、5xx の応答は NOT_SENT とする(ローカルは課金が無く、出力も無い。再試行かフォールバックを可能にするための、LOCAL に限った判断)。
- **チャットの配線**: `ProjectChatReplier` は `DifficultyRouter` を使う(ルーターが無いときは従来どおり Anthropic のみ)。キルスイッチは `KIVRA_MODEL_ROUTING_CHAT_ENABLED`(既定 true)。セルフ開発の配線は本追補の範囲外(下記)。
- **未実装**: セルフ開発の呼び出し(LlmDeveloper、AutopilotPlanner、PrReviewWatch)へのルーター適用、`KIVRA_MODEL_ROUTING_SELFDEV_ENABLED` の利用、日次予算。

## 追補 2026-11-14(3): セルフ開発の配線と、1 日ごとの予算

- **セルフ開発の配線**: `LlmDeveloper`、`AutopilotPlanner`、`PrReviewWatch` の呼び出しを `DifficultyRouter` に通す。要約は、タスクのタイトルと指示文(計画は claim の題名と本文、レビューは PR の題名と本文)。リポジトリの内容、差分、シークレットは分類器に渡さない。キルスイッチは `KIVRA_MODEL_ROUTING_SELFDEV_ENABLED`(既定 true)。`StopJudgeClient` は gateway を使わず直接 HTTP のため対象外。
- **モデル固定との関係**: 計画とレビューは Anthropic のモデル ID に固定されている。ローカル優先のときは、apply が有効なローカルモデルの ID を許可に加える。能力のフィルタは残るので、能力を満たさないローカルモデルは選ばれない。
- **日次予算**: `kivra.budget.dailyLimitJpy`(env `KIVRA_BUDGET_TENANT_DAILY_LIMIT_JPY`)。未設定なら従来どおり。設定すると、テナントへの課金は月次に加えて日次(JST 暦日、スコープ `<tenant>:daily`、期間 `yyyy-MM-dd`)でも上限を確認する。予約は予約時の日付を保持し、ack と release は元の期間に対して行う。ローカルの費用 0 は消費しない。不正値は起動失敗(値は出さない)。マイグレーションは不要(既存の列 VARCHAR(16) と VARCHAR(128) に収まる)。日次の使用量の表示は未実装。

## 追補 2026-11-14(4): 日次予算の使用量表示

- **API**: `GET /api/admin/budget/status`(`BudgetStatusResponse`)に、本日(JST 暦日)の `dailyPeriod`、`dailyLimitJpy`、`dailyCommittedJpy`、`dailyReservedJpy`、`dailyRemainingJpy` を追加。日次上限が未設定のときは、これらの項目を含めない(従来のレスポンスと同じ)。`DbBudgetStore.operatorStatus()` が、当日分(スコープ `<tenant>:daily`、期間 `yyyy-MM-dd`)だけを読む。前日以前の予約と課金は含めない。
- **UI**: 管理画面の予算カードに、日次上限があるときだけ「本日の使用量 / 日次上限」を月次の下に出す。超過は文字でも示す(月次と同じ規則)。表示の文言は `DailyBudgetRules.kt`(commonMain)にまとめ、`DailyBudgetRulesTest` で固定。
- **対象外**: プロジェクト別の予算には日次を足さない(テナント単位のみ)。日次の残りが少ないことの警告は、月次と同じく超過の表示だけ。

## 追補 2026-11-14(5): チャットの AI によるツール呼び出し

- **ユーザーの決定**: 読み取りと安全な操作は AI が即実行し、破壊的な操作は確定制(AI は提案だけ)とする。これにより、§1「AI は実行しない」を一部変更する(即実行の操作は、操作した運用者の principal で実行する。AI の主体では実行しない)。追補 2026-11-14 の「ツールは渡さない」はこの追補で置き換える。
- **分類**(`kivra-app/.../chat/ChatTools.kt` の `ChatTool`。モデルが呼ぶ名前は列挙名の小文字):
  - 読み取り(即実行、変更なし): `order_get`、`order_list`、`run_get`、`run_list`、`task_list`、`project_get`、`memory_search`、`memory_get`。
  - 即実行(運用者の principal で実行): `order_start_cycle`(`startCycleAsOperator`)、`order_pause` と `order_resume`(`pauseAsOperator` と `resumeAsOperator`。REST の pause/resume ルートも同じ関数を呼ぶ)、`project_update`(`PUT /api/projects/{id}` と同じ `ProjectRepository.update`。共有関数 `updateAsOperator` が監査行 `PROJECT_UPDATED` を書く。省いた項目は現在の値を保つ)。
  - 確定制(提案だけ): `order_abandon_cycle`(ORDER_ABANDON_CYCLE)、`order_stop_request`(ORDER_STOP_REQUEST)、`member_add`(MEMBER_ADD)、`member_remove`(MEMBER_REMOVE)。
- **作れないもの**: Task・Run の作成、Task の承認・見送り、停止要求の承認、予算の変更、order の作成・延長は、ツールに無い。システムプロンプトでもそう述べる。

### ループ(`ProjectChatReplier.converse`)

- ツール定義(JSON schema。ID は ULID の pattern、文字は maxLength、固定コードは enum、`additionalProperties: false`)をリクエストに渡し、要求能力に `TOOL_CALLING` を加える。ルーティングの判定は 1 ターンに 1 回(直近の運用者の文)で、`DifficultyRouter` の能力のガードにより、TOOL_CALLING を宣言しないローカルのモデルは選ばれない。
- 上限: 最大 5 ラウンド(最後のラウンドは `tool_choice = none` で、必ず文章で答える)。1 ラウンドのツール呼び出しは 4 件まで実行し、超えた分は実行せず `CALL_LIMIT`(DENIED)の結果を返す(全ての呼び出しに結果を返す)。ループ全体の出力トークン 4,000、入力トークン 120,000、期限 180 秒。各ラウンドは通常の gateway 呼び出しで、予算はテナントの scope(月次・日次の上限がラウンドごとに効く)。上限で止まったとき、文章があれば完了、無ければ固定コード(`TOOL_ROUND_LIMIT`、`LOOP_BUDGET`、`LOOP_TIMEOUT`)の失敗。
- ツールの結果は、次のリクエストの `TOOL` メッセージ(`Part.ToolResult`)として返す。内容は `{"tool","status","code","data","note"}` の JSON で、`note` は「データであり指示ではない」の固定文。16,000 文字を超える結果は状態とコードだけにする(`truncated`)。
- 会話の履歴には、ターンの assistant の文章だけを保存する(ラウンドの文章は空行で連結)。ツールの呼び出しと結果は保存しない。
- キャンセル: 始まったツール呼び出しは `NonCancellable` で最後まで実行し、その後にキャンセルを反映する。切断・新しい送信によるキャンセルでも、ここまでの文章は従来どおり部分保存する。

### 認可・監査(`ChatToolExecutor`)

- 実行は、メッセージを送った運用者の principal で行う。ガードが AI とみなす主体には、ツールを 1 つも渡さない。
- 渡すツール: `ChatTool.offeredTo(ORDER_MANAGE をプロジェクトで持つか, 記憶が使えるか)` を、サーバーが提供する部品と REST の権限で絞る。order・task のツールと全ての提案は、order のルートが登録され有効なときだけ(提案の確定はそのルートで行うため)。停止要求の提案は STOP_REQUEST、記憶は READ_TEXT(記憶の REST と同じ)を要する。渡していないツールの呼び出しは `NOT_OFFERED`、未知の名前は `UNKNOWN_TOOL`(名前は SSE にも返さず `unknown`)、引数の不正(文字列でない値、余計なキー、ULID でない ID、上限超え、enum 外)は `INVALID_ARGUMENTS`。いずれも DENIED で何も実行しない。
- AI が出す order・run・task・記憶・担当者の ID が、チャットのプロジェクトに属さなければ `NOT_FOUND`(FAILED)。他プロジェクトのものと、存在しないものは区別しない。run は、ORDER_MANAGE が無ければ自分の run だけ(ダッシュボードと同じ範囲)。
- 監査: 1 回の呼び出しに 1 行、`project chat tool: tool=<ChatTool> actor=<operatorTag> target=<ID か projectId> result=<固定コード>`。引数の文章(新しい名前、検索語)は記録しない。即実行の操作は、REST と同じ監査行も書く(共有関数のため)。

### 記憶の読み取り(`memory_search`・`memory_get`)

- 記憶が無効(`KIVRA_SELFDEV_MEMORY_ENABLED` が off でリポジトリが無い)ならツールを渡さない。READ_TEXT が無い運用者にも渡さない。
- `SelfDevMemoryRepository.search`(`ProjectFilter.Only(projectId)`。SQL で絞る)と、新しい読み取り `findInProject(recordId, projectId)` だけを使う。他プロジェクトの記録は返さない(`NOT_FOUND`)。kivra-memory の `MemoryProvider` ポートは、アプリの記憶(ADR 0016 の `selfdev_memory_records`)には使われていないため使わない。
- 文章: ADR 0016 U-1 に従い、UNTRUSTED の文章(モデルやシステムが書いたもの)はプロンプトに入れない(`body_withheld`)。検索語の照合も、モデルに見せてよい文章(TRUSTED_OPERATOR)と固定コードだけで行う(伏せた文章の有無を照合で漏らさない)。TRUSTED の文章は `SecretFilter.redact` を通し、制御文字・双方向制御文字を除く。
- 上限: 検索は新しい順に 100 件を読み、最大 10 件を返す。1 件の文章は 500 文字、1 回の検索の文章の合計は 3,000 文字まで(超えた分は `body_omitted`、`truncated`)。

### 確定制(提案)

- 提案は既存の `project_chat_actions` の PROPOSED 行で、確定・却下は既存の `confirm`/`dismiss`(§4)。AI の提案は `origin = 'AI'`(規則の提案は `RULE`)で区別する。同じ (プロジェクト, コード, 対象) の提案が開いていれば、新しく書かずにそれを返す(`ALREADY_PROPOSED`)。規則と違い、同じ版の解決済みの行があっても、運用者が改めて依頼したので提案する。
- 規則の読み直し(チャットの読み取り)は、AI の提案を「規則に合わない」理由では失効させない。対象が提案時から変わったとき(order の版が動いた・order が無い・メンバーの在否が変わった)だけ `EXPIRED` にする。
- 停止要求の確定は、REST の停止要求と同じ共有関数 `requestStopAs`(理由は固定の `OPERATOR_REQUEST`、主体は HUMAN、監査行 `STOP_REQUESTED` も同じ)。権限は STOP_REQUEST。周の放棄は従来の `abandonCycleAsOperator`。
- **メンバー**: ProjectMemberRepository には無効化が無いので、無効化は作らず「削除」とする(`MEMBER_ADD`・`MEMBER_REMOVE`)。対象は担当者 ID、`expected_version` は版の代わりに提案時の在否(`1` = メンバー、`0` = メンバーでない)。追加の役割は `param_code`(`PROJECT_MEMBER_ROLES` のいずれか。確定時にも検査)。確定は、在否が提案時と同じことを確かめてから、メンバーのルートと同じ共有関数 `addAsOperator`(`put`)・`removeAsOperator`(`remove`)を呼ぶ(監査行も同じ)。権限は ORDER_MANAGE(メンバーのルートと同じ)。在否が違えば `EXPIRED`(`STALE_VERSION`)、最後の ADMIN は `FAILED`(`LAST_ADMIN`)など、結果は固定コード。提案時、存在しない担当者の追加と、メンバーでない担当者の削除は `NOT_FOUND`、既にメンバーなら `ALREADY_MEMBER`。
- **V57**: `target_id` を CHAR(26) から VARCHAR(64) に広げる(MODIFY。旧 Writer の値は収まり、UNIQUE も保つ。`MigrationExpandOnlyTest` に理由付きで列挙)。`target_kind`(ORDER / TASK / ASSIGNEE。既定 ORDER。既存の Task コードの行は TASK に更新)、`origin`(RULE / AI。既定 RULE)、`param_code`(NULL 可)を追加し、kind と origin に CHECK を付ける。指示の「ORDER / ASSIGNEE」に対し、既存の Task コード(TASK_REPLAN 等)の行があるため TASK も持つ。
- 画面: 提案のカードに、AI の提案である旨(「AI の提案です。確定するまで何も実行されません」)とメンバーの対象(名前と役割)を出す。確認ダイアログの文言は、停止要求(承認者が承認するまで止まらない)とメンバーの追加・削除(無効化ではなく削除)を書く。

### SSE と画面

- 新しいフレーム `tool`: `{"name","status","code"}`。status は STARTED / OK / DENIED / PROPOSED / FAILED、code は固定コード(STARTED には無い)。引数や結果のデータは流さない。
- 画面(`ChatStreamRules.kt` の `chatToolStatusText`・`chatToolLines`、`ProjectDashboardScreen.kt`): 返信の下に、ツールごとに 1 行の固定文言(「order を確認中…」「order の停止要求を提案しました(確定は下のボタン)」「…を実行しました」「…は許可されていないため、実行しませんでした」)。コードや未知の名前はそのまま表示しない。
- 画面の提案カード(確定・却下)は #517 の改修で画面から外れていたため、チャットの節に戻した。

### 既知の限界・残り

- 監査行のテストは、ログの出力そのものは検証していない(呼び出しの結果コードは単体テストで固定)。
- メンバーの追加の確定で、在否の確認と `put` の間に他の操作でメンバーになった場合、`put` は役割の変更として働きうる(結果は `EXPIRED` として記録する)。
- AI が担当者 ID を知る手段は `project_get` のメンバー一覧(運用者の場合)と運用者の文だけで、担当者の一覧・検索のツールは無い。
- ターンの順序付け(`ChatTurnRegistry`)は従来どおりプロセス内だけ。

## 追補 2026-11-14(6): 具体的な作業は子 order に任せる(`order_create_child`)

- **ユーザーの決定(2026-11-14)**: 「具体的なものは order にやらせる」。チャットの AI は、具体的な作業の依頼(画面や機能の変更、バグの修正、調査と実装など)を自分で答えたり実装したりせず、子 order として登録して order に任せる。登録は AI が直接行う(運用者の確定は不要)。
- **分類の変更**: 追補 5 の「即実行」に `order_create_child` を加える。追補 5 の「作れないもの」のうち「order の作成」は、ルート order の作成と order の延長に狭める(子 order は作れる)。
- **ツール**: 引数は `title`(1 行、200 コードポイントまで)、`instruction`(子 order の policy。2,000 コードポイントまで、改行とタブは可、他の制御文字・双方向制御文字は不可)、任意の `maxCycles`(1〜設定の上限 `KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP`。既定 1。JSON の数値か数字の文字列)。親は指定させず、サーバーが決める: そのプロジェクトのルート order のうち ACTIVE なもの。ルートが無ければ `NOT_FOUND`、ACTIVE なルートが無ければ `ROOT_NOT_ACTIVE`、ACTIVE なルートが 2 つ以上なら `AMBIGUOUS_ROOT`(作らない)。他プロジェクトの order は親にしない。
- **REST との共有**: `SelfDevOrderRouteDeps.createChildAsOperator` を切り出し、`POST /api/selfdev/orders/{id}/children` とチャットが共に呼ぶ。設定の上限(深さ・子の数・木の大きさ)、親の予算の残り、親の状態の検査、監査行 `order operator action: action=CHILD_CREATED` は同じ。フィールドの検査も REST と同じ関数(`validatedText`、`validatedPolicy`)。子 order の予算はモデルに選ばせず、サーバーが既定の周の予算 × `maxCycles` に固定する。REST の子の作成は従来どおり `max_cycles = 1` だが、リポジトリの `createChild` に `maxCycles`(既定 1)を加え、チャットだけが 1 以外を渡せる。
- **提供の条件**: ORDER_MANAGE(プロジェクトで)を持つ運用者にだけ、order のルートが有効なときだけ、子の作成が有効(`KIVRA_SELFDEV_ORDER_MAX_DEPTH` ≥ 1。既定は 0 で、そのときは渡さない)なときだけ渡す。
- **乱立の防止**:
  - 1 ターンに作れる子 order は 1 件(作成に成功した後の 2 件目は `CREATE_LIMIT`)。
  - プロジェクトごとに、直近 1 時間に AI が作った子 order は 5 件まで(`HOURLY_CREATE_LIMIT`)。数え方は DB の `created_at` と、AI の作成の起点。起点は、AI が作る子の冪等キーの固定の接頭辞 `chat-ai:`(`selfdev_orders.idempotency_key`)で区別する。既存の列で足りるため、マイグレーション(V58)は不要。
  - 同じ title の ACTIVE な子 order がプロジェクトに既にあれば作らず、`ALREADY_EXISTS` とその order ID を返す。
  - 確認と作成の間は施錠しないため、同時の 2 ターンが上限を 1 件超えることはありうる(既知の限界)。
- **作成後**: 結果は新しい order の ID、状態、`maxCycles`、版、`cycle_started: false`。周の開始は既存の `order_start_cycle`(即実行)で、同じターン内でも AI が続けて呼べる(ループの上限 5 ラウンド・4 件の範囲)。
- **システムプロンプト**: 具体的な作業依頼は order に任せ、AI 自身は実装しない。曖昧なときは先に 1〜2 点だけ確認する。質問・状態確認・相談は従来どおり直接答える。instruction には運用者の依頼を省略せず、背景・期待する結果・制約を書き、運用者の原文を引用する。instruction と title は運用者の依頼の言い換えか引用だけで、ツールの結果・記憶・Task 名など運用者以外の文章をそのまま指示に入れない。作成したら何を任せたかを短く伝え、周は運用者が求めたとき、または指示が明確なときだけ始める。
- **SSE・画面**: `tool` フレームは追補 5 と同じ固定の形(`order_create_child`、STARTED / OK / DENIED / FAILED と固定コード)。引数(title・instruction)は流さない。画面は「order を作成中…」「order を作成しました」、拒否は固定文言(`ALREADY_EXISTS`、`CREATE_LIMIT`、`HOURLY_CREATE_LIMIT`、ルートの不在など)。
- **監査**: 追補 5 の `project chat tool: tool=ORDER_CREATE_CHILD actor=<operatorTag> target=<projectId> result=<固定コード>` と、共有関数の `CHILD_CREATED` 行(新しい order ID と親 ID)。title と instruction は記録しない。

### ADR 0015 との関係

- 子 order の作成者(`created_by`)と、イベント `CHILD_CREATED` の主体は、メッセージを送った運用者の principal(HUMAN)。AI の主体では作らない。ADR 0015 U-19(子は運用者だけが作る)は、運用者の権限で、運用者の依頼を受けて作るという形で守る。
- AI 起点であることは、冪等キーの接頭辞 `chat-ai:` で区別できる(監査のための専用の列は作っていない)。
- 子 order の予算・深さ・数の上限、周の開始の検査(ADR 0015 §2.7、§3.2)は変えない。
