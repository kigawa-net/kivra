# ADR 0007: Policy は読み取り専用の設定として供給し、Agent から変更できないようにする

- ステータス: 採用(Accepted)
- 日付: 2026-10-15
- 関連: Issue #32(M3: Tool Gateway と Policy Engine)、PR #143(禁止操作の拒否のテスト)、設計 #1 §6.3(Forbidden)

## 背景

Policy(静的ルール)は、Tool Gateway が操作を許可・拒否する根拠になる。
Policy を Agent や Tool Gateway から書き換えられると、Agent が自分の権限を広げられてしまう。
`kivra-policy` の `FORBIDDEN_ACTION_PATTERNS` は `policy.*`、`safety.*`、`privilege.*`、`approval.grant` などを
承認があっても拒否する。PR #143 は、その拒否をテストで保証している。

## 決定

1. Policy は、コンテナイメージに同梱するか、読み取り専用でマウントした設定として供給する。
2. Agent・Tool Gateway から Policy を書き込む経路を作らない。
3. Policy の変更は、PR と人間のレビューを通す(GitOps)。
4. 起動時に Policy のハッシュを計算して固定し、ログと(あれば)メトリクスに報告する。
5. 実行中のホットリロードは行わない。反映は再起動で行う。
6. `policy.update`、`approval.grant` などは、承認があっても Forbidden とする(PR #143 のテストで保証)。

## 根拠

- Agent が変更できる Policy は、Agent 自身の権限を広げる入口になる。書き込み経路を無くすことが最も単純な防御である。
- GitOps にすると、変更の履歴・レビュー・巻き戻しが既存の仕組みで行える。
- 起動時に固定すれば、実行中の差し替えを防げる。ハッシュの報告で、どの Policy が効いていたかを後から確認できる。

## 結果

- 影響: Policy の変更は、再起動を伴うデプロイとして扱う。Policy を動的に調整する機能は作らない。
- やらないこと: Policy を更新する API、承認による Policy の一時緩和、実行中のホットリロード。

## 見直しの条件

- Policy の変更頻度が、デプロイ単位では運用に合わなくなったとき(その場合は別の ADR で、署名つきの配布などを決める)。

## 実装状況

- 済: 禁止操作の拒否(`kivra-policy` の `FORBIDDEN_ACTION_PATTERNS`。`policy.update`、`approval.grant` などは承認があっても拒否)。PR #143 のテストで保証。
- 未確認: Policy の供給方法(イメージ同梱、またはマウント)の実装。今回の調査では、ファイルからの読み込み経路を確認できなかった。
- 未実装: 起動時の Policy のハッシュの計算・固定・報告。Issue #32 に残す。
