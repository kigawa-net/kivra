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
