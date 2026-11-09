# /ui/compose のヘッドレスブラウザ確認手順

Compose Wasm UI(`/ui/compose`)を、ローカルのヘッドレス Chrome で確認する手順。
主な方法は **実サーバ(kivra-app)に対して全画面を巡回する** こと(§A)。モック(§B)は起動だけを見る補助。

モックでは API の形のずれ(モデルと実応答の不一致)を見逃す。実際に「ナビは出るが中身が空」の不具合は、モックでは
空にならず、実サーバでのみ再現した。画面の確認は必ず §A を使う。

## A. 実サーバで全画面を巡回する(主な方法)

構成: kivra-app を `KIVRA_AUTH_MODE=oidc-jwt` で起動する。JWKS と RS256 トークンは `scripts/compose-ui-crawl/oidc-local.mjs`
が作る(外部の IdP は使わない)。DB は H2 のファイル。selfdev・自律運転・order・記憶・ロールは有効にし、GitHub と
Anthropic の資格情報はダミー(外部への呼び出しは失敗する。画面の確認には影響しない)。
本番の既定値は変えない。すべてのローカル設定は `scripts/compose-ui-crawl/run-local-server.sh` の中の環境変数に閉じている。

作業ディレクトリ(鍵、H2 のファイル、レジストリ)は `KIVRA_LOCAL_DIR`(既定 `~/.cache/kivra-local`)。リポジトリには入らない。

### A-1. kivra-app をビルドする

```bash
./gradlew :kivra-app:installDist
```

終了コードを必ず確認する(`BUILD SUCCESSFUL`)。Compose Web の wasm も一緒にビルドされる。

### A-2. サーバを起動する

```bash
scripts/compose-ui-crawl/run-local-server.sh     # フォアグラウンド。止めるのは Ctrl-C
```

別のターミナルで、起動を確認する。

```bash
curl -s http://127.0.0.1:18080/health              # {"status":"ok"}
```

注意:
- H2 は `testImplementation` のみなので、スクリプトが H2 の jar(Gradle キャッシュ内の `h2-2.3.232.jar`、または `H2_JAR`)を
  classpath に足す。本体のビルドは変えない。
- 起動時に JWKS(`http://127.0.0.1:18090/certs`)を別プロセスで立てる。既に動いていれば立てない。
  **同じマシンで別の起動が 18090 を使っていると、その JWKS を流用して 401 になる。** その場合は `PORT`・`OIDC_PORT` で
  別のポートにし、トークンを作るコマンド(`seed-local.sh`・`crawl.mjs`)にも `OIDC_ISSUER=http://127.0.0.1:<OIDC_PORT>/realms/local` を渡す。
  他の起動のプロセスは止めない(止めるのは自分が起動した PID だけ)。
- 再起動のたびに FX の基準日は今日になる(期限切れの警告を避けるため)。

### A-3. サンプルデータを入れる

```bash
scripts/compose-ui-crawl/seed-local.sh
```

プロジェクト 2 件、開発目標 3 件、order 2 件、Run 1 件を API の POST で入れる。空の DB に一度だけ流す。

### A-3b. 未分類(project_id が NULL)のデータを作る

API は project_id の無い作成を拒否する(ADR 0022 PR-C)ため、未分類のデータは H2 の UPDATE で作る。**サーバを止めた状態**で一度だけ実行する
(H2 のファイルは、サーバが開いている間は使えない)。

```bash
kill <サーバの PID>                                 # PID を指定して止める(pkill -f は使わない)
scripts/compose-ui-crawl/seed-unassigned-local.sh   # 開発目標「監視を追加」と order「未分類に回る order(検証用)」を NULL にする
scripts/compose-ui-crawl/run-local-server.sh &      # 同じ作業ディレクトリで起動し直す(再シードはしない)
```

Run は、シードの「シミュレーション」の Run が最初から NULL(プロジェクトを持たない)。記憶は API で作れないので、未分類の記憶は 0 件のまま。
確認: `GET /api/runs?projectId=unassigned`・`/api/selfdev/goals?projectId=unassigned`・`/api/selfdev/orders?projectId=unassigned` が 1 件以上返ること。

### A-4. 巡回する

```bash
cd scripts/compose-ui-crawl
npm install                                         # puppeteer-core(初回のみ)
OUT=/path/to/out node crawl.mjs                     # 全画面
OUT=/path/to/out.png node projects-error-check.mjs   # /api/projects を 500 にして、ヘッダーのエラー表示と再試行を確認
ONLY=home,goals OUT=/path/to/out node crawl.mjs     # 一部だけ
```

- 巡回の対象(`crawl.mjs` の `screens`)は、改訂 2 の構成(docs/design/16-site-structure.md)に合わせてある。
  - 入口と全体: `home`(`#/`)、`projects`、`budget`・`autopilot`・`assignees`(全体)。
  - 旧 URL: `legacy-tasks`・`legacy-goals`・`legacy-approvals`(`#/tasks` など)。プロジェクトが 1 つに決まれば、その配下の URL に
    置き換わる。決まらなければ、プロジェクト一覧が案内文つきで出る。
  - 未分類(疑似プロジェクト。`#/projects/unassigned/<page>`): `unassigned-tasks`・`unassigned-goals`・`unassigned-orders`・
    `unassigned-approvals`・`unassigned-memory`・`unassigned-run-detail`。作成フォームは出さず、理由の一文が出ること。
    `unassigned-dashboard`(`#/projects/unassigned/dashboard`)は「このリンクは開けません」になること。
  - プロジェクト配下: `proj-dashboard`・`proj-tasks`・`proj-goals`・`proj-orders`・`proj-approvals`・`proj-memory`・
    `proj-assignees`・`proj-budget`(`#/projects/<id>/<page>`)。
  - プロジェクトの中の詳細: `proj-goal-detail`・`proj-order-detail`・`proj-order-cycles`・`proj-run-detail`。
    旧い詳細 `legacy-goal-detail`・`legacy-run-detail`(`#/goals/<id>` など)も同じ画面を開く。
  - 壊れたリンク `invalid`(`#/projects/%ZZ/tasks`)は「リンクが正しくありません」。
  - 各スクリーンショットで確認すること: 左のナビが「ホーム・プロジェクト」「そのプロジェクトの節」「全体」の 2 層になっていること、
    上のパンくずが「プロジェクト › <名前> › <ページ> › <詳細>」になっていること、URL(ハッシュ)が上の表どおりであること。
    パンくずは折り返す(横スクロールにしない)。1280px 幅で末尾の id が切れないこと。
- `CHROME`(既定 `/usr/bin/google-chrome`)で Chrome を指定できる。
- 各画面を開くときは `Authorization: Bearer` を全リクエストに付ける(本番の oauth2-proxy の代わり)。
- 出力: `<画面>.png`(スクリーンショット)、`summary.json`(API の 4xx/5xx と本文、ページの例外、コンソールのエラー)。
- Compose Web は canvas に描くので、DOM のテキストは取れない。文字の確認はスクリーンショットで行う(`.txt` は空になる)。
- 巡回はテストではない(アサーションなし)。重いので CI では回さない。

### A-5. 合格の目安

- `summary.json` の `pageErrors` が空、`apiFailures` が空(想定外の 4xx/5xx がない)。
- `consoleErrors` は favicon 404 などのノイズのみ。
- スクリーンショットで、各画面に項目(見出し・一覧・詳細・フォーム)が描かれている。データが無いことによる空表示
  (例: 承認待ち 0 件、Run が無いプロジェクト)は正常な空表示として扱う。

## B. モックで起動だけ確認する(補助)

本番の `UiRoutes.kt` と同じパス・同じヘッダ(CSP、`X-Content-Type-Options`、`Referrer-Policy`、`Cache-Control: no-store`)で
`ui/compose` を返す小さなサーバを使う。CSP は `UiRoutes.kt` の `COMPOSE_UI_CSP` と一致させること。
`/api/*` と `/health` は実バックエンドがないので、401 か `[]` などのスタブで十分。
(Node の `http` で数十行。`/`、`/ui`、`/ui/` は `/ui/compose` へ 302、`/ui/compose(/)` は `index.html` を返す。)

```js
// check.mjs: node check.mjs http://127.0.0.1:<port>/ui/compose
import puppeteer from 'puppeteer-core';
const browser = await puppeteer.launch({
  executablePath: '/usr/bin/google-chrome', headless: true,
  args: ['--no-sandbox', '--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 800 });
page.on('console', (m) => console.log(`[console.${m.type()}] ${m.text()}`));
page.on('pageerror', (e) => console.log('[pageerror]', String(e)));
await page.evaluateOnNewDocument(() => {
  document.addEventListener('securitypolicyviolation', (e) => console.log('[csp]', e.violatedDirective, e.blockedURI));
});
await page.goto(process.argv[2], { waitUntil: 'load' });
await new Promise((r) => setTimeout(r, 10000));
console.log(JSON.stringify(await page.evaluate(() => ({
  title: document.title,
  canvas: !!document.querySelector('#ComposeTarget canvas'),
  styleTags: document.querySelectorAll('style').length,
}))));
await page.screenshot({ path: process.argv[3] || 'compose.png' });
await browser.close();
```

合格の目安: `[pageerror]` と `[unhandledrejection]` が出ない。`[csp]` が出ない。`styleTags` は 0。
`#ComposeTarget canvas` があり、ナビゲーション(Kivra、ホームなど)が描かれている。
モックでは本文の空表示は確認できない。本文の確認は §A で行う。

## 既知の注意

- 日本語フォントは `composeResources/font/` に同梱している(詳細は `docs/compose-ui-font.md`)。Compose は canvas に描くので、
  ホストのシステムフォント(このマシンの Noto CJK など)は使われない。日本語が豆腐(□)で出るのは、同梱フォントの
  読み込みに失敗したときだけ。
- 起動時に `'try' instruction is deprecated` の警告が出るが、無視してよい。
- Cloudflare の beacon、favicon 404、ソースマップ 404 は無関係のノイズ。
- 過去の不具合(本文が空になる)の原因は、メインの領域の幅が 0 になるレイアウトだった。`Row` の中の `HorizontalDivider` が
  残りの幅を全部取っていた。縦の区切りは `VerticalDivider` を使うこと。
