// /ui/compose の主要画面を実サーバに対して順に開き、スクリーンショットと失敗の記録を OUT に残す。
// 巡回のみで、アサーションはしない(目視で判断する前提)。ローカル検証専用。
//
// 環境変数:
//   BASE         kivra-app の URL (既定 http://127.0.0.1:18080)
//   KIVRA_LOCAL_DIR  run-local-server.sh の作業ディレクトリ(既定 $HOME/.cache/kivra-local)。鍵はその下の oidc/
//   OUT          出力先(既定 ./crawl-out)
//   ONLY         画面名のカンマ区切り(例: home,goals)。省略で全画面
//   CHROME       Chrome の実行ファイル(既定 /usr/bin/google-chrome)
//   SETTLE_MS    画面を開いてから待つ最低時間(既定 5000)
//
// 出力: OUT/<画面名>.png(スクリーンショット)、OUT/<画面名>.txt(アクセシビリティツリーの文字。Compose は canvas 描画なので通常は空)、
//       OUT/summary.json(画面ごとの API の 4xx/5xx、ページの例外、コンソールのエラー)。
import puppeteer from 'puppeteer-core';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.BASE ?? 'http://127.0.0.1:18080';
const OUT = process.env.OUT ?? './crawl-out';
const ONLY = process.env.ONLY ? new Set(process.env.ONLY.split(',')) : null;
const WORK = process.env.KIVRA_LOCAL_DIR ?? path.join(os.homedir(), '.cache', 'kivra-local');
const CHROME = process.env.CHROME ?? '/usr/bin/google-chrome';
const SETTLE_MS = Number(process.env.SETTLE_MS ?? 5000);

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
fs.mkdirSync(OUT, { recursive: true });

const TOKEN = execFileSync('node', [path.join(SCRIPT_DIR, 'oidc-local.mjs'), 'token', 'local-root'], {
  env: { ...process.env, OIDC_DIR: path.join(WORK, 'oidc') },
}).toString().trim();
const AUTH = { Authorization: `Bearer ${TOKEN}` };

async function getJson(p) {
  const r = await fetch(BASE + p, { headers: AUTH });
  return r.ok ? r.json() : [];
}

// 詳細画面を開くための id を API から取る
const projects = await getJson('/api/projects');
const pid = projects[0]?.projectId;
const goals = await getJson('/api/selfdev/goals');
const orders = await getJson('/api/selfdev/orders');
const runs = await getJson('/api/runs');
// 未分類(project_id が NULL)の Run。seed-unassigned-local.sh で作る。
const unassignedRuns = await getJson('/api/runs?projectId=unassigned');

// 改訂 2(docs/design/16-site-structure.md)の構成。プロジェクトが入口で、ページと詳細はプロジェクトの中に置く。
// 旧 URL(legacy-*)は、プロジェクトが 1 つに決まればその配下へ置き換わり、決まらなければプロジェクト一覧へ行く。
const screens = [
  // ホームと入口
  ['home', '#/'],
  ['projects', '#/projects'],
  // 全体
  ['budget', '#/budget'],
  ['autopilot', '#/autopilot'],
  ['assignees', '#/assignees'],
  // 旧 URL(プロジェクトを持たない一覧)。置き換えの結果を見る
  ['legacy-tasks', '#/tasks'],
  ['legacy-goals', '#/goals'],
  ['legacy-approvals', '#/approvals'],
  // 未分類(疑似プロジェクト。データの一覧だけ。作成フォームは出さない)
  ['unassigned-tasks', '#/projects/unassigned/tasks'],
  ['unassigned-goals', '#/projects/unassigned/goals'],
  ['unassigned-orders', '#/projects/unassigned/orders'],
  ['unassigned-approvals', '#/projects/unassigned/approvals'],
  ['unassigned-memory', '#/projects/unassigned/memory'],
  ['unassigned-run-detail', unassignedRuns[0]?.runId && `#/projects/unassigned/runs/${unassignedRuns[0].runId}`],
  // 未分類にはダッシュボードが無い(解釈できないリンク)
  ['unassigned-dashboard', '#/projects/unassigned/dashboard'],
  // プロジェクト配下
  ['proj-dashboard', pid && `#/projects/${pid}/dashboard`],
  ['proj-tasks', pid && `#/projects/${pid}/tasks`],
  ['proj-goals', pid && `#/projects/${pid}/goals`],
  ['proj-orders', pid && `#/projects/${pid}/orders`],
  ['proj-approvals', pid && `#/projects/${pid}/approvals`],
  ['proj-memory', pid && `#/projects/${pid}/memory`],
  ['proj-assignees', pid && `#/projects/${pid}/assignees`],
  ['proj-budget', pid && `#/projects/${pid}/budget`],
  // プロジェクトの中の詳細
  ['proj-goal-detail', pid && goals[0]?.taskId && `#/projects/${pid}/goals/${goals[0].taskId}`],
  ['proj-order-detail', pid && orders[0]?.orderId && `#/projects/${pid}/orders/${orders[0].orderId}`],
  ['proj-order-cycles', pid && orders[0]?.orderId && `#/projects/${pid}/orders/${orders[0].orderId}/cycles`],
  ['proj-run-detail', pid && runs[0]?.runId && `#/projects/${pid}/runs/${runs[0].runId}`],
  // 旧い詳細(プロジェクトを持たない URL は同じ画面を開く)
  ['legacy-goal-detail', goals[0]?.taskId && `#/goals/${goals[0].taskId}`],
  ['legacy-run-detail', runs[0]?.runId && `#/runs/${runs[0].runId}`],
  // 壊れたリンク
  ['invalid', '#/projects/%ZZ/tasks'],
].filter(([name, hash]) => hash && (!ONLY || ONLY.has(name)));

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--no-sandbox', '--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--lang=ja-JP'],
});

const summary = [];
for (const [name, hash] of screens) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });
  await page.setExtraHTTPHeaders(AUTH);
  const consoleErrors = [];
  const pageErrors = [];
  const apiFailures = [];
  const pending = new Set();
  let lastApiAt = Date.now();
  page.on('console', (m) => {
    if (m.type() === 'error' || m.type() === 'warning') consoleErrors.push(`[${m.type()}] ${m.text()}`);
  });
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  page.on('request', (req) => {
    if (req.url().includes('/api/')) {
      pending.add(req.url());
      lastApiAt = Date.now();
    }
  });
  page.on('requestfinished', (req) => pending.delete(req.url()));
  page.on('requestfailed', (req) => {
    pending.delete(req.url());
    if (req.url().includes('/api/')) apiFailures.push(`FAILED ${req.url()} ${req.failure()?.errorText}`);
  });
  page.on('response', async (res) => {
    const url = new URL(res.url());
    if (url.pathname.startsWith('/api/') && res.status() >= 400) {
      let body = '';
      try {
        body = (await res.text()).slice(0, 300);
      } catch {
        body = '(本文を読めない)';
      }
      apiFailures.push(`${res.status()} ${url.pathname}${url.search} ${body}`);
    }
  });
  const started = Date.now();
  await page.goto(BASE + '/ui/compose' + hash, { waitUntil: 'load' });
  // 静かになるまで待つ: 最低 SETTLE_MS、API の応答が 1.5 秒なく、送信中の要求がない。上限は SETTLE_MS + 15 秒。
  while (Date.now() - started < SETTLE_MS || Date.now() - lastApiAt < 1500 || pending.size > 0) {
    await new Promise((r) => setTimeout(r, 300));
    if (Date.now() - started > SETTLE_MS + 15000) break;
  }
  await page.screenshot({ path: path.join(OUT, `${name}.png`) });
  const texts = [];
  const walk = (n) => {
    if (!n) return;
    if (n.name && n.name.trim()) texts.push(n.name.trim());
    (n.children ?? []).forEach(walk);
  };
  walk(await page.accessibility.snapshot({ interestingOnly: false }));
  const dom = await page.evaluate(() => ({
    canvas: [...document.querySelectorAll('canvas')].map((c) => `${c.width}x${c.height}`),
    hash: location.hash,
  }));
  fs.writeFileSync(path.join(OUT, `${name}.txt`), texts.join('\n'));
  summary.push({
    screen: name,
    hash,
    dom,
    a11yTextCount: texts.length,
    consoleErrors: consoleErrors.filter((c) => !c.includes('favicon')),
    pageErrors,
    apiFailures,
  });
  console.log(`${name}: pageErrors=${pageErrors.length} apiFail=${apiFailures.length} console=${consoleErrors.length}`);
  await page.close();
}
fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify(summary, null, 2));
await browser.close();
console.log(`done -> ${path.resolve(OUT)}`);
