// /api/projects を 500 にして、ヘッダーのエラー表示と再試行が出ることを確認する(ローカル検証専用)。
// 環境変数: BASE, KIVRA_LOCAL_DIR, OUT(スクリーンショット), CHROME。使い方は docs/compose-ui-headless-check.md。
import puppeteer from 'puppeteer-core';
import { execFileSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.BASE ?? 'http://127.0.0.1:18080';
const OUT = process.env.OUT ?? './projects-error.png';
const WORK = process.env.KIVRA_LOCAL_DIR ?? path.join(os.homedir(), '.cache', 'kivra-local');
const CHROME = process.env.CHROME ?? '/usr/bin/google-chrome';
const DIR = path.dirname(new URL(import.meta.url).pathname);
const TOKEN = execFileSync('node', [path.join(DIR, 'oidc-local.mjs'), 'token', 'local-root'], {
  env: { ...process.env, OIDC_DIR: path.join(WORK, 'oidc') },
}).toString().trim();

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox'] });
const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 800 });
await page.setExtraHTTPHeaders({ Authorization: `Bearer ${TOKEN}` });
let fail = true;
await page.setRequestInterception(true);
page.on('request', (r) => {
  if (fail && new URL(r.url()).pathname === '/api/projects') {
    r.respond({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' });
  } else r.continue();
});
await page.goto(`${BASE}/ui/compose#/`, { waitUntil: 'networkidle2' });
await new Promise((r) => setTimeout(r, 5000));
await page.screenshot({ path: OUT });
await browser.close();
console.log('saved', OUT);
