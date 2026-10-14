# ADR 0005: Exkes Port の失敗結果を Transient と Retryable に分ける

- ステータス: 採用(Accepted)
- 日付: 2026-10-15
- 関連: Issue #72(429 を NotSentProof として再送できる問題、P1)、#29(契約試験)、#44 / #52 / #58 / #60 / #61 / #70 / #71、PR #52 / #58 のレビュー

## 背景

`PortResult.Transient` は「再試行してよい」の意味で使われていた。`OperationExecutor` は全ての `Transient` に対して
`NotSentProof` を作り、`RETRYABLE` へ遷移していた。HTTP 応答(429 や 5xx)は、副作用が起きていないという証拠にならない。
そのため、変更系の操作で 429 を再送すると、二重の副作用を起こす可能性があった。

PR #52 で、変更系(POST / DELETE)の 429 は `Unknown` に直された。一方、読み取り(GET)の 5xx・429・timeout は、
まだ `Transient` のまま返している(#72 のコメントで判断待ちの論点として挙がっている)。

## 決定

1. `PortResult.Transient` は「送信前に確実に失敗した」場合だけに限る。例として、認証済みの Transport の送信前エラーがあり、
   検証可能な Dispatch Phase Evidence がある場合。`NotSentProof` の根拠にできるのはこの型だけとする。
2. 読み取り(GET)の 5xx・429・timeout など、「再試行してよいが、未送信の証拠ではない」ものは、別の型 `Retryable` にする。
   `Retryable` は `NotSentProof` の根拠にできない。
3. 変更系(POST / DELETE)の 429・5xx・timeout・不明な応答は `Unknown` とする。追加の POST はせず、照合(Trusted Reconciliation)へ回す。
4. `NotSentProof` を、`PortResult` から Agent 側で合成しない。

## 根拠

- 「未送信の証拠」は、HTTP 応答の種類からは分からない。検証可能な証拠を持つ経路だけが未送信を証明できる。
- 読み取りは副作用が無いので再試行してよいが、その再試行の可否を未送信の証明と混同すると、変更系の安全性が崩れる。
- 型を分ければ、呼び出し側が「再試行可」と「未送信」を取り違えられない。

## 結果

- 影響: `PortResult` に `Retryable` を追加する。`OperationExecutor` は `Retryable` を `NotSentProof` の根拠にしない。GET の再試行は `Retryable` で行う。
- やらないこと: 変更系の失敗を `Transient` に分類すること。HTTP 応答だけで `NotSentProof` を作ること。Provider の能力を自己申告で信用して `UNKNOWN` を `RETRYABLE` へ昇格させること(#71 の検証が前提)。

## 見直しの条件

- #29 の契約試験が main にマージされ、`Transient` / `Retryable` / `Unknown` の境界が試験で固定されたとき。
- Provider の Idempotency-Key や Receipt の契約が (exkes#25 / exkes PR #26 のとおり)実証されたとき。

## 実装状況

- 未実装: `Retryable` 型の追加、GET の分類の変更、`OperationExecutor` の分岐の変更。実装は別 PR とし、#29 の契約試験のマージ後に着手する。
- 済(main 上): 変更系の HTTP 429 は `Unknown` に分類される(PR #52)。`OperationExecutor` は現在 `Transient` を `NotSentProof` に変換している(#72 の対象)。
- 未決: 読み取り(GET)の扱い。#72 の判断待ち(選択肢 A: 現状維持と明文化、B: 本 ADR のとおり `Retryable` に分離)。本 ADR は B を採る。
