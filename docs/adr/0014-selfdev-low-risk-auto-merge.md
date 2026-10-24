# ADR 0014: 自己開発の低リスクな PR は、必須チェックと実際の差分の検査を通したときだけ自動でマージする(dev のみ、段階的に導入)

- ステータス: 提案(Proposed)。ユーザーの承認待ち。承認まで実装しない
- 日付: 2026-10-19
- 関連: ADR 0008(マージは人間が行う。§3(b)(d) を一部改定する案を含む)、ADR 0011(段階 2 の自動承認、§2.8d と §6.2 の差分の再検査、§6.3 の昇格条件)、ADR 0001(Run の正本)、ADR 0005(結果の分類と UNKNOWN)、ADR 0009(UNKNOWN の解放)、ADR 0010(CLI 経路)

## 背景

- 現在の流れは次のとおり。運用者が Goal を入力する。Planner が Plan を作る。段階 2 では、docs・テスト・README だけに触れる低リスクのタスクは承認なしで投入される(ADR 0011 §6.1)。Run が PR を作る。**人が PR をマージする**(ADR 0008)。kivra が GitHub 上のマージを照合し、Run を COMPLETED にする。
- 人のマージが、自立した開発を止める唯一の手作業になっている。ユーザーは、dev に限り、低リスクな PR の自動マージを望んでいる。それ以外のコード変更は、人が承認し、人がマージする。Goal の入力は dashboard だけであり、Issue からの入力はしない(変えない)。
- 事実(2026-10-19 に、読み取りだけで確認した)。
  - `kigawa-net/kivra` の `main` にはブランチ保護がある。承認必須数は 0、force push と削除は禁止、`enforce_admins` は false。**必須ステータスチェックは未設定**(`required_status_checks` は 404)。Rulesets は 0 件。ADR 0008 の時点では保護が無かった。infra#296 による追加と推定するが、未確認。
  - リポジトリの `allow_auto_merge` は **false**。GitHub の自動マージ機能は、現在は使えない。
  - kivra の GitHub App が発行するトークンの権限は `contents:write` と `pull_requests:write` だけである(`kivra-github/.../GitHubAppTokenMinter.kt`)。`checks:read` も `statuses:read` も無い。
  - `GitHubPort` には merge が無い(ADR 0008 のとおり、テストで固定されている)。CI の状態を読む `getCheckRuns` と `getCombinedStatus` は実装されているが、本番の経路からは呼ばれていない(テストと `FakeGitHub` からのみ)。
  - 実際の PR(#266)のチェック名は、`test`、`build`、`boot`、`bad-credentials`(pass)と、`deploy to dev`(**PR では skipping**)である。ワークフローは `ci.yml`(`push` と `pull_request`)と `cd.yml`、`local-boot-smoke.yml` である。
  - `CheckRunsSummary.allPassed` は `neutral` と `skipped` を成功として扱い、空の一覧を成功としない(コードのコメントに明記)。マージの判定には、このまま使えない。必須チェックを名前で見る必要がある。
  - マージの照合(`PrMergeAuthorityBase.verifyMerge`、`kivra-core/.../TrustedPrMerge.kt`)は、repo、repoId、PR 番号、node id、base ref、head SHA(binding と一致)、author id(App の bot)、merged、merge commit SHA を見る。**マージを実行する経路は無い**。
  - `cd.yml` は PR でもビルドを行い、同一リポジトリの PR には BWS と Harbor の認証情報を渡す。`main` への push では dev のマニフェストを更新する。つまり、`main` へのマージは dev へのデプロイを起こす。`Dockerfile` は `installDist` を実行し、テストは実行しない。テストソース(`kivra-*/src/test/**`)が実行されるのは `ci.yml` の `test` ジョブだけであり、`ci.yml` は secrets を参照していない。
  - Developer の既定の文脈は `README.md,docs/` である(`KIVRA_SELFDEV_CONTEXT_PREFIXES` の既定値、`SelfDevSettings.kt`)。
  - ADR 0011 §6.3 の昇格条件(段階 1 → 2 の「承認タスク 10 件以上が COMPLETED」)の達成状況は**未確認**である。#230 は CLOSED を確認したが、完了か取り下げかは未確認。ADR 0011 の実装状況(2026-10-18 時点)では、dev での段階 1 の有効化と E2E は未実施と記録されている。

## 決定

### 1. 自動にするものと、人に残すもの

- **自動マージする**: 次の §2 の条件を**すべて**満たす PR だけ。対象の範囲は U-1 で決める。
- **人に残す**: それ以外のすべての PR。特に次のもの。
  - 運用者が承認したタスクの PR(U-8)。
  - コードの本体(`kivra-*/src/main/**`)の変更。
  - CI・ビルド・認証・予算・ポリシー・マイグレーション・マージの判定そのものの変更。
  - docs と README.md(§2.2 の理由で、初回は対象外)。
- 対象は dev(`kigawa-net/kivra`)だけである。本番の設定は変えない。
- 自動マージは **PR を既存のマージ操作で閉じる**ことだけを行う。`main` への直接の書き込み、force push、ブランチ保護とルールセットの変更、必須チェックの変更は、kivra の実行時の処理では行わない。

### 2. 自動マージの条件(すべて満たすときだけ。1 つでも欠けたら人に残す)

- **(C1) 起源**: Run の状態が `PULL_REQUEST` である。PR の head ref が `kivra/run-<runId>` である(`RunBranch.name`)。Run の binding(repo、PR 番号、node id)と一致する。PR の作者 id が kivra-gh の bot の id と一致する。
- **(C2) 段階 2 の自動承認**: そのタスクは `approved_by` が無く、`AUTO_APPROVED` で投入された(ADR 0011 §2.8d の判定と同じ)。運用者が承認したタスクの PR は対象外(U-8)。
- **(C3) 実際の差分**: 次は GitHub の PR の files API の値で判定する(ローカルの `PatchSet` は使わない)。
  - ファイル数が 1〜3 件。
  - `additions + deletions` の合計が 200 以下(削除行を含む)。ADR 0011 §2.8d の残るリスク 3(書き換えの削除行を数えない)は、この判定で解消する。
  - `status` は `added` か `modified` のみ。`removed` と `renamed` は不可。
  - すべてのパスが対象範囲内(U-1)。`..`、`\`、先頭の `/`、NUL を含まない。
  - head コミットのツリーで、各ファイルの mode が `100644` である。symlink(`120000`)と実行ビット(`100755`)は不可。取得方法は**未確認**(files API が mode を返すかは未確認。git の tree API で確認する想定)。
- **(C4) head の一致**: PR の現在の head SHA = 承認時の binding の head SHA = PR 本文の `kivra-head-sha:` マーカー(作成時に書かれている、`GitHubPort.kt`)。1 つでも一致しなければ、人に残す(以後は自動で扱わない)。
- **(C5) 必須チェック**: U-3 で決める名前付きのチェック(推奨は `test`、`build`、`boot`)が、**同じ head SHA** で `status=completed` かつ `conclusion=success` である。`neutral`、`skipped`、欠落、`cancelled` は成功としない。`queued` と `in_progress` は保留にして、次の tick で再評価する。保留が 60 分(暫定)を超えたら、人に残す。これは失敗として数えない。`deploy to dev`(PR では skipping)は必須にしない。
- **(C6) マージ可能**: GitHub の `mergeable == true` かつ `mergeable_state == "clean"`。`mergeable` が `null`(計算中)なら保留する。draft の PR は対象外。
- **(C7) 人が止める手段**: PR に `kivra:hold` ラベルが無い(U-7)。
- **(C8) 全体の状態**: 自動マージの有効フラグ(§4 の環境変数と DB のフラグ)が有効である。全体の一時停止が無い。サーキットブレーカーが閉じている。`main` の直近の push の CI(`ci.yml` の push 実行)が `success` である。
- **(C9) 上限**: 処理中の自動マージは同時に 1 件まで。UTC の 1 日に自動マージする PR は 3 件まで(U-5)。
- **(C10) 直前の再検査**: merge API を呼ぶ直前に、C3〜C8 を PR を読み直して再評価する。merge API には **`sha`(head SHA)を指定する**。head が変わっていれば、GitHub はマージしない(GitHub の仕様。実装前に公式ドキュメントで確認する)。C10 で条件が崩れたら、人に残す。merge API の拒否(409、422)は失敗として数えず、次の tick で C3 から最初にやり直す。同じ PR で 2 回続けば、人に残す。

マージのあとの確認は、既存の経路をそのまま使う。`GitHubPrMergeAuthority` と `verifyMerge` が author、head SHA、merged を確かめたうえで、Run を COMPLETED にする。自動マージは、この照合を迂回しない。

#### 2.1 候補の採否

| 候補 | 採否 | 理由 |
|---|---|---|
| タスクが段階 2 の自動承認を通っている | **採用**(C2) | 計画の段階で低リスクと判定されたもの。ただし計画の自己申告なので、C3 の差分の検査と組み合わせる |
| 実際の差分が範囲内(AutoScopeGuard の規則を、マージ直前に PR の差分で再検査) | **採用**(C3、C10) | 差分は計画と違いうる。コミットの後に head が動くのは人の push だけだが、マージの直前に読み直すのは安い |
| PR の作者が kivra-gh の bot、ブランチが `kivra/run-*` | **採用**(C1) | 人が作った PR を、この経路でマージしないため |
| 変更ファイル数と行数の上限 | **採用**(3 件、200 行) | ADR 0011 §6.2 と同じ値。行数は PR の `additions + deletions` で数える |
| 禁止領域 | 下の表のとおり | コードの実態に基づいて決める |
| 運用者が承認したタスク | **対象外**(U-8) | 運用者の承認は、マージの承認ではない |
| bot が PR を承認して人のレビューの要件を満たす | **却下**(§7) | |
| 必須チェックを緩める(`skipped` を成功扱い) | **却下**(C5) | 「実行されなかった」を「成功」にしないため |

禁止領域の判定(コードの実態を確認した結果)。

| パス | 判定 | 理由 |
|---|---|---|
| `.github/**`、`gradle/**`、`**/*.gradle.kts`、`**/gradle.properties`、`**/gradlew*`、`**/Dockerfile*`、`**/compose*.yml` | 禁止 | `PatchPolicy` の既定の denylist(拒否)。CI とビルドの変更 |
| `kivra-policy/**`、`kivra-*/src/main/kotlin/**/auth/**`、`kivra-model-gateway/**/Budget.kt` | 禁止 | `PatchPolicy` の denylist。認可、ポリシー、予算の中核 |
| `kivra-*/src/main/resources/db/migration/**` | 禁止 | `PatchPolicy` の denylist。マイグレーションは人が書く(V1〜V20 が実在する) |
| `docs/adr/**`、`docs/provider-compatibility-matrix.md`、`**/CLAUDE.md`、`**/.env*`、`**/*.pem`、`**/*.key` | 禁止 | `PatchPolicy` の denylist |
| `kivra-github/**`、`kivra-core/src/main/kotlin/net/kigawa/kivra/core/**`、`kivra-app/src/main/kotlin/net/kigawa/kivra/selfdev/**` | 禁止 | マージの判定と照合の本体。自動マージの門番を、門番自身が書き換えないため。これらは `kivra-*/src/main/**` でもあり、C3 の範囲外でもある |
| `kivra-app/src/main/resources/ui/**` | 禁止 | 運用者の画面。表示の経路の変更は、人が見る |
| `kivra-*/src/test/resources/**` | 禁止 | テストが読む設定として使われうる。ソースではない |
| `docs/**`、`README.md` | **初回は対象外**(U-1) | §2.2 |
| `kivra-*/src/test/kotlin/**/*.kt` | **対象**(U-1 の推奨) | §2.2 |

#### 2.2 docs と README を初回の対象から外す理由

段階 2 の計画の規則は、docs と README を低リスクとして承認なしに通す(ADR 0011 §6.1)。しかし、Developer の既定の文脈は `README.md,docs/` である。自動でマージされた文書は、後の Run の Developer の入力になる。悪意のある指示を文書に入れれば、それが後の実行に持ち越される(持続的なプロンプトインジェクション)。人のレビューが無い経路では、この持ち越しを止める手段が無い。そのため、初回は対象から外す。

テストソースは、CI の `test` ジョブで実行される。ただし、マージの前にすでに PR の CI で実行されており、自動マージで新たに増えるのは、マージ後の `main` の CI の実行と、`cd.yml` の `main` への push の実行(dev へのデプロイ)である。テストは本番のイメージに入らず、`Dockerfile` もテストを実行しないため、残るリスクは `ci.yml` の `test` ジョブの実行環境に限られる(`ci.yml` は secrets を参照しない。ただし、ランナーの環境は未確認)。

### 3. マージを行う者

| 方式 | 内容 | 利点 | 欠点・必要なもの |
|---|---|---|---|
| **A: kivra-gh が merge API を呼ぶ(推奨)** | §2 の条件をすべて kivra が判定し、`sha` を指定して `PUT /repos/{owner}/{repo}/pulls/{n}/merge` を呼ぶ | 条件がすべて kivra の中にあり、監査できる。`sha` で head の一致を GitHub 側で確かめられる。C10 の直前の再検査を、同じ処理で行える | `GitHubPort` に merge を足す(テストの固定の変更)。ADR 0008 §3(d)「kivra は承認・マージの API を呼ばない」を部分改定する。`main` への書き込みを、merge という形で例外にする(§3(b) の改定)。merge に必要な権限が現在の App で足りるか**未確認**。`checks:read` の追加(U-4) |
| B: GitHub の auto-merge を使う | PR に auto-merge を有効化し、GitHub が必須チェックの成功でマージする | マージの実行を GitHub に任せる。kivra は merge API を持たない | リポジトリの `allow_auto_merge` を true にする必要がある(リポジトリ管理者の権限。kivra-gh は持たない)。必須チェックが無いと、有効化の直後にマージされる。有効化のあとに head が変わっても、kivra は再検査しない。C3 から C8 の判定は、有効化の前にしか行われない |
| C: merge queue | GitHub の merge queue を使う | base が進んだ後の再検査を GitHub が行う | 機能の利用可否と設定は**未確認**。同時処理が 1 件の現在は効果が小さい。同時 2 件以上にするときに再検討する |

**推奨は A** である。理由は、判定と実行を kivra の 1 か所にまとめられ、C10 の `sha` の指定と再検査を同じ場所で行えるからである。B は、リポジトリ設定の管理者権限と、必須チェックの先行設定に依存する。

**権限の追加は共有基盤の変更である。** A を選ぶ場合は、次のどちらも、実装の前にユーザーの承認が要る。
- GitHub App の権限への `checks:read`(と、必要なら merge に必要な権限)の追加(U-4)。
- `kigawa-net/infra` の Terraform での、`main` の必須ステータスチェックの設定(U-3)。

ブランチ保護は、必須チェックを足しても、承認必須数 0 のままでよい。承認の必須化は、この ADR の範囲外(ユーザーの判断)。`enforce_admins` は false のままなので、保護を迂回できるのは管理者の権限を持つ主体だけである。kivra が発行を要求する App の権限は `contents` と `pull_requests` だけであり、管理者の権限は含まれない。ただし、App のインストール設定の全体は未確認である。

### 4. 安全弁と停止の条件

- **キルスイッチ(起動時)**: 環境変数 `KIVRA_SELFDEV_AUTOPILOT_AUTOMERGE_ENABLED`(名前は仮。既定 `false`)。`true` と `false` 以外は起動に失敗する。オートパイロットが無効のときは読まない(ADR 0011 §7.2 と同じ形)。変更には再起動が要る。
- **一時停止(実行時)**: `selfdev_autopilot_control` に `auto_merge_paused`(U-10)を足す。dashboard から、再起動なしで止められる。次の tick から効く。ADR 0011 U-11 と同じ理由で、環境変数だけにはしない。
- **全体の停止**: 既存の全体の一時停止(`/api/selfdev/autopilot/pause`)と、`KIVRA_SELFDEV_AUTOPILOT_ENABLED=false`、`KIVRA_SELFDEV_ENABLED=false` は、自動マージも止める(C8)。
- **PR ごとの停止**: `kivra:hold` ラベル(U-7)。人が PR に付ければ、その PR は自動マージされない。
- **ブレーカー**: マージ後の `main` の CI の赤(下記)は、1 件でも自動マージと投入を即時に止める。ADR 0011 の連続失敗の計数には入れない。C10 の拒否(merge API の 409、422)も計数しない(§2 のとおり)。
- **マージ後に main の CI が赤になったとき(検知と自動停止)**: kivra は、マージした commit の SHA(`mergeCommitSha`)に対する `main` の CI の結果を、マージから 60 分(暫定)まで定期的に読む。**赤になったら、次の順で動く**。
  1. 自動マージを止める(`auto_merge_paused = true`)。
  2. 投入も止める(全体の一時停止と同じ扱い。理由コード `MAIN_CI_RED`)。
  3. dashboard に赤い表示を出し、理由コードと merge commit の SHA だけを出す。
  4. 復旧は人が行う。`main` の状態を確認し、必要なら revert の PR を人が作ってマージする(kivra は revert を作らない。U-6)。`main` が緑になったあと、運用者が再開する(自動では再開しない)。
- **マージ後の検知の限界**: CI が検出しない破損(テストに無い動作の退行など)は、この ADR では自動で検出しない。これは U-9(運用者の定期的な確認)で補う。
- **レート**: C9 の同時 1 件、1 日 3 件。
- **監査**: 自動マージは Run に属するため、`audit_events` に記録する(ADR 0011 §7.1 と同じ)。次の項目だけを記録する。
  - 行動(`AUTO_MERGE_JUDGED`、`AUTO_MERGE_REQUESTED`、`AUTO_MERGE_MERGED`、`AUTO_MERGE_REFUSED`、`AUTO_MERGE_HALTED`)、Run、PR 番号、head SHA、merge commit SHA、理由コード、件数(ファイル数、行数)、判定の対象になったチェックの名前(設定の値)。
  - 運用者の操作の主体は、ADR 0011 §2.9 と同じく、ID の SHA-256 の先頭 12 文字だけを記録する。
  - **記録しない**: PR の題名と本文、Goal と Task の題名と本文、パス、ファイルの内容、チェックの出力の本文、プロバイダーの応答、トークンと秘密。

### 5. 脅威の検討

| 脅威 | 経路 | 対策 |
|---|---|---|
| Goal や Issue の本文によるインジェクション。差分を範囲内に見せる | 本文を読んだ Developer が、範囲内の見かけの変更に、範囲外の内容を混ぜる | 判定は、テキストではなく GitHub の PR の実際の差分で行う(C3)。範囲は `.kt` のテストソースに限る(U-1)。範囲内の内容が悪意を持つ残りのリスクは、`ci.yml` の実行環境と、テストの内容のレビュー(人が行わない)に依存する。これは受け入れる残余リスクとして明記する |
| 許可範囲のすり抜け(実行されるファイル) | シンボリックリンク(`120000`)、実行ビット(`100755`)、リネーム、テストの `resources`、拡張子の偽装 | C3 の mode 検査、`renamed` の禁止、`.kt` のみ、`resources` の除外。パスの表記(`..`、`\`、先頭の `/`)も C3 で拒否する |
| 範囲内の文書による持続的な注入 | 自動マージされた docs が、後の Developer の既定の文脈に入る | docs と README を初回の対象から外す(§2.2) |
| タイミング: CI が緑になったあと、head が変わる | 人が PR ブランチに push する。または、古い check run を読む | C4(head の三者一致)、C5(同じ head SHA の check だけ)、C10(merge API の `sha` 指定) |
| タイミング: CI が緑になったあと、base が変わる | 別の PR のマージで `main` が進み、単独では緑でも、組み合わせると壊れる | 同時 1 件(C9)。マージ後の `main` の CI の監視(§4)で検知する |
| 権限の拡大 | App に merge の権限と `checks:read` が増える。App は kivra の全 PR を操作できる | merge の対象は、Run の binding(PR 番号、node id)と一致する PR だけ(C1)。App のトークンは `kigawa-net/kivra` 1 つに限定されたまま(`repositories` の指定)。`enforce_admins=false` で保護を迂回できるのは管理者だけであり、App はその権限を持たない(App の設定は未確認) |
| 門番の書き換え | 判定のコードを、PR で緩める | マージ判定の本体を禁止領域にする(§2.1 の表)。これらは人が承認し、人がマージする |
| 予算と PR の暴走 | 自動マージが速すぎて、確認が追いつかない | C9 の上限、マージ後の検知による自動停止、ブレーカー |
| 人の見落とし | 自動マージされた PR を、誰も読まない | U-9(運用者が自動マージの一覧を定期的に確認する)。dashboard に、マージ済みの一覧を出す |

### 6. 段階的な導入

順序は、ユーザーの要望のとおり「段階 2 だけを先に有効にする → 実績を見る → 自動マージ」とする。

- **S0(現状。前提の確認)**: 段階 2 の自動承認と PR 作成までを dev で動かす。人がマージする。進むための前提は次のとおり。
  - ADR 0011 §6.3 の段階 1 → 2 の条件を確認する(承認タスクの COMPLETED が 10 件以上。#230 の完了の確認)。現在は**未確認**。
  - ADR 0011 の段階 1 の dev での有効化と E2E(2026-10-18 時点で未実施)。
- **S1(影の判定)**: 自動マージの前提(P1 から P4)を満たしたうえで、C1 から C9 を毎 tick 評価し、「マージ可能と判定」か「理由コード」を、監査と dashboard に出す。マージはしない。進む条件は、判定が出た PR が **20 件以上**、そのうち「マージ可能と判定」した PR を、人が後で問題と判断した件数が **0**。
- **S2(自動マージ。対象は U-1 の範囲だけ)**: 進む条件は、**4 週間**で、自動マージが **10 件以上**、マージ後の `main` の CI の赤が **0 件**、revert と hotfix が **0 件**、C3 で止めた PR を人が確認して、誤検出を分類済みであること。S2 の後の対象の拡大は、別の判断とする。

S1 の前提(P):
- **P1**: `main` の必須ステータスチェックの設定(U-3)。`kigawa-net/infra` の Terraform(`platform/github/variables.tf` の `var.repositories` の周辺)で行う。
- **P2**: App の権限の追加(U-4)。ユーザーの承認が要る。
- **P3**: 実装。判定ロジック、フラグ(環境変数と `auto_merge_paused`、V21 の expand)、監査、マージ後の監視。
- **P4**: S0 の前提の確認。

数値(20 件、10 件、4 週間、60 分、3 件)は、すべて**暫定**である。ユーザーの判断で変えられる(U-5、U-8 と同じ扱い)。

### 7. 却下した案

- **すべての PR を自動マージする**: 却下。ユーザーの決定の範囲外である。
- **bot が PR を承認して、人のレビューの要件を満たす**: 却下。GitHub は、PR の作者が自分の PR を承認することを認めない(一般的な仕様。実装前に確認する)。別のアカウントで承認すると、人の判断を別の自動化に置き換えるだけになる。
- **必須チェックを設定せずに GitHub の auto-merge を有効化する**: 却下。チェックが無いと、PR の作成の直後にマージされる。
- **kivra が `main` に直接 commit する**: 却下(ADR 0008 §3(b))。
- **kivra がテストを自分で実行して、その結果でマージする**: 却下(ADR 0008 §2。kivra は生成されたコードを実行しない。CI に任せる)。
- **自動 revert**: 却下。revert もマージであり、同じ検査が要る。revert の判断は人が行う(U-6)。
- **必須チェックの `skipped` を成功として扱う**: 却下(C5)。

### 8. 結果

- ADR 0008 の不変条件(マージは人が行う)を、方式 A を選ぶ場合は部分的に改定する。改定は、この ADR の承認と同時に行う(ADR 0008 の本文は変えず、追補で示す想定)。
- `GitHubPort` に merge を足す。テストの固定を変える。
- App の権限に `checks:read`(と必要なら merge に必要な権限)を足す。共有基盤の変更であり、承認が要る。
- `kigawa-net/infra` で `main` の必須チェックを設定する。
- kivra に、環境変数、DB の列(V21、expand のみ)、マージ後の監視が増える。
- 監査は Run 単位で増える。

## 未決の点

- **U-1 自動マージの対象範囲**: 推奨は、`kivra-*/src/test/kotlin/**/*.kt` だけ。docs と README.md は初回は対象外(§2.2)。docs を入れるなら、Developer の既定の文脈(`KIVRA_SELFDEV_CONTEXT_PREFIXES`)から外すことを先に決める。
- **U-2 マージの実行者**: 推奨は方式 A(kivra-gh が `sha` 指定で merge API を呼ぶ)。B は管理者の権限と必須チェックの先行設定に依存する。A は ADR 0008 §3(b)(d) の部分改定を伴う。
- **U-3 必須チェック**: 推奨は `test`、`build`、`boot` の 3 件を `main` の必須に設定する(`kigawa-net/infra` の Terraform)。`bad-credentials` の目的は**未確認**で、必須に含めるかは確認してから決める。`deploy to dev` は PR では skipping になるため、必須にしない。
- **U-4 App の権限**: 推奨は `checks:read` の追加。merge に必要な権限が現在の App で足りるかは未確認(足りなければ追加が要る)。共有基盤の変更のため、承認が要る。
- **U-5 上限**: 推奨は、同時 1 件、UTC の 1 日 3 件、保留の上限 60 分。
- **U-6 `main` が赤になったときの扱い**: 推奨は、自動停止(§4)と、revert の判断を人が行うこと。kivra が revert の PR を作る機能は、別の判断とする。
- **U-7 人が止める手段**: 推奨は `kivra:hold` ラベルの導入。
- **U-8 運用者が承認したタスクの PR**: 推奨は、自動マージの対象にしない。運用者の承認は、タスクの投入の承認であり、マージの承認ではない。
- **U-9 マージ後の定期的な確認**: 推奨は、運用者が週 1 回、dashboard の自動マージの一覧を確認すること。実施は運用で行い、機能にはしない。
- **U-10 一時停止のフラグ**: 推奨は、`selfdev_autopilot_control` に `auto_merge_paused` を足す(V21、expand のみ)。環境変数だけでは、運用中に止められない。

## 確認できなかったこと

- App の merge API に必要な権限が、現在の `contents:write` と `pull_requests:write` で足りるか。未確認(GitHub の公式仕様の確認が必要)。
- files API が各ファイルの mode を返すか。返さなければ、head の git tree API で確認する。未確認。
- `required_status_checks` を設定したあとの `mergeable_state` の挙動。未確認。
- ブランチ保護の状態を、kivra の実行時に確認できるか。App は `administration` 権限を持たないため、保護の読み取りはできないと推定する。今回の確認は運用者の `gh` の読み取り権限で行った(2026-10-19)。
- `bad-credentials` のチェックの目的。未確認。
- ADR 0011 §6.3 の段階 1 → 2 の条件の達成。未確認(自動承認の完了件数の集計が無い)。#230 は CLOSED を確認したが、完了か取り下げかは未確認。
- dev の現在の設定(`KIVRA_SELFDEV_AUTOPILOT_ENABLED` などの値)。未確認(k8s の manifest は読んでいない)。
- Runner 環境(`ci.yml` の `test` ジョブ)が、テストの実行中に外部へ接続できるか。未確認。
- GitHub の「PR の作者は自分の PR を承認できない」の仕様。一般的な挙動として扱い、実装前に確認する。

## 見直しの条件

- 自動マージした PR が、マージ後の `main` の CI を赤にしたとき(1 件でも)。S2 を止め、判定の規則を見直す。
- 自動マージの範囲の外の変更が、人の確認で見つかったとき。C3 の検査を見直す。
- 必須チェックの名前が実際の PR と合わないとき、または `skipped` と `neutral` の扱いが期待と違うとき。
- 同時の Run を 2 件以上にするとき。merge queue(§3 の C)を再検討する。
- ADR 0011 の段階 2 の自動承認の数値(失敗率、費用、範囲外の件数)が、§6 の前提を満たさないとき。自動マージの前に、段階 2 の見直しを行う。

## 追記(2026-10-22): 門番自身のテストを禁止領域に入れる

- **きっかけ**: 部品 A(純粋な判定ロジック。#324)のレビューで、§2.1 の表が本体のパスだけを対象にしていることが分かった。対象の範囲 `kivra-*/src/test/kotlin/**/*.kt` の中には、門番自身のテストが含まれる。このままだと、自動承認のタスクが、判定や範囲検査のテストのアサーションを弱める PR(削除行は 200 行以内)を出し、CI が通れば自動マージされうる。
- **決定**: §2.1 の原則「自動マージの門番を、門番自身が書き換えない」を、テスト側にも適用する。次のパターンを禁止領域に追加する。`AutoMergePaths.kt` の `DENYLIST` と完全に一致させる。
  - 門番のテスト: `kivra-*/src/test/kotlin/**/selfdev/automerge/**`、`kivra-*/src/test/kotlin/**/*PatchPolicy*.kt`、`kivra-*/src/test/kotlin/**/*AutoScope*.kt`、`kivra-*/src/test/kotlin/**/*PathRules*.kt`、`kivra-*/src/test/kotlin/**/*DiffCollector*.kt`、`kivra-*/src/test/kotlin/**/*DiffLimits*.kt`、`kivra-*/src/test/kotlin/**/*ArchitectureTest*.kt`、`kivra-*/src/test/kotlin/**/*Invariant*Test*.kt`
  - 禁止領域の本体に対応するテスト: `kivra-policy/src/test/**`、`kivra-github/src/test/**`、`kivra-core/src/test/kotlin/net/kigawa/kivra/core/**`、`kivra-*/src/test/kotlin/**/auth/**`、`kivra-app/src/test/kotlin/net/kigawa/kivra/selfdev/**`
  - 認証・認可・MCP の検査のテスト: `kivra-app/src/test/kotlin/net/kigawa/kivra/mcp/**`、`kivra-app/src/test/kotlin/net/kigawa/kivra/routes/**`
- **結果**: 対象に残るのは、上のどれにも当たらないテストだけになる(例: `kivra-app/src/test/kotlin/net/kigawa/kivra/util/XTest.kt`)。最初は狭く始め、S1(影の判定)の実績を見てから、広げるかを別に判断する。
- **照合の違い**: 禁止領域の照合は大文字小文字を区別しない。対象の範囲の照合は区別する(実装どおり)。理由: 大文字小文字だけ違う名前を、禁止領域から漏らさないため。範囲の照合で区別しても、大文字小文字だけ違うパスは範囲の外として拒否される。
- **残るリスク**: 対象に残るテストでも、テストを弱めることは可能である。C3 の上限(3 ファイル、200 行)と、S1 の影の判定で、人が確認する。
- **見直しの条件**: S1 の影の判定で、人が確認して「自動マージしてはいけなかった」PR が 1 件でも出たら、対象を見直す。

## 追補(2026-10-23): U-10 の一時停止(部品 C)を実装する

- **列の番号**: 本文の U-10 は `V21` と書いたが、V21 は既に別の用途(自己開発のメモリ)で使われている。実装は **V31**(`auto_merge_paused BOOLEAN NOT NULL DEFAULT FALSE` を `selfdev_autopilot_control` に足す expand のみ)。V1 から V30 は編集しない。
- **止め方と再開**: `POST /api/selfdev/autopilot/auto-merge/pause` と `.../auto-merge/resume`。全体の一時停止と同じ認可(運用者の認証、同一オリジンの JSON、有効化の確認)で、AI の主体は 403 `forbidden`(ADR 0019)。再起動は要らず、次の tick から効く。`GET /api/selfdev/autopilot` の `autoMergePaused` に現在の値が出る。
- **影の判定の動き**: `AutoMergeShadowWatch` は、一時停止の間、GitHub を読まず判定もしない。Run ごとに `AUTO_MERGE_SHADOW_PAUSED`(結果 `PAUSED`)を 1 件記録し、同じ状態のあいだは繰り返さない。解除後の最初の tick は、判定を新しく記録する。shadow は読み取り専用のままで、マージ、ラベル、コメントは行わない(アーキテクチャテストの書き込みの検査は変えない)。
- **画面**: 「自動マージ: 一時停止中 / 一時停止していません」を表示する。操作のボタンは作らない。
- **範囲外(変えていない)**: 判定ロジック(C1 から C9)、フラグの有効化(k8s 側)、実際のマージ、`main` の CI が赤になったときの自動停止(§4 の検知。別の作業)。
- **テスト**: H2 と MariaDB の両方で、repository、ルート(AI の拒否と運用者の切り替え)、影の判定(一時停止中の無読取り、記録の重複なし、解除後の再判定)を確かめる。

## 追補(2026-10-23): 再束縛した PR の扱い(#351)

- **対象**: #276 の基点追従(`PrBranchRebaser`)で、PR の head を Run の記録の上で再束縛した場合の、自動マージの判定(C4)と PR 本文の `kivra-head-sha:` マーカーの関係。#351 の 3 項目(マーカーの食い違い、ポリシー結果の再実行、再束縛の経路の読み取りの上限)を扱う。
- **状態**: 提案。この追補では実装しない。shadow を本番化する前に決める。
- **実装状況(項目 3、2026-10-24)**: 案 P を実装した。再束縛の経路に tick 単位の GitHub 読み取りの予算(既定 40、`KIVRA_SELFDEV_PR_REBASE_MAX_READS_PER_TICK`)を置き、予算を超えた Run は書き込まずに次の tick に回す。項目 1 と項目 2 は未実装である。
- **前提**: 作業ツリーの基点は origin/main の 5beb51b。以下の行番号は、この時点のもの。

### 1. 現状の事実(コードで確認)

- **再束縛の経路**: `PrBranchRebaser.look` は、PR の head が記録と違うとき `verify` を通し、通れば `rebind` を呼ぶ(`selfdev/PrBranchRebaser.kt` 97〜101 行)。`updateIfBehind` も、検査を通った新しい head に対して同じ `rebind` を呼ぶ(149 行)。
- **`rebind` は GitHub に書き込まない**: `rebind`(315 行付近)は `DevelopmentRunRepository.rebindPullRequestHead` を呼ぶだけである。この関数(`db/DevelopmentRunRepository.kt` 651 行付近)は `pr_head_sha` を新しい head に変え、監査行 `PR_REBASE_REBOUND` を同じトランザクションで書く。監査の payload は head を先頭 12 文字で持つ。
- **本文マーカーは作成時にだけ書かれる**: 本文は `kivra-github/.../GitHubPort.kt` 158 行の `renderedBody()` で作られ、`HttpGitHubAdapter.kt` 426 行付近の `createPullRequest`(POST)だけが送る。PR 本文を後から更新する呼び出しは無い。`HttpGitHubAdapter.kt` 207 行の `patch` は、641 行の `updateRef`(ブランチの参照)だけが使う。`PrBranchPort.updatePrBranch`(937 行付近)は `PUT .../update-branch` で、本文を送らない。
- **書き込みの範囲**: `HttpGitHubAdapterTest.kt` 62 行の反射の検査は、`GitHubPort` のメソッド名に merge、force、push、delete、update、remove、close を含まないことを固定している。update-branch は、この検査の対象外の別ポート `PrBranchPort` に置かれている。App の権限(`GitHubAppTokenMinter.kt` 32 行)は `contents:write` と `pull_requests:write`。
- **判定の読み方**: `AutoMergeFactsAssembler.kt` 112 行の `markerHeadSha = headMarkerOf(pr.body)`。`headMarkerOf`(196〜206 行付近)は、`kivra-head-sha:` の行が 1 つだけで、40 桁の小文字の 16 進のときにその値を返し、それ以外は null を返す。`AutoMergeDecision.kt` 301〜305 行の `head()`(C4)は、`markerHeadSha` と、Run の binding の head と、PR の現在の head が同じときだけ通す。
- **再束縛後の結果**: DB の binding は新しい head、PR の head も新しい head、本文のマーカーは作成時の古い head になる。C4 はこの組を `HEAD_MISMATCH` で拒否する。したがって、**誤って自動マージされる経路は無い**。ただし、再束縛した PR は必ず拒否され、理由が `HEAD_MISMATCH` として残る。これは意図して決めた結果ではなく、C4 の規則の帰結である。
- **テスト**: `AutoMergeShadowTest.kt` 294〜296 行の「本文のマーカーが head と違う」は、マーカーの食い違いが拒否されることを確かめる。再束縛の経路を通した Run の判定のテストは無い(#351 の完了条件に未対応)。
- **ポリシー**: `PrBranchRebaser.kt` 53 行に「The policy result is not run again.」とある。ADR 0014 の C1〜C10 は、PatchPolicy の結果を条件にしていない。
- **読み取りの上限**: 影の判定には `AutoMergeShadowWatch.kt` 52 行と 134 行の `MAX_READS_PER_TICK = 40` がある。再束縛の経路には、読み取りの予算が無い。1 回の `look` の読み取りは、更新の判定で数件、更新のあとの `observeNewHead` で最大 `observePolls`(既定 5)回の PR 読み取り、`verify` で数件である。総数は PULL_REQUEST の Run の数に比例し、tick あたりの上限は無い。

### 2. 3 項目の選択肢

**項目 1: 再束縛後のマーカーの食い違い**

| 案 | 内容 | 利点 | 欠点 |
|---|---|---|---|
| A: 再束縛時にマーカーも更新する | `rebind` の前後に、PR 本文の `kivra-head-sha:` の行だけを書き換える(`PATCH /pulls/{n}`) | C4 の規則を変えない。再束縛した PR も判定の対象になる | 外部への書き込みが増える。本文の全体を送るため、人の編集と競合すると本文を壊しうる(読み直しと比較が要る)。`pull_requests:write` で足りるかは公式仕様で**未確認**。同じ値を再送しても結果は変わらないが、読み直しの競合は残る。順序を誤ると、マーカーだけ古い状態が残る(判定は拒否するため安全側だが、再試行の規則が要る) |
| B: 判定側で再束縛の履歴を認める | Run が記録する再束縛後の head と、古いマーカーの対応を、C4 の一致の代わりに認める | GitHub への書き込みが増えない | C4(三者一致)を緩める。監査行は head を 12 文字しか持たないため、完全な SHA の照合には列の追加が要る(expand のみ)。判定の規則の改定になり、ADR の改定が要る |
| C: 再束縛した PR を自動マージの対象外にする | 再束縛の履歴のある Run を、判定で固定の理由コード(例: `REBOUND_NOT_ELIGIBLE`)で拒否する | 最も安全で単純。書き込みも規則の改定も無い | 再束縛した PR は自動マージされない。自動マージの件数(§6 の S2 の 10 件の条件)に影響しうる。現状の `HEAD_MISMATCH` の拒否と結果は同じで、理由が明示されるだけ |

**項目 2: ポリシー結果の再実行**

| 案 | 内容 | 利点 | 欠点 |
|---|---|---|---|
| X: 再束縛後の head に PatchPolicy を再評価する | 新しい head の内容から PatchSet を組み立て、ポリシーを評価し直す | 内容の検査を、新しい head に対して行う | ポリシーの入力を GitHub の内容から組み立てる実装が要る。読み取りが増える。新しい head は main の変更を含むため、結果が再束縛の前と変わりうる |
| Y: 再評価しないことを明記する | ADR に、再束縛ではポリシーを再評価しないこと、その理由と残るリスクを書く | 変更が無い | `verify` の (b) は PR 自身の変更の同一性を確かめるが、ブロブ全体は比べない(main の変更と、コンテキスト行の変更は許す)。内容全体を見る規則なら、結果が変わりうる |

**項目 3: 再束縛の経路の読み取りの上限**

| 案 | 内容 | 利点 | 欠点 |
|---|---|---|---|
| P: tick 単位の読み取り予算 | 影の判定の `ShadowReadMeter` と同じ考え方で、再束縛の経路に 1 tick あたりの予算を置く。超えたら、その Run は次の tick に回す | 既存の考え方を流用できる。上限が明確 | 予算の値を決める必要がある。Run が多いと、再束縛が遅れる |
| Q: 検査の結果をキャッシュする | `overlapCache` と同じく、`verify` の結果を (旧 head、新 head、main の head) の組で持つ | 同じ組の再検査が無くなる | 組が変わるたびに読み取りが増えるため、効果は限定的。上限にはならない |

### 3. 推奨と理由

- **項目 1: 案 C**。理由: (i) 現状でも `HEAD_MISMATCH` で安全側に倒れており、C は結果を変えずに理由を明示するだけである。(ii) 外部への書き込みを増やさない。(iii) 変更が小さく、テストで固定できる。案 A は、本番化のあとに、再束縛した PR を自動マージの対象にする価値が確かめられてから、別の PR で検討する。案 B は、C4 の三者一致を緩めるため、今回の段階では採らない。
- **項目 2: 案 Y**。理由: ポリシーは Run の作成時の判定であり、ADR 0014 の自動マージの条件ではない。自動マージの対象は U-1 の範囲(テストのソース)に限られ、C3 が、マージの時点の実際の差分を files API で再検査する。ただし (b) がブロブを比べない点は、残るリスクとして明記する(U-12)。
- **項目 3: 案 P**。理由: 変更が小さく、既存の考え方と揃う。予算の超過は再束縛の時期を遅らせるだけで、安全性は変わらない。
- **全体の方針**: 判断に迷うときは拒否して人に残す。外部への書き込みは、必要が確かめられるまで増やさない。

### 4. 未決の点

- **U-11 再束縛した PR を自動マージの対象にするか**: 推奨は対象外(案 C)。対象にするなら、案 A か B を別に決める。§6 の S2 の件数に影響するため、ユーザーの判断が要る。
- **U-12 ポリシー結果の扱い**: 推奨は案 Y(再実行しないことを明記する)。再実行するなら、ポリシーの入力の組み立て方を別に決める。
- **U-13 読み取りの予算の値**: 推奨は、案 P の予算を、影の判定の `MAX_READS_PER_TICK`(40)とは別に置き、値は dev での実測の後に決める。
- **U-14 本文のマーカーの更新(案 A を採る場合)**: 本文の書き込みは外部への新しい書き込みである。権限の確認(`pull_requests:write` で足りるか)と、人の編集との競合の扱いを決める。ユーザーの承認が要る。

### 5. 実装を分けるときの PR の単位と進む条件

1. **PR-1(判定の明示、案 C)**: 再束縛の履歴のある Run を、判定で固定の理由コードにより拒否する。テスト: 再束縛した Run の影の判定が拒否されること。既存のマーカー食い違いのテストは残す。進む条件: 本追補のマージ、U-11 の決定。
2. **PR-2(読み取りの予算、案 P)**: 再束縛の経路に tick 単位の予算を置く。テスト: 予算の到達で、その Run が次の tick に回ること。進む条件: PR-1 のマージ、U-13 の決定。
3. **PR-3(マーカーの更新、案 A。採る場合のみ)**: 権限の確認、読み直しと比較の設計、失敗時の再試行の規則を先に別の追補で決める。進む条件: U-14 の承認。
4. 各 PR は、CI がすべて緑であること、ユーザーがマージを個別に承認することを条件とする。shadow の解除(本番化)は、別途ユーザーの承認が要る。

### 6. ロールバック

- **本追補**: PR を revert する。
- **PR-1**: revert で判定の規則が元に戻る。DB の変更は無い。
- **PR-2**: revert で予算が無くなる(読み取りは元に戻る)。
- **再束縛の経路**: `KIVRA_SELFDEV_PR_REBASE_ENABLED` を off にすると、新しい再束縛は止まる(既定は off)。ただし、既に再束縛した Run の `pr_head_sha` は戻らない。戻すには、GitHub 上の正しい head を読み直し、運用者が DB を更新する。監査は 12 文字しか持たないため、完全な SHA は GitHub から取り直す。
- **案 A を採った場合**: 本文の書き換えは revert では戻らない。書き換えの前の本文をどう残すかは、U-14 の中で決める(本文の全体を監査に残すと、個人の情報の扱いが要る)。

### 確認できなかったこと

- `pull_requests:write` で PR 本文の更新が成り立つかの公式仕様(案 A の前提)。
- 再束縛の経路の読み取り回数の実測(dev では観測していない)。
- PULL_REQUEST の Run を列挙するときの件数の上限(`SelfDevRunner` の tick 側は未確認)。
- dev の DB に、再束縛した Run が実在するか、その判定の記録(DB は読んでいない)。
- ADR 0019 には、再束縛と本文マーカーに関する記述は無い(0019 の関連は、operator の認可と監査のみ)。

### 見直しの条件

- 再束縛した PR を自動マージの対象にする要望が出たとき(U-11)。
- 読み取りの上限に達して、再束縛が遅れた実績が出たとき(U-13)。
- C4 の規則(マーカーの読み方)を変えるとき。
