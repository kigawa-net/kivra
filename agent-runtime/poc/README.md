# ADR 0018 PoC: OpenCode で docs の 1 行修正を完走できるか

対象: [ADR 0018](../../docs/adr/0018-opencode-executor.md) §8 フェーズ 0(実装の前の PoC)。運用者の PC の dev で、kivra-agent の使い捨てサンドボックス(rootless docker、読み取り専用の root、認証情報なし、proxy 経由)に、OpenCode を 1 回だけ起動する。コードは変えない。

スクリプト: [`opencode-poc.sh`](./opencode-poc.sh)。実行は 1 回。

## 目的

次の 6 点を確かめる(ADR 0018 §8 の (1)〜(6))。

1. 非対話で完走し、終了コードが取れるか
2. `--format json` の出力の形(イベントの型とキー名)
3. 許可の設定(`"*": "deny"` を基準に、read、edit、glob、grep だけ allow)で、承認の要求が出ないか
4. 無料枠のモデルが、認証なしで呼べるか(U-3)
5. 通信先(`opencode.ai` の経路)と、想定外の通信(拒否ログ)
6. 差分が、`docs/` の 1 行だけになるか(PatchPolicy に通る形か)

## 前提(実行の前に、すべて満たす)

- **U-1(データの扱い)を先に済ませる。** 無料枠のモデルは、入力を学習に使う場合がある(ADR 0018 §5)。使うモデルの提供元の記述の原文を運用者が確認し、registry の `dataHandling` を `CONFIRMED` にする。確認できないモデルは使わない。
- **入力は公開の内容だけ。** このスクリプトのタスクは、`docs/selfdev-hello.md` の末尾に 1 行を追記する固定文である。秘密、非公開の内容、Issue の本文は入れない。
- **モデル ID を選ぶ(U-2)。** `OPENCODE_POC_MODEL` に、`opencode/` の後の ID を指定する。ADR の候補(`glm-5.3-flash-free`、`deepseek-v4.1-flash-free`、`minimax-m3.1-flash-free`、`longcat-2.5-preview-free`)は、いずれも原文の確認が未了である。
- **検証用のイメージを用意する。** 名前は既定で `kivra-agent-opencode-poc:local`(`KIVRA_AGENT_POC_IMAGE` で変えられる)。`opencode` が PATH にあり、ENTRYPOINT を上書きできる必要がある。**このリポジトリには、このイメージの Dockerfile は無い**(インストールの方法と版の固定は未確認。U-10)。本番の `kivra-agent-runtime:local` は使わない。
- **kivra-agent が install 済み**である(`/opt/kivra-agent`、ユーザー `kivra-agent`、`/var/lib/kivra-agent/work`、ルートレス docker、`kivra-agent-internal` ネットワーク、proxy が稼働中)。
- **origin/main を最新にする。** `git fetch origin main`。スクリプトは `origin/main` を展開する(ローカルの作業内容は使わない)。
- **claim を止める。** 実行中の attempt がある状態では始めない。スクリプトは実行中の kivra の容器があると止まる。一時的な許可は proxy を共有する全ての実行に効くので、`sudo kivra-agentctl stop` で止めてから行う。

## 手順(1 回の実行)

```
sudo OPENCODE_POC_MODEL=<model id> bash agent-runtime/poc/opencode-poc.sh
```

確認を待つ箇所は 2 つある。

1. **最初の確認(`y`)。** 実行する内容(作業ディレクトリ、タスク、モデル、タイムアウト)を表示する。`y` で続ける。
2. **一時的な CONNECT の許可(Enter)。** スクリプトは次を**案内するだけ**で、自分では行わない。運用者が手で行い、Enter を押す。
   - a. 編集するのは**インストール先**の `/opt/kivra-agent/agent-runtime/proxy/allowlist.txt` の末尾に `opencode.ai` の 1 行を足す。リポジトリの `allowlist.txt` は変えない(コミットしない)。
   - b. proxy イメージを作り直す(`install.sh` の 179 行と同じ `docker build`)。
   - c. proxy を再起動する(`sudo kivra-agentctl start` など)。
   - d. `sudo bash agent-runtime/poc/opencode-poc.sh --check-allowlist` で、`opencode.ai` が「ある」と表示されることを確認する。スクリプトも、Enter の後に同じ確認をして、無ければ止まる。

Enter の後、スクリプトは次を行う。

- 使い捨ての作業ディレクトリを作る。`origin/main` の `git archive` を展開する(`.git` なし)。場所は `/var/lib/kivra-agent/work/poc-<時刻>`(0777。親の work は 0700)。
- `.kivra/task.json` を書く(タスクの文は固定。タイトルは「`docs/selfdev-hello.md` の末尾に、空行と「> kivra opencode poc」の 1 行を追記する。それ以外のファイルは変更しない」、本文は空)。
- コンテナを 1 回だけ起動する。引数は `DockerCommandBuilder`(kivra-agent)と同じ順序、同じ値(`--user=10001:10001`、`--read-only`、tmpfs、`--cap-drop=ALL`、`--security-opt=no-new-privileges`、`--network=kivra-agent-internal`、作業ディレクトリだけの bind mount など)。違いは後述。
- OpenCode は `opencode run --format json -m opencode/<model> --dir /work "<固定文>"` で起動する。`--auto` は使わない。許可は `OPENCODE_CONFIG_CONTENT` でインラインに与える(`"*": "deny"`、read、edit、glob、grep だけ allow。bash、task、webfetch などは deny)。`autoupdate` は false。
- タイムアウト(既定 900 秒)で `timeout` が切り、スクリプトが容器を kill する。

## 表示される結果

1. **終了コード。** 0 なら完走。124 はタイムアウト。
2. **出力の量。** 行数、バイト数。stderr の中身は表示しない。
3. **出力の形式。** 先頭 5 行について、行の `type` とキー名だけを表示する(本文は出さない)。JSON でない行は長さだけ。
4. **承認の要求の兆候。** 文字列 `permission` を含む行の数。`--auto` を使っていないので、0 が期待値。
5. **proxy の拒否ログ。** 拒否された通信の、メソッド、ホスト名、理由(`not_allowlisted` など)。パスは `(hidden)`。
6. **変更されたファイル。** `pristine`(`origin/main` の展開)との比較。`.kivra` は除く。統計(`git diff --no-index --shortstat`)。`docs/selfdev-hello.md` 以外の変更があれば注意を出す。

## 期待する結果

- 終了コード 0。
- 出力の行に、OpenCode のイベントらしい型があり、最後に完了の型がある(型の名前は未確認。記録する)。
- 承認の要求の兆候は 0。
- 拒否ログは、`opencode.ai` 以外に、通信先が無い、または少数(モデル一覧、更新、共有、リモート設定などの試み)。**これらの試みが拒否されたこと自体が、ADR §4 の「想定外の通信が無い」の確認の材料になる**。
- 変更は `docs/selfdev-hello.md` の末尾に 1 行の追加だけ。統計は `1 file changed, 1 insertion(+)` 程度。

## 失敗した場合の読み方

| 症状 | 読み方 | 次に見ること |
|---|---|---|
| 終了コード 124 | 時間切れ。OpenCode が止まった、または通信が待ちになった | 拒否ログ、出力の最後の行。U-11 の時間の見直し |
| 終了コード非 0、stderr の形式に `api key`、`unauthorized`、`401` | 無料枠に認証が要る可能性(U-3) | 認証なしの経路が無いなら、OPENCODE 経路は断念(ADR §4) |
| 拒否ログに `opencode.ai` があり `not_allowlisted` | 一時的な許可が効いていない | 手順 a〜d の確認。`--check-allowlist`。proxy の再起動 |
| 拒否ログに `opencode.ai` は無く、接続の失敗が stderr に出る | OpenCode が proxy を使っていない(HTTPS_PROXY を見ていない可能性、未確認) | 内部ネットワークには経路が無いので、直接の接続は失敗する。実行環境(OpenCode の proxy 対応)を確認 |
| 出力に `permission` の行があり、承認待ちで止まる | `ask` の挙動が headless で想定と違う、または許可の書き方が効いていない | 許可の設定の形(read のパターン形式は未確認)。`ask` は使っていないので、設定の解釈を確認 |
| 変更が `docs/` 以外、または `opencode.json` や `.opencode/` にある | 許可の迂回、または作業の誤り。PatchPolicy では拒否される形 | 出力を確認し、ADR §3 の拒否パスの範囲を見直す |
| 変更が無い | 完走したが編集していない | 出力の type の並び。モデルの応答の有無 |
| `opencode --version` が失敗 | イメージに `opencode` が無い、PATH が違う | イメージの作り方(U-10) |

## 後始末

- **作業ディレクトリと記録は自動で消える**(`trap`)。`KEEP=1` を付けた場合は残るので、`sudo rm -rf` で消す。記録には、タスクの文と、リポジトリの内容が含まれる。
- **容器。** 終了時に `kivra-poc-<時刻>` を kill する。残っていないか `sudo -u kivra-agent env DOCKER_HOST=unix:///run/user/$(id -u kivra-agent)/docker.sock docker ps -a --filter label=kivra-agent-poc=1` で確認する。
- **一時的な許可を戻す。** スクリプトが終了時に次を表示する。
  1. `/opt/kivra-agent/agent-runtime/proxy/allowlist.txt` から `opencode.ai` の行を削除する。
  2. proxy イメージを作り直す(`docker build -t kivra-agent-proxy:local`)。
  3. proxy を再起動する(`sudo kivra-agentctl start`)。
  4. `sudo bash agent-runtime/poc/opencode-poc.sh --check-allowlist` で「ない」と表示されることを確認する。
- 戻したら、`sudo kivra-agentctl resume`(claim を止めた場合)。
- リポジトリに変更が無いことを `git status` で確認する(このスクリプトはリポジトリの中身を変えない)。

## PoC の結果を ADR 0018 に反映する

| ADR の項目 | 反映の仕方 |
|---|---|
| U-1(データの扱い) | PoC の前に確定しているはず。結果は registry の `CONFIRMED` の根拠に、`confirmedBy`、`confirmedAt`、`sourceUrl` を記録する |
| U-2(モデル) | 完走したモデル ID を 1 つ記録。品質の比較はしない(サンプルが 1 件のため) |
| U-3(認証) | 認証なしで完走したなら、(a) を確認。キーを要求されたなら、OPENCODE 経路は断念(ADR §4) |
| U-6(proxy の範囲) | 拒否ログのホスト名の一覧。`/opencode/...` の 1 経路で足りるかの判断材料(パスは CONNECT では見えない。下の「判断が要る点」) |
| U-8(`--auto`) | 承認の要求が 0 なら、`--auto` なしの方針を確認 |
| U-10(版と配布) | 使ったイメージの作り方、`opencode --version`、取得の方法とハッシュを記録 |
| U-11(時間の上限) | 完走までの所要時間(秒)を記録 |
| §8 フェーズ 1 の進む条件 | (1)〜(6) が確認でき、拒否ログに想定外の通信が無いこと |

## 判断が要る点(ADR 0018 に無い、または未確認)

このスクリプトを作るときに、推測で進めず、次を未確認のまま残した。実行の前、または結果を見た後に決める。

1. **OpenCode のイメージ。** このリポジトリに Dockerfile は無い。インストールの方法、版の固定、ハッシュの検査は PoC の前に決める(U-10)。
2. **拒否ログはホストまでしか見えない。** proxy は CONNECT だけを扱うので、パスは見えず、`(hidden)` と表示する。ADR の `/opencode/...` の経路は、まだ無い。また、**許可された通信はログに出ない**ので、表示されるのは拒否の記録だけ。`opencode.ai` への通信が通ったかどうかは、終了コードと出力で判断する。
3. **HTTPS_PROXY を OpenCode が使うか未確認。** Bun や Node の fetch は、環境変数の proxy を既定では使わないことがある。使わない場合、内部ネットワークから直接は出られないので、通信が失敗する(結果の表の 4 行目)。
4. **read のパターンの書き方は未確認。** `"read": {"*": "allow", "*.env": "deny", ...}` の形は、公式の permissions の記述で確認できていない。通らない場合は、`read: allow` にして、`.env` が作業ディレクトリに無いことを、リポジトリの内容で保証する(公開の archive には `.env` は無いはず)。
5. **`share` とリモート設定の無効化の名前は未確認。** 設定に入れていない。拒否ログに、共有やリモート設定の通信が出るかで確かめる。
6. **ANTHROPIC の環境変数を入れている。** `DockerConstants.ENV_NAMES` の 6 つは、`DockerArgsGuard` が必須にしている。OpenCode は使わないので、本番の OPENCODE 経路では `ENV_NAMES` の見直しが要る(ADR の実装のフェーズ 1 で決める)。
7. **許可リストの一時的な変更は、インストール先の複製に対して行う。** リポジトリは変えない。ただし proxy は共有で、一時的な許可は全ての実行に効く。claim を止めてから行う。
8. **タイムアウトの既定 900 秒。** U-11 の値は、PoC の所要時間を見てから決める。
9. **モデルの ID を、スクリプトは既定にしていない。** U-2 で運用者が選ぶ。

## テスト

- `bash -n agent-runtime/poc/opencode-poc.sh`(構文)。
- `shellcheck` は、このマシンに無いので未実施。
- `DRY_RUN=1 OPENCODE_POC_MODEL=<id> bash agent-runtime/poc/opencode-poc.sh` で、実行する手順と引数を表示する。sudo、docker、opencode、git archive は呼ばない。ファイルは作らない。
- 実際の実行(PoC)は、このリポジトリの作業では行っていない。
