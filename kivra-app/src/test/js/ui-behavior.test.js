// Behaviour tests for the Web UI script (src/main/resources/ui/app.js), run with `node --test` (Node 18+).
// The script runs unchanged in a vm context with a small stub DOM: its element tree, textContent and listeners are
// inspected, and fetch is replaced by a responder. No browser and no npm packages. Invoked from UiBehaviorTest.kt.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const APP_JS = path.join(__dirname, '..', '..', 'main', 'resources', 'ui', 'app.js');
const SELF_RUN_ID = '01SELFDEVRUNAAAAAAAAAAAAAA';
const SIM_RUN_ID = '01SIMRUNAAAAAAAAAAAAAAAAAA';
const UNBOUND_RUN_ID = '01UNBOUNDRUNAAAAAAAAAAAAAA';
const BOUND_RUN_ID = '01BOUNDRUNAAAAAAAAAAAAAAAAA';
const EVIL = '"><img src=x onerror=alert(1)><script>alert(2)</script>';

// ---- Stub DOM -------------------------------------------------------------

class TextNode {
  constructor(text) { this.nodeType = 3; this.data = text; this.parentNode = null; }
  get textContent() { return this.data; }
}

class El {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.attrs = {};
    this.listeners = {};
    this.parentNode = null;
    this.classList = { add() {}, remove() {} };
    this.hidden = false;
    this.disabled = false;
    this.checked = false;
    this.value = '';
    this.className = '';
    this.open = false;
    this.focused = false;
  }
  get firstChild() { return this.children[0] || null; }
  appendChild(c) { c.parentNode = this; this.children.push(c); return c; }
  removeChild(c) { this.children = this.children.filter((x) => x !== c); return c; }
  setAttribute(n, v) { this.attrs[n] = String(v); }
  getAttribute(n) { return this.attrs[n] === undefined ? null : this.attrs[n]; }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  fire(type, ev) { (this.listeners[type] || []).forEach((fn) => fn(ev || { preventDefault() {} })); }
  focus() { this.focused = true; }
  showModal() { this.open = true; }
  close() { this.open = false; }
  get textContent() { return this.children.map((c) => c.textContent).join(''); }
  set textContent(v) {
    this.children = [];
    if (v !== '' && v !== null && v !== undefined) this.appendChild(new TextNode(String(v)));
  }
  walk(fn) { fn(this); this.children.forEach((c) => { if (c.nodeType !== 3) c.walk(fn); }); }
  tags() { const out = []; this.walk((n) => out.push(n.tagName)); return out; }
  buttons() { const out = []; this.walk((n) => { if (n.tagName === 'BUTTON') out.push(n); }); return out; }
}

function makeDom() {
  const ids = {};
  const byId = (id) => (ids[id] = ids[id] || new El('div'));
  const documentListeners = {};
  const document = {
    hidden: false,
    createElement: (t) => new El(t),
    createTextNode: (t) => new TextNode(t),
    getElementById: byId,
    addEventListener: (type, fn) => { documentListeners[type] = fn; },
  };
  const hashListeners = [];
  const window = {
    location: { hash: '' },
    crypto: { randomUUID: (() => { let n = 0; return () => 'key-' + (++n); })() },
    scrollTo() {},
    addEventListener: (type, fn) => { if (type === 'hashchange') hashListeners.push(fn); },
  };
  return { ids, byId, document, window, hashListeners };
}

/**
 * Loads app.js into a fresh context. `respond(path, init)` returns { status, data } (or a promise of it), or throws to
 * simulate a network failure. Every request is recorded in `calls` as { path, method, body }.
 */
function load(respond, { hash = '' } = {}) {
  const dom = makeDom();
  dom.window.location.hash = hash;
  const calls = [];
  const ctx = {
    document: dom.document,
    window: dom.window,
    console,
    setInterval: () => 1,
    clearInterval() {},
  };
  ctx.fetch = async (p, init) => {
    calls.push({ path: p, method: init.method, body: init.body === undefined ? undefined : JSON.parse(init.body), headers: init.headers });
    const r = await respond(p, init);
    const ok = r.status >= 200 && r.status < 300;
    return {
      type: 'basic',
      redirected: false,
      ok,
      status: r.status,
      headers: { get: (n) => (n.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null) },
      json: async () => r.data,
    };
  };
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(APP_JS, 'utf8'), ctx, { filename: 'app.js' });
  return { ...dom, calls };
}

const flush = async () => { for (let i = 0; i < 10; i++) await new Promise((r) => setImmediate(r)); };

const status = (enabled) => ({ status: 200, data: enabled === null ? {} : { enabled } });
const runList = [];

/** A default responder: status as given, an empty run list, and a 500 for anything else. */
function defaults(selfDev) {
  return (p) => {
    if (p === '/api/selfdev/status') return selfDev === null ? { status: 500, data: { error: 'x' } } : status(selfDev);
    if (p === '/api/runs' || p.startsWith('/api/admin/runs?')) return { status: 200, data: runList };
    if (p === '/health') return { status: 200, data: { status: 'ok' } };
    return { status: 500, data: {} };
  };
}

function setTitle(dom, text) { dom.byId('title').value = text; }

async function submit(dom) {
  dom.byId('submit-form').fire('submit', { preventDefault() {} });
  await flush();
}

function postsTo(calls, p) { return calls.filter((c) => c.method === 'POST' && c.path === p); }

function selectKind(dom, kind) {
  dom.byId(kind === 'SELF_DEV' ? 'kind-self' : 'kind-sim').checked = true;
  dom.byId('kind-form').fire('change');
}

// ---- Tests ----------------------------------------------------------------

test('a self-development kind is available only when the status says enabled', async () => {
  const off = load(defaults(false));
  await flush();
  assert.equal(off.byId('kind-self').disabled, true, 'enabled=false: self-development cannot be picked');
  selectKind(off, 'SELF_DEV');
  assert.equal(off.byId('kind-self').disabled, true);
  setTitle(off, 'A');
  await submit(off);
  assert.equal(postsTo(off.calls, '/api/selfdev/tasks').length, 0, 'no self-development request while disabled');
  assert.equal(postsTo(off.calls, '/api/tasks').length, 1, 'simulation is still submitted');

  const unknown = load(defaults(null));
  await flush();
  assert.equal(unknown.byId('kind-self').disabled, true, 'status unavailable: self-development cannot be picked');
  assert.match(unknown.byId('kind-note').textContent, /選べません/);

  const on = load(defaults(true));
  await flush();
  assert.equal(on.byId('kind-self').disabled, false, 'enabled=true: self-development can be picked');
  assert.match(on.byId('kind-note').textContent, /PR を作成/);
});

test('a self-development selection is dropped when the status later says disabled', async () => {
  let enabled = true;
  const dom = load((p) => (p === '/api/selfdev/status' ? status(enabled) : defaults(true)(p)));
  await flush();
  selectKind(dom, 'SELF_DEV');
  enabled = false;
  dom.byId('refresh-btn').fire('click');
  await flush();
  assert.equal(dom.byId('kind-sim').checked, true);
  assert.equal(dom.byId('kind-self').checked, false);
  setTitle(dom, 'B');
  await submit(dom);
  assert.equal(postsTo(dom.calls, '/api/selfdev/tasks').length, 0);
});

test('switching the kind makes a new idempotency key, and a retry with the same content keeps it', async () => {
  let fail = true;
  const dom = load((p) => {
    if (p === '/api/tasks' || p === '/api/selfdev/tasks') {
      if (fail) throw new Error('network down');
      return { status: 202, data: { runId: SIM_RUN_ID, state: 'CREATED' } };
    }
    return defaults(true)(p);
  });
  await flush();
  setTitle(dom, 'same title');
  await submit(dom);
  await submit(dom); // the same content again after a network failure
  const sim = postsTo(dom.calls, '/api/tasks');
  assert.equal(sim.length, 2);
  assert.equal(sim[0].body.idempotencyKey, sim[1].body.idempotencyKey, 'retry keeps the key');

  selectKind(dom, 'SELF_DEV');
  fail = false;
  await submit(dom);
  const self = postsTo(dom.calls, '/api/selfdev/tasks');
  assert.equal(self.length, 1);
  assert.notEqual(self[0].body.idempotencyKey, sim[0].body.idempotencyKey, 'a kind change gives a new key');
  assert.equal(self[0].body.title, 'same title');
});

test('a changed title gets a new key as well', async () => {
  const dom = load((p) => (p === '/api/tasks' ? { status: 500, data: {} } : defaults(true)(p)));
  await flush();
  setTitle(dom, 'first');
  await submit(dom);
  setTitle(dom, 'second');
  await submit(dom);
  const posts = postsTo(dom.calls, '/api/tasks');
  assert.notEqual(posts[0].body.idempotencyKey, posts[1].body.idempotencyKey);
});

test('a double submit sends one request and the button is disabled while it is in flight', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  const dom = load(async (p) => {
    if (p === '/api/tasks') {
      await gate;
      return { status: 202, data: { runId: SIM_RUN_ID, state: 'CREATED' } };
    }
    return defaults(true)(p);
  });
  await flush();
  setTitle(dom, 'once');
  dom.byId('submit-form').fire('submit', { preventDefault() {} });
  dom.byId('submit-form').fire('submit', { preventDefault() {} });
  await flush();
  assert.equal(dom.byId('submit-btn').disabled, true, 'disabled during the send');
  assert.equal(dom.byId('kind-sim').disabled, true, 'the kind cannot change during the send');
  assert.equal(postsTo(dom.calls, '/api/tasks').length, 1, 'the second submit is ignored');
  release();
  await flush();
  assert.equal(dom.byId('submit-btn').disabled, false, 'enabled again after the answer');
});

test('202 and 200 give the fixed success text with the run id; a 409 makes a new key and says so', async () => {
  let reply = { status: 202, data: { runId: SIM_RUN_ID, state: 'CREATED' } };
  const dom = load((p) => (p === '/api/tasks' ? reply : defaults(true)(p)));
  await flush();
  setTitle(dom, 'x');
  await submit(dom);
  assert.match(dom.byId('submit-result').textContent, /受け付けました。Run ID: 01SIMRUN/);

  reply = { status: 200, data: { runId: SIM_RUN_ID, state: 'CREATED' } };
  setTitle(dom, 'y');
  await submit(dom);
  assert.match(dom.byId('submit-result').textContent, /既に受け付け済み/);

  reply = { status: 409, data: { error: 'idempotency key was already used with a different request' } };
  setTitle(dom, 'z');
  await submit(dom);
  const before = postsTo(dom.calls, '/api/tasks');
  const conflictKey = before[before.length - 1].body.idempotencyKey;
  assert.match(dom.byId('submit-result').textContent, /別の内容（別のタイトル、または別の種別）/);
  assert.doesNotMatch(dom.byId('submit-result').textContent, /already used/, 'the response body is not shown');

  reply = { status: 202, data: { runId: SIM_RUN_ID, state: 'CREATED' } };
  await submit(dom);
  const after = postsTo(dom.calls, '/api/tasks');
  assert.notEqual(after[after.length - 1].body.idempotencyKey, conflictKey, 'the next send uses a new key');
});

test('429 gives the fixed limit text, and the body is never shown', async () => {
  const dom = load((p) => (p === '/api/selfdev/tasks' ? { status: 429, data: { error: '<b>secret detail</b>' } } : defaults(true)(p)));
  await flush();
  selectKind(dom, 'SELF_DEV');
  setTitle(dom, 'limit');
  await submit(dom);
  const text = dom.byId('submit-result').textContent;
  assert.match(text, /自己開発の Run が上限に達しています/);
  assert.doesNotMatch(text, /secret detail/);
  assert.equal(dom.byId('submit-result').className, 'result error');
});

test('400, 401, 415 and other failures give fixed texts, never the response body', async () => {
  const cases = [
    [400, /入力内容を確認してください/],
    [415, /送信の形式がサーバーに受け付けられませんでした/],
    [500, /サーバーでエラーが起きました/],
  ];
  for (const [status_, re] of cases) {
    const dom = load((p) => (p === '/api/tasks' ? { status: status_, data: { error: 'LEAK-' + status_ } } : defaults(true)(p)));
    await flush();
    setTitle(dom, 't');
    await submit(dom);
    const text = dom.byId('submit-result').textContent;
    assert.match(text, re, String(status_));
    assert.doesNotMatch(text, /LEAK-/);
  }
});

test('a title with markup is only ever text, and no element is created from server data', async () => {
  const runs = [{
    runId: SIM_RUN_ID, taskId: 'api:1', title: EVIL, state: 'CREATED', mode: 'SIMULATED', executed: false,
    effect: 'SIMULATED_ONLY', reasonCode: EVIL, createdAt: '2026-10-16T10:00:00Z', updatedAt: '2026-10-16T10:00:00Z',
    createdBy: EVIL,
  }];
  const dom = load((p) => {
    if (p === '/api/runs') return { status: 200, data: runs };
    if (p === '/api/tasks') return { status: 202, data: { runId: SIM_RUN_ID, state: 'CREATED', note: EVIL } };
    return defaults(true)(p);
  });
  await flush();
  assert.equal(dom.byId('runs-list').children.length, 1);
  dom.byId('runs-list').walk((n) => {
    assert.ok(!['IMG', 'SCRIPT', 'B'].includes(n.tagName), 'no markup element from server data: ' + n.tagName);
  });
  assert.ok(dom.byId('runs-list').textContent.includes(EVIL), 'the title is shown as literal text');

  setTitle(dom, EVIL);
  await submit(dom);
  assert.equal(dom.byId('submit-result').tags().filter((t) => t === 'IMG').length, 0);
  assert.ok(dom.byId('submit-result').textContent.includes(SIM_RUN_ID));
});

// Builds a loaded page on the detail view of a run, and returns the dom.
async function openDetail(run, respond) {
  const dom = load((p) => {
    if (p === '/api/runs/' + encodeURIComponent(run.runId)) return { status: 200, data: run };
    if (respond) {
      const r = respond(p);
      if (r) return r;
    }
    return defaults(true)(p);
  }, { hash: '#/runs/' + run.runId });
  dom.window.location.hash = '#/runs/' + run.runId;
  dom.hashListeners.forEach((fn) => fn());
  await flush();
  return dom;
}

function selfRun(state, extra) {
  return {
    runId: SELF_RUN_ID, taskId: 'selfdev:1', title: 'a self-dev title', state, mode: 'SELF_DEV', executed: null,
    effect: 'CREATES_PULL_REQUEST', reasonCode: 'GITHUB_UNKNOWN_OUTCOME', createdAt: '2026-10-16T10:00:00Z',
    updatedAt: '2026-10-16T10:01:00Z', events: [], ...extra,
  };
}

function findButton(dom, text) {
  return dom.byId('detail-body').buttons().find((b) => b.textContent === text);
}

test('an UNKNOWN self-development run has the release button; the dialog needs a reason of 1 to 500 characters', async () => {
  const dom = await openDetail(selfRun('UNKNOWN'));
  const btn = findButton(dom, '運用者の解放…');
  assert.ok(btn, 'release button shown');
  btn.fire('click');
  assert.equal(dom.byId('release-dialog').open, true, 'dialog opened');
  assert.match(dom.byId('release-explain').textContent, /PR は閉じません・マージしません/);
  assert.equal(dom.byId('release-submit').disabled, true, 'empty reason: cannot send');

  dom.byId('release-reason').value = '   ';
  dom.byId('release-reason').fire('input');
  assert.equal(dom.byId('release-submit').disabled, true, 'blank reason: cannot send');

  dom.byId('release-reason').value = 'x'.repeat(501);
  dom.byId('release-reason').fire('input');
  assert.equal(dom.byId('release-submit').disabled, true, '501 characters: cannot send');
  dom.byId('release-submit').fire('click');
  await flush();
  assert.equal(postsTo(dom.calls, '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release').length, 0, 'no request for 501 characters');

  dom.byId('release-reason').value = 'あ'.repeat(500);
  dom.byId('release-reason').fire('input');
  assert.equal(dom.byId('release-submit').disabled, false, '500 characters: can send');
  assert.equal(dom.byId('release-count').textContent, '500 / 500');
});

test('the release sends the reason as JSON to /release and reloads the detail on success', async () => {
  let released = false;
  const dom = await openDetail(selfRun('UNKNOWN'), (p) => {
    if (p === '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release') {
      released = true;
      return { status: 200, data: { runId: SELF_RUN_ID, state: 'CANCELLED', reasonCode: 'OPERATOR_RELEASED' } };
    }
    return null;
  });
  findButton(dom, '運用者の解放…').fire('click');
  dom.byId('release-reason').value = '  確認した  ';
  dom.byId('release-reason').fire('input');
  dom.byId('release-submit').fire('click');
  await flush();
  assert.equal(released, true);
  const post = postsTo(dom.calls, '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release')[0];
  assert.deepEqual(post.body, { reason: '確認した' });
  assert.equal(post.headers['Content-Type'], 'application/json', 'sent with the JSON content type through apiFetch');
  assert.equal(dom.byId('release-dialog').open, false, 'dialog closed');
  assert.equal(dom.byId('detail-notice').textContent, '解放しました（状態: CANCELLED）。');
});

test('a bound PULL_REQUEST has no release button; a PULL_REQUEST without a binding uses release-unbound-pr', async () => {
  const bound = await openDetail(selfRun('PULL_REQUEST', {
    runId: BOUND_RUN_ID, pullRequestRepository: 'kigawa-net/kivra', pullRequestNumber: 241,
    pullRequestUrl: 'https://github.com/kigawa-net/kivra/pull/241',
  }));
  assert.equal(findButton(bound, '解放（束縛なし）…'), undefined, 'bound: no unbound release');
  assert.equal(findButton(bound, '運用者の解放…'), undefined);

  const unbound = await openDetail(selfRun('PULL_REQUEST', { runId: UNBOUND_RUN_ID }), (p) => {
    if (p === '/api/admin/selfdev/runs/' + UNBOUND_RUN_ID + '/release-unbound-pr') {
      return { status: 200, data: { runId: UNBOUND_RUN_ID, state: 'CANCELLED', reasonCode: 'OPERATOR_RELEASED_UNBOUND_PR' } };
    }
    return null;
  });
  const btn = findButton(unbound, '解放（束縛なし）…');
  assert.ok(btn, 'unbound PR: release button shown');
  btn.fire('click');
  assert.match(unbound.byId('release-explain').textContent, /GitHub には何も書き込みません/);
  unbound.byId('release-reason').value = 'ok';
  unbound.byId('release-reason').fire('input');
  unbound.byId('release-submit').fire('click');
  await flush();
  assert.equal(postsTo(unbound.calls, '/api/admin/selfdev/runs/' + UNBOUND_RUN_ID + '/release-unbound-pr').length, 1);
});

test('a simulation run has no release button', async () => {
  const dom = await openDetail({
    runId: SIM_RUN_ID, taskId: 'api:1', title: 't', state: 'UNKNOWN', mode: 'SIMULATED', executed: false,
    effect: 'SIMULATED_ONLY', reasonCode: null, createdAt: '2026-10-16T10:00:00Z', updatedAt: '2026-10-16T10:00:00Z', events: [],
  });
  assert.equal(dom.byId('detail-body').buttons().length, 0);
});

test('409, 404 and 400 on release show fixed texts and do not show the body', async () => {
  const cases = [
    [409, /解放できる状態ではありません/],
    [404, /見つかりません/],
    [400, /理由を確認してください/],
  ];
  for (const [code, re] of cases) {
    const dom = await openDetail(selfRun('UNKNOWN'), (p) => {
      if (p === '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release') return { status: code, data: { error: 'LEAK-' + code } };
      return null;
    });
    findButton(dom, '運用者の解放…').fire('click');
    dom.byId('release-reason').value = 'reason';
    dom.byId('release-reason').fire('input');
    dom.byId('release-submit').fire('click');
    await flush();
    const text = dom.byId('release-error').textContent;
    assert.match(text, re, String(code));
    assert.doesNotMatch(text, /LEAK-/);
    assert.equal(dom.byId('release-error').hidden, false);
    assert.equal(dom.byId('release-dialog').open, true, 'the dialog stays open on failure');
  }
});

test('while a release is in flight the send and cancel buttons are disabled (no second request)', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  const dom = await openDetail(selfRun('UNKNOWN'), (p) => {
    if (p === '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release') {
      return gate.then(() => ({ status: 200, data: { runId: SELF_RUN_ID, state: 'CANCELLED', reasonCode: 'OPERATOR_RELEASED' } }));
    }
    return null;
  });
  findButton(dom, '運用者の解放…').fire('click');
  dom.byId('release-reason').value = 'reason';
  dom.byId('release-reason').fire('input');
  dom.byId('release-submit').fire('click');
  dom.byId('release-submit').fire('click');
  await flush();
  assert.equal(dom.byId('release-submit').disabled, true);
  assert.equal(dom.byId('release-cancel').disabled, true);
  assert.equal(postsTo(dom.calls, '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release').length, 1);
  release();
  await flush();
});

test('the page is still usable with the self-development kind chosen after a release reload', async () => {
  // Sanity: the kind radios and key logic are untouched by the detail view.
  const dom = load(defaults(true));
  await flush();
  assert.equal(dom.byId('kind-self').disabled, false);
  assert.equal(dom.byId('kind-sim').checked, true);
});

// ---- Autopilot (ADR 0011): the Goal form, the Goal list and detail, approvals, the switches ---------------------

const G1 = '01GOALAAAAAAAAAAAAAAAAAAAA';
const P1 = '01PLANAAAAAAAAAAAAAAAAAAAA';
const AP_STATUS = { enabled: true, targetRepo: 'kigawa-net/kivra', paused: false, breakerOpen: false, consecutiveFailures: 0, runsToday: 0, maxRunsPerDay: 5, maxConsecutiveFailures: 3, approval: 'required' };

/**
 * A responder for the autopilot API. `s` is the mutable server state: the status, the goal list, and the goal detail body.
 * Unknown paths fall back to the defaults (status enabled=false, an empty run list).
 */
function apResponder(s) {
  return (p, init) => {
    if (p === '/api/selfdev/status') return status(false);
    if (p === '/api/selfdev/autopilot') return s.autopilot === 503 ? { status: 503, data: { error: 'autopilot is disabled' } } : { status: 200, data: s.autopilotStatus };
    if (p === '/api/selfdev/goals' && init.method === 'GET') return { status: 200, data: s.goals };
    if (p === '/api/selfdev/goals' && init.method === 'POST') return s.postGoal ? s.postGoal(init) : { status: 202, data: { goalId: G1, state: 'GOAL_SUBMITTED' } };
    if (p === '/api/selfdev/goals/' + G1) return { status: 200, data: s.detail };
    if (s.other) {
      const r = s.other(p, init);
      if (r) return r;
    }
    return defaults(true)(p);
  };
}

function apState(overrides = {}) {
  return {
    autopilot: 200,
    autopilotStatus: { ...AP_STATUS },
    goals: [],
    detail: null,
    ...overrides,
  };
}

function goalSummary(over = {}) {
  return {
    goalId: G1, title: 'CI を速くする', state: 'PLAN_PROPOSED', stateReason: null, revision: 1, version: 2,
    targetRepo: 'kigawa-net/kivra', createdAt: '2026-10-18T10:00:00Z', updatedAt: '2026-10-18T10:05:00Z', ...over,
  };
}

function taskOf(over = {}) {
  return {
    taskId: P1 + '-1', ordinal: 1, title: 'テストを足す', body: '手順の本文', targetPaths: ['docs/a.md'], sizeEstimate: 'S',
    dependsOn: [], requiresApproval: false, state: 'PENDING_APPROVAL', stateReason: null, runId: null, version: 0, ...over,
  };
}

function detailOf(goal, tasks, extra = {}) {
  return {
    goal: goal,
    body: '背景の本文',
    plans: [{ planId: P1, revision: 1, state: 'PROPOSED', summary: '要約', plannerModel: 'claude-haiku-5-5', createdAt: '2026-10-18T10:04:00Z', tasks }],
    ...extra,
  };
}

function buttonIn(container, text) {
  const found = container.buttons().filter((b) => b.textContent === text);
  assert.equal(found.length >= 1, true, `no button "${text}" in the container`);
  return found[0];
}

test('the autopilot sections stay hidden unless the status answers 200 with enabled (503 and failures show nothing)', async () => {
  const off = load(apResponder(apState({ autopilot: 503 })));
  await flush();
  assert.equal(off.byId('goal-section').hidden, true, '503: the goal form is hidden');
  assert.equal(off.byId('plans-section').hidden, true, '503: the list and switches are hidden');

  const broken = load(apResponder(apState({ autopilotStatus: null, autopilot: 500 })));
  await flush();
  assert.equal(broken.byId('goal-section').hidden, true, 'a failed status keeps the form hidden');

  const on = load(apResponder(apState()));
  await flush();
  assert.equal(on.byId('goal-section').hidden, false, 'enabled: the goal form is shown');
  assert.equal(on.byId('plans-section').hidden, false, 'enabled: the list is shown');
  assert.equal(on.byId('goal-repo').textContent, 'kigawa-net/kivra');
  assert.equal(postsTo(on.calls, '/api/selfdev/goals').length, 0, 'nothing is submitted by the page on load');
});

test('a goal is sent with a title and an optional body, a retry keeps the key, and a blank body is not sent', async () => {
  let fail = true;
  const s = apState({
    postGoal: () => {
      if (fail) throw new Error('network down');
      return { status: 202, data: { goalId: G1, state: 'GOAL_SUBMITTED' } };
    },
  });
  const dom = load(apResponder(s));
  await flush();
  dom.byId('goal-title').value = '  CI を速くする  ';
  dom.byId('goal-body').value = '   ';
  dom.byId('goal-form').fire('submit', { preventDefault() {} });
  await flush();
  fail = false;
  dom.byId('goal-form').fire('submit', { preventDefault() {} });
  await flush();
  const sent = postsTo(dom.calls, '/api/selfdev/goals');
  assert.equal(sent.length, 2);
  assert.equal(sent[0].body.title, 'CI を速くする', 'the title is trimmed');
  assert.equal('body' in sent[0].body, false, 'a blank body is not sent');
  assert.equal(sent[0].body.idempotencyKey, sent[1].body.idempotencyKey, 'a retry keeps the key');
  assert.match(dom.byId('goal-result').textContent, /受け付けました/);
  assert.equal(dom.byId('goal-title').value, '', 'the form is cleared after success');
});

test('a goal with a control character is refused before any request; a 409 makes a new key and says so', async () => {
  const s = apState({
    postGoal: () => ({ status: 409, data: { error: 'x' } }),
  });
  const dom = load(apResponder(s));
  await flush();
  dom.byId('goal-title').value = 'a\u0007b';
  dom.byId('goal-form').fire('submit', { preventDefault() {} });
  await flush();
  assert.equal(postsTo(dom.calls, '/api/selfdev/goals').length, 0, 'a control character is not sent');

  dom.byId('goal-title').value = 'ok';
  dom.byId('goal-form').fire('submit', { preventDefault() {} });
  await flush();
  const first = postsTo(dom.calls, '/api/selfdev/goals')[0].body.idempotencyKey;
  dom.byId('goal-form').fire('submit', { preventDefault() {} });
  await flush();
  const second = postsTo(dom.calls, '/api/selfdev/goals')[1].body.idempotencyKey;
  assert.notEqual(first, second, 'the key was changed after the 409');
  assert.match(dom.byId('goal-result').textContent, /別の内容/);
});

test('the goal list and detail show operator text as text, never as markup', async () => {
  const s = apState({
    goals: [goalSummary({ title: EVIL })],
    detail: detailOf(goalSummary({ title: EVIL }), [taskOf({ title: EVIL, body: EVIL })], { body: EVIL }),
  });
  const dom = load(apResponder(s));
  await flush();
  const toggle = buttonIn(dom.byId('plans-list'), '詳細');
  toggle.fire('click');
  await flush();
  const tags = [];
  dom.byId('plans-list').walk((n) => tags.push(n.tagName));
  assert.equal(tags.includes('IMG'), false, 'no image element is made from operator text');
  assert.equal(tags.includes('SCRIPT'), false, 'no script element is made from operator text');
  assert.match(dom.byId('plans-list').textContent, /<img src=x onerror=alert\(1\)>/, 'the text is shown literally');
  // The list is redrawn after the click, so the toggle is read again.
  assert.equal(buttonIn(dom.byId('plans-list'), '閉じる').getAttribute('aria-expanded'), 'true');
});

test('the plan approval comes first; a task is approved with the version the operator read, and a denylisted task waits', async () => {
  const goal = goalSummary({ state: 'PLAN_PROPOSED', version: 2 });
  const tasks = [taskOf({ ordinal: 1, taskId: P1 + '-1', version: 0 }), taskOf({ ordinal: 2, taskId: P1 + '-2', title: '権限', requiresApproval: true, version: 0 })];
  const s = apState({ goals: [goal], detail: detailOf(goal, tasks) });
  s.other = (p, init) => {
    if (p === '/api/selfdev/goals/' + G1 + '/approve-plan' && init.method === 'POST') {
      s.goals = [goalSummary({ state: 'TASKS_QUEUED', version: 3 })];
      s.detail = detailOf(s.goals[0], tasks);
      return { status: 200, data: s.goals[0] };
    }
    if (p === '/api/selfdev/tasks/' + P1 + '-1/approve' && init.method === 'POST') {
      return { status: 200, data: taskOf({ ordinal: 1, taskId: P1 + '-1', state: 'QUEUED', version: 1 }) };
    }
    return null;
  };
  const dom = load(apResponder(s));
  await flush();
  buttonIn(dom.byId('plans-list'), '詳細').fire('click');
  await flush();
  const list = dom.byId('plans-list');
  assert.equal(buttonIn(list, 'このタスクを承認').disabled, true, 'a task cannot be approved before the plan');
  buttonIn(list, 'この Plan を承認').fire('click');
  await flush();
  const approvePlan = postsTo(dom.calls, '/api/selfdev/goals/' + G1 + '/approve-plan');
  assert.equal(approvePlan.length, 1);
  assert.deepEqual(approvePlan[0].body, { version: 2 });
  assert.match(dom.byId('plans-msg').textContent, /Plan を承認しました/);

  buttonIn(dom.byId('plans-list'), 'このタスクを承認').fire('click');
  await flush();
  const approveTask = postsTo(dom.calls, '/api/selfdev/tasks/' + P1 + '-1/approve');
  assert.equal(approveTask.length, 1);
  assert.deepEqual(approveTask[0].body, { version: 0 });
});

test('a 409 on an approval shows the fixed text, never the server text, and reloads the goal', async () => {
  const goal = goalSummary({ state: 'TASKS_QUEUED', version: 3 });
  const tasks = [taskOf({ version: 0 })];
  const s = apState({ goals: [goal], detail: detailOf(goal, tasks) });
  s.other = (p, init) => (p === '/api/selfdev/tasks/' + P1 + '-1/approve' && init.method === 'POST'
    ? { status: 409, data: { error: 'SERVER-SECRET-TEXT' } }
    : null);
  const dom = load(apResponder(s));
  await flush();
  buttonIn(dom.byId('plans-list'), '詳細').fire('click');
  await flush();
  buttonIn(dom.byId('plans-list'), 'このタスクを承認').fire('click');
  await flush();
  const msg = dom.byId('plans-msg').textContent;
  assert.match(msg, /状態が変わっていました/);
  assert.equal(msg.includes('SERVER-SECRET-TEXT'), false);
  assert.equal(dom.byId('plans-msg').className, 'result error');
});

test('stop asks for a reason in a dialog; an empty reason is not sent; the request carries the version', async () => {
  const goal = goalSummary({ state: 'TASKS_QUEUED', version: 4 });
  const s = apState({ goals: [goal], detail: detailOf(goal, [taskOf({ version: 0 })]) });
  s.other = (p, init) => (p === '/api/selfdev/goals/' + G1 + '/stop' && init.method === 'POST'
    ? { status: 200, data: goalSummary({ state: 'STOPPED', version: 5 }) }
    : null);
  const dom = load(apResponder(s));
  await flush();
  buttonIn(dom.byId('plans-list'), '詳細').fire('click');
  await flush();
  buttonIn(dom.byId('plans-list'), '中止…').fire('click');
  assert.equal(dom.byId('stop-dialog').open, true);
  assert.equal(dom.byId('stop-submit').disabled, true, 'no reason, no send');
  dom.byId('stop-submit').fire('click');
  await flush();
  assert.equal(postsTo(dom.calls, '/api/selfdev/goals/' + G1 + '/stop').length, 0);

  dom.byId('stop-reason').value = '  もう要らない  ';
  dom.byId('stop-reason').fire('input');
  assert.equal(dom.byId('stop-submit').disabled, false);
  dom.byId('stop-submit').fire('click');
  await flush();
  const sent = postsTo(dom.calls, '/api/selfdev/goals/' + G1 + '/stop');
  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].body, { version: 4, reason: 'もう要らない' });
  assert.equal(dom.byId('stop-dialog').open, false);
});

test('pause and resume post an empty JSON object; the breaker button shows only while the breaker is open', async () => {
  const s = apState();
  s.autopilotStatus = { ...AP_STATUS, breakerOpen: true, consecutiveFailures: 3 };
  s.other = (p, init) => {
    if (p === '/api/selfdev/autopilot/pause' || p === '/api/selfdev/autopilot/resume' || p === '/api/selfdev/autopilot/reset-breaker') {
      return { status: 200, data: { ok: true } };
    }
    return null;
  };
  const dom = load(apResponder(s));
  await flush();
  assert.equal(dom.byId('breaker-block').hidden, false, 'the breaker is open: the reset is shown');
  assert.match(dom.byId('breaker-status').textContent, /3 件失敗/);

  dom.byId('autopilot-toggle').fire('click');
  await flush();
  const pause = postsTo(dom.calls, '/api/selfdev/autopilot/pause');
  assert.equal(pause.length, 1);
  assert.deepEqual(pause[0].body, {});

  dom.byId('breaker-reset').fire('click');
  await flush();
  assert.equal(postsTo(dom.calls, '/api/selfdev/autopilot/reset-breaker').length, 1);
});

test('a closed breaker hides the reset button, and the toggle says what it will do', async () => {
  const dom = load(apResponder(apState({ autopilotStatus: { ...AP_STATUS, paused: true } })));
  await flush();
  assert.equal(dom.byId('breaker-block').hidden, true);
  assert.equal(dom.byId('autopilot-toggle').textContent, '自律運転を再開');
});
