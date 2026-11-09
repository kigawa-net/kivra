# /ui/compose のヘッドレスブラウザ確認手順

Compose Wasm UI(`/ui/compose`)が実際に起動して画面を描くかを、ローカルのヘッドレス Chrome で確認する手順。
「起動しきらない」問題(CSP 違反と未捕捉の WebAssembly 例外)の再現と回帰確認に使う。

## 1. 成果物を作る

```bash
./gradlew :kivra-app:copyComposeWebDist
```

`kivra-app/build/resources/main/ui/compose/` に `index.html`、`kivra-web.js`、`*.wasm`、`kivra-web.css`、`composeResources/` ができる。終了コードを必ず確認する。

## 2. 配信を再現するサーバを立てる

本番の `UiRoutes.kt` と同じパス・同じヘッダ(CSP、`X-Content-Type-Options`、`Referrer-Policy`、`Cache-Control: no-store`)で
`ui/compose` を返す小さなサーバを使う。CSP は `UiRoutes.kt` の `COMPOSE_UI_CSP` と一致させること。
`/api/*` と `/health` は実バックエンドがないので、401 か `[]` などのスタブで十分。
(Node の `http` で数十行。`/`、`/ui`、`/ui/` は `/ui/compose` へ 302、`/ui/compose(/)` は `index.html` を返す。)

## 3. ヘッドレス Chrome で開き、コンソールと描画を見る

```bash
mkdir -p /tmp/kivra-headless && cd /tmp/kivra-headless && npm init -y >/dev/null && npm i puppeteer-core
```

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

## 4. 合格の目安

- `[pageerror]` と `[unhandledrejection]` が出ない。
- CSP 違反(`[csp]`)が出ない。`styleTags` は 0。
- `#ComposeTarget canvas` があり、スクリーンショットにナビゲーション(Kivra、ホームなど)が描かれている。

`/health` や `/api/*` の 404/401 は、スタブを使う限り想定内。

## 既知の注意

- 日本語フォントが入っていない環境では、ナビの日本語が豆腐(□)で表示される。描画そのものは確認できる。
- 起動時に `'try' instruction is deprecated` の警告が出るが、無視してよい。
- Cloudflare の beacon、favicon 404、ソースマップ 404 は無関係のノイズ。
