# ADR 0009: UNKNOWN の自己開発 Run は、運用者専用の経路だけで CANCELLED に解放する

- ステータス: 採用(Accepted)
- 日付: 2026-10-16
- 関連: Issue #56(Self-Development Integration Runner)、ADR 0005(結果の分類と UNKNOWN)、ADR 0008(PR 作成までの自己開発ループ)、ADR 0003(UNKNOWN_COST の acknowledge)

## 背景

ADR 0008 の自己開発 Run は、外部結果が不明なとき `UNKNOWN` になる。`UNKNOWN` は有効な Run(terminal ではない)なので、同時 Run 上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`、既定 1)を占有し続ける。
運用者が解放する手段は無かった。そのため、1 件の UNKNOWN で新規の提出が 429 になり続けた。

状態機械(`kivra-core` の `DevelopmentRunState`)では、`UNKNOWN` の通常の遷移先は空で、証拠付きの遷移先は `PULL_REQUEST` と `FAILED` だけである。
`UNKNOWN -> CANCELLED` は、どちらにも無いので、汎用の `transition()` は拒否する。`StatesTest` がこの期待を固定している。

## 決定

1. **通常の遷移ルールは変えない。** `DevelopmentRunState.next`、`evidenceGated`、`transition()` は従来どおり、`UNKNOWN` からの遷移を拒否する。`kivra-core` は変更しない。
2. **解放は運用者専用の経路だけで行う。** `DevelopmentRunRepository.releaseUnknownSelfDev` が、`UNKNOWN -> CANCELLED` だけを行う。対象は自己開発の Run(`taskId` が `selfdev:` で始まるもの)に限る。
3. **1 トランザクションで書く。** 行をロックし、`state = UNKNOWN` を条件にして更新する。同じトランザクションで `RUN_RELEASED` の監査と、`RunStateChanged` の outbox を書く。どれかが失敗すれば、すべて戻る。
4. **理由は監査の payload にだけ入れる。** 運用者の理由(1〜500 文字)は、監査の payload にだけ入る。Run の `state_reason` は固定コード `OPERATOR_RELEASED` である。ログには出さない。
5. **認可は予算の acknowledge と同じ。** `TaskApiIdentity` の主体を運用者とする。未認証は 401、存在しない Run と自己開発でない Run は 404、`UNKNOWN` でない Run は 409(解放済みを含む)。
6. **解放すると上限の計数から外れる。** 計数は terminal 状態を除外しているので、`CANCELLED` になった Run は同時 Run 上限を占有しない。

## 結果

- PR_ONLY の Run の副作用は、GitHub の branch と PR だけである。人がマージする。kivra は PR を閉じず、マージもしない(ADR 0008)。解放しても PR は残る。その扱いは人が決める。
- 解放は、UNKNOWN の原因を直さない。同じ原因が続けば、次の Run も UNKNOWN になる。原因の再発防止は別の作業である。
- 解放の経路が増えるのは、この 1 つの状態遷移だけである。汎用の遷移ルールは、今後も証拠が無ければ UNKNOWN から出られない。
- 将来、証拠(reconciliation)に基づく自動の解放を入れるときは、この ADR を置き換える。

## 追補(2026-10-17): 束縛の無い PULL_REQUEST の Run の解放

対象: #56 のマージ検証 ⑩ の PR4b。

### 背景

束縛(binding)の記録(PR2)より前に作られた自己開発 Run は、`PULL_REQUEST` で止まり、binding を持たない。PR がマージされても、
`SelfDevRunner` は `BINDING_MISSING` で待つ。kivra は binding を GitHub の値から推測しないため、自動では完了しない。
この Run は同時 Run 上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)を占有し続ける。UNKNOWN 専用の解放(本 ADR の決定 2)は `PULL_REQUEST` に使えない。

### 決定(追補)

1. **UNKNOWN の解放とは別の経路にする。** `DevelopmentRunRepository.releaseUnboundPullRequestSelfDev` が、
   `PULL_REQUEST -> CANCELLED` だけを行う。通常の遷移表(`DevelopmentRunState.next`)と `transition()`、`releaseUnknownSelfDev` は変えない。
   `kivra-core` は変更しない。
2. **対象は限定する。** 自己開発の Run(`taskId` が `selfdev:` で始まる)で、状態が `PULL_REQUEST`、かつ binding が無いもの
   (`toPullRequestBindingOrNull` が null。`loadPullRequestBinding` と同じ判定で、行ロックの下で読む)。
   binding がある `PULL_REQUEST` は対象外で、409 になる。マージされれば従来どおり自動で `COMPLETED` になる。
3. **エンドポイントは別のパスにする: `POST /api/admin/selfdev/runs/{id}/release-unbound-pr`。**
   既存の `POST .../release` は拡張しない。理由: (a) 既存のエンドポイントは「UNKNOWN だけを解放する」という契約として、
   ADR 0009、runbook §4.1、既存のテスト(`SelfDevAdminRoutesTest`、`RequestGuardsTest`)が固定している。状態で分岐させると、
   既存の 409 の意味が変わる。(b) 別パスにすると、運用者が「束縛の無い PR」を明示して選ぶことになり、
   UNKNOWN の経路で誤って PR の Run を解放する事故が起きない。
   認可、CSRF(`requireJsonSameOrigin`)、本文 `{"reason": 1〜500 文字}`(厳格。ほかのフィールドは 400)、未認証 401、
   該当なし 404(不存在、`api:` の Run を含む)、対象外の状態・binding がある 409 は、既存の `release` と同じ。
   リクエストの本文の解析は共通の関数にまとめた。
4. **遷移と書き込み。** 行をロック(`FOR UPDATE`)し、`state = PULL_REQUEST` と `version` を条件にして更新する。
   同じトランザクションで `RUN_RELEASED` の監査(actor は認証済みの運用者、payload に `from`/`to`/`reasonCode` と `reason`)と、
   `RunStateChanged` の outbox(理由コードのみ)を書く。どれかが失敗すれば、すべて戻る。
   Run の `state_reason` は固定コード `OPERATOR_RELEASED_UNBOUND_PR`(27 文字。`state_reason` は `varchar(64)`)。
   理由(自由記述)は監査の payload にだけ入る。ログと outbox には出さない。
5. **GitHub には何もしない。** kivra は PR を閉じず、マージもしない。`GitHubPort` に close / merge を足さない。
   解放後、PR は GitHub に残る。人がマージしても、Run は `CANCELLED` のままで、kivra はそれを追跡しない。
   PR の扱い(閉じる、そのまま残す)は人が決める。
6. **解放後は上限の計数に入らない。** `countActiveSelfDev` と `activeSelfDevRunIds` は terminal 状態を除外する。
   `CANCELLED` は terminal なので、解放後の Run は上限を占有しない。テストで確認する。

### 未対応(この追補で扱わないこと)

- **binding がある `PULL_REQUEST` の Run を人が止める経路は作らない。** 放置したい PR(マージされる見込みが無いもの)を止めるには、
  別の決定が要る(例: 作者が kivra の App ではないと分かっている PR の扱い、PR を閉じるかどうか)。現時点では、
  binding がある Run は `PR_ABANDONED`(PR が閉じられた、または `KIVRA_SELFDEV_PR_REVIEW_TIMEOUT_DAYS` を過ぎた)でだけ止まる。
  それ以外は、人がマージするか、PR を閉じるまで待つ。この経路が必要になったら、別の ADR で決める。
- **解放時に、Run の lease を強制的に解く処理は行わない。** worker が lease を持っていれば、その worker の次の fenced 書き込みは、
  状態と version の条件で失敗する。UNKNOWN の解放と同じ扱いである。
- **原因の再発防止は含まない。** 同じ原因で次の Run が再び止まれば、同じ経路で解放する。
- **画面(`app.js` など)の対応は含まない。** 別の PR で扱う。

### 結果(追補)

- 解放の経路は、2 つになる。UNKNOWN(ADR 0009 本文)と、束縛の無い PULL_REQUEST(本追補)。どちらも、運用者が認証された主体として行い、
  監査と outbox を同じトランザクションで書く。
- 汎用の遷移ルールは変わらない。`PULL_REQUEST -> CANCELLED` は、`next` に従来からあるが、この経路は `transition()` を通らない。
  運用者の経路で、束縛の有無を行ロックの下で確認する点が、汎用の遷移と異なる。
