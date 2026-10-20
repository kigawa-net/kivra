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
    addEventListener: (type, fn) => { (documentListeners[type] = documentListeners[type] || []).push(fn); },
  };
  const fireDocument = (type) => (documentListeners[type] || []).forEach((fn) => fn({}));
  const hashListeners = [];
  const window = {
    location: { hash: '' },
    crypto: { randomUUID: (() => { let n = 0; return () => 'key-' + (++n); })() },
    scrollTo() {},
    addEventListener: (type, fn) => { if (type === 'hashchange') hashListeners.push(fn); },
  };
  return { ids, byId, document, window, hashListeners, fireDocument };
}

/**
 * Loads app.js into a fresh context. `respond(path, init)` returns { status, data } (or a promise of it), or throws to
 * simulate a network failure. Every request is recorded in `calls` as { path, method, body }.
 */
/**
 * A fake clock for setTimeout / clearTimeout and Date.now(). Timers run only when `advance(ms)` moves time past them.
 * Nothing real waits, so the tests are fast and the node process ends when they do.
 */
function makeClock(start = Date.parse('2026-10-19T00:00:00Z')) {
  const clock = { now: start, timers: [], nextId: 1 };
  clock.setTimeout = (fn, ms) => {
    const t = { id: clock.nextId++, at: clock.now + (ms || 0), fn };
    clock.timers.push(t);
    return t.id;
  };
  clock.clearTimeout = (id) => { clock.timers = clock.timers.filter((t) => t.id !== id); };
  clock.advance = async (ms) => {
    const end = clock.now + ms;
    for (;;) {
      const due = clock.timers.filter((t) => t.at <= end).sort((a, b) => a.at - b.at || a.id - b.id)[0];
      if (!due) break;
      clock.now = Math.max(clock.now, due.at);
      clock.timers = clock.timers.filter((t) => t !== due);
      due.fn();
      await flush();
    }
    clock.now = end;
    await flush();
  };
  return clock;
}

function fakeDateFor(clock) {
  return class FakeDate extends Date {
    constructor(...args) {
      if (args.length === 0) super(clock.now);
      else super(...args);
    }
    static now() { return clock.now; }
  };
}

/**
 * Loads app.js into a fresh context. `opts.eventSource` is an optional EventSource constructor (absent: no SSE).
 */
function load(respond, { hash = '', eventSource } = {}) {
  const dom = makeDom();
  dom.window.location.hash = hash;
  const clock = makeClock();
  if (eventSource) dom.window.EventSource = eventSource;
  const calls = [];
  const ctx = {
    document: dom.document,
    window: dom.window,
    console,
    setInterval: () => 1,
    clearInterval() {},
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    Date: fakeDateFor(clock),
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
  return { ...dom, calls, clock };
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

// ---- Budget (operator) -----------------------------------------------------

const BUDGET_STATUS = {
  period: '2026-10',
  limitJpy: '10000',
  committedJpy: '1300',
  reservedJpy: '100',
  remainingJpy: '8600',
  paths: { api: { committedJpy: '950' }, cli: { committedJpy: '350' } },
  unpricedCount: 2,
  unpriced: [
    { reservationKey: 'coding:01ATTEMPTAAAAAAAAAAAAAAAAAA:1', scopeId: 'tenant', period: '2026-10', state: 'UNPRICED', path: 'cli', amountJpy: '120', reason: 'COST_NOT_ESTABLISHED' },
    { reservationKey: 'api-req-1', scopeId: 'tenant', period: '2026-09', state: 'LAPSED', path: 'api', amountJpy: '80.5', reason: 'TTL_ELAPSED' },
  ],
};
const BUDGET_FX_OK = { fx: { asOf: '2026-10-08', daysLeft: 10, expired: false } };
const ACK_KEY = 'coding:01ATTEMPTAAAAAAAAAAAAAAAAAA:1';

/** The budget endpoints as given (or the default), with the run API answering as in `defaults(true)`. */
function budgetResponder(opts = {}) {
  const base = defaults(true);
  return (p, init) => {
    if (p === '/api/admin/budget/status') return opts.status || { status: 200, data: BUDGET_STATUS };
    if (p === '/api/admin/budget/fx') return opts.fx || { status: 200, data: BUDGET_FX_OK };
    if (p === '/api/admin/budget/acknowledge' && opts.ack) return opts.ack(p, init);
    return base(p, init);
  };
}

function allTags(el) { return el.tags(); }

async function openBudget(respond) {
  const dom = load(respond);
  await flush();
  return dom;
}

function openFirstAck(dom) {
  dom.byId('budget-unpriced-list').buttons()[0].fire('click');
}

function fillAck(dom, { actual = '', evidence = 'invoice 123', reason = 'lost response' } = {}) {
  dom.byId('budget-actual').value = actual;
  dom.byId('budget-actual').fire('input');
  dom.byId('budget-evidence').value = evidence;
  dom.byId('budget-evidence').fire('input');
  dom.byId('budget-reason').value = reason;
  dom.byId('budget-reason').fire('input');
}

test('the budget section stays hidden for a non-operator, and that does not end the session', async () => {
  const dom = await openBudget(budgetResponder({ status: { status: 401, data: { error: 'unauthorized' } }, fx: { status: 401, data: {} } }));
  assert.equal(dom.byId('budget-section').hidden, true);
  // A 401 from the operator-only budget is not an expired session: the run list still refreshes.
  dom.byId('refresh-btn').fire('click');
  await flush();
  assert.ok(dom.calls.some((c) => c.path === '/api/runs'), 'the run list is still requested');
  assert.equal(dom.byId('refresh-btn').disabled, false);
  assert.equal(dom.calls.filter((c) => c.path === '/api/admin/budget/status').length, 1, 'the status is read once on load');
});

test('a 404 (budget route not registered) also keeps the section hidden', async () => {
  const dom = await openBudget(budgetResponder({ status: { status: 404, data: {} } }));
  assert.equal(dom.byId('budget-section').hidden, true);
});

test('the bar, the remaining amount and the CLI / API split are shown as text', async () => {
  const dom = await openBudget(budgetResponder());
  assert.equal(dom.byId('budget-section').hidden, false);
  assert.equal(dom.byId('budget-body').hidden, false);
  assert.equal(dom.byId('budget-bar').max, 100);
  assert.equal(dom.byId('budget-bar').value, 14, 'confirmed 1,300 + reserved 100 of 10,000');
  const text = dom.byId('budget-bar-text').textContent;
  assert.match(text, /確定 1,300 円 \+ 予約中 100 円 \/ 上限 10,000 円/);
  assert.match(text, /使用 14%/);
  assert.match(text, /残り 8,600 円/);
  assert.equal(dom.byId('budget-bar-text').className, 'meta');
  assert.equal(dom.byId('budget-path-api').textContent, '950 円');
  assert.equal(dom.byId('budget-path-cli').textContent, '350 円');
});

test('an overspent month says so in words, and the bar stops at 100', async () => {
  const over = { ...BUDGET_STATUS, committedJpy: '10200', remainingJpy: '-200', reservedJpy: '0', unpricedCount: 0, unpriced: [] };
  const dom = await openBudget(budgetResponder({ status: { status: 200, data: over } }));
  assert.equal(dom.byId('budget-bar').value, 100);
  assert.match(dom.byId('budget-bar-text').textContent, /上限を超えています（超過 200 円）/);
  assert.equal(dom.byId('budget-bar-text').className, 'meta error');
});

test('the FX warning: two days or less is amber with words, expired is red with words, otherwise plain', async () => {
  const soon = await openBudget(budgetResponder({ fx: { status: 200, data: { fx: { asOf: '2026-10-08', daysLeft: 2, expired: false } } } }));
  assert.equal(soon.byId('budget-fx').className, 'budget-alert warn');
  assert.match(soon.byId('budget-fx').textContent, /残り 2 日です/);

  const gone = await openBudget(budgetResponder({ fx: { status: 200, data: { fx: { asOf: '2026-10-01', daysLeft: 0, expired: true } } } }));
  assert.equal(gone.byId('budget-fx').className, 'budget-alert error');
  assert.match(gone.byId('budget-fx').textContent, /為替レートが失効しています/);

  const fine = await openBudget(budgetResponder());
  assert.equal(fine.byId('budget-fx').className, 'meta');
  assert.match(fine.byId('budget-fx').textContent, /残り 10 日/);

  const unset = await openBudget(budgetResponder({ fx: { status: 200, data: { fx: null } } }));
  assert.match(unset.byId('budget-fx').textContent, /設定されていません/);
});

test('the UNPRICED list shows the attempt, the amount and the reason, and no raw body', async () => {
  const dom = await openBudget(budgetResponder());
  const list = dom.byId('budget-unpriced-list');
  assert.equal(list.children.length, 2);
  const first = list.children[0].textContent;
  assert.match(first, /CLI の attempt 01ATTEMP…（世代 1）/);
  assert.match(first, /CLI 経路/);
  assert.match(first, /金額 120 円/);
  assert.match(first, /費用が確定しない/);
  assert.doesNotMatch(first, /coding:01ATTEMPT/, 'the full key is not shown as text (it is kept for the request)');
  const second = list.children[1].textContent;
  assert.match(second, /期限切れ/);
  assert.match(second, /金額 80.5 円/);
  assert.match(dom.byId('budget-unpriced-count').textContent, /未確定の予約: 2 件/);
});

test('a reservation key carrying markup is shown as text only (no element is built from it)', async () => {
  const hostile = {
    ...BUDGET_STATUS,
    unpricedCount: 2,
    unpriced: [
      { reservationKey: 'coding:<script>alert(1)</script>:7', scopeId: 'tenant', period: '2026-10', state: 'UNPRICED', path: 'cli', amountJpy: '1', reason: 'COST_NOT_ESTABLISHED' },
      { reservationKey: EVIL, scopeId: 'tenant', period: '2026-10', state: 'LAPSED', path: 'api', amountJpy: '2', reason: 'TTL_ELAPSED' },
    ],
  };
  const dom = await openBudget(budgetResponder({ status: { status: 200, data: hostile } }));
  const tags = allTags(dom.byId('budget-unpriced-list'));
  assert.equal(tags.includes('SCRIPT'), false);
  assert.equal(tags.includes('IMG'), false);
  const text = dom.byId('budget-unpriced-list').textContent;
  assert.ok(text.includes('<script>'), 'the markup is text');
  assert.ok(text.includes('<img'), 'the markup is text');
});

test('a malformed status body is refused, and a failed load shows a fixed message', async () => {
  const bad = await openBudget(budgetResponder({ status: { status: 200, data: { period: '2026-10' } } }));
  assert.equal(bad.byId('budget-section').hidden, false);
  assert.equal(bad.byId('budget-body').hidden, true);
  assert.match(bad.byId('budget-status').textContent, /応答の形式が想定と違います/);

  const down = await openBudget(budgetResponder({ status: { status: 503, data: { error: 'LEAK-503' } } }));
  assert.equal(down.byId('budget-body').hidden, true);
  assert.match(down.byId('budget-status').textContent, /取得できませんでした/);
  assert.doesNotMatch(down.byId('budget-status').textContent, /LEAK/);
});

test('acknowledge is two steps: nothing is sent until the confirmation, then the body has no operator and no actual unless typed', async () => {
  const acks = [];
  const dom = await openBudget(budgetResponder({
    ack: (p, init) => { acks.push(JSON.parse(init.body)); return { status: 200, data: { status: 'ACKNOWLEDGED', reservationKey: ACK_KEY } }; },
  }));
  openFirstAck(dom);
  assert.equal(dom.byId('budget-dialog').open, true);
  fillAck(dom);
  dom.byId('budget-next').fire('click');
  assert.equal(acks.length, 0, 'the first step sends nothing');
  assert.equal(dom.byId('budget-dialog-confirm').hidden, false);
  assert.equal(dom.byId('budget-dialog-edit').hidden, true);
  assert.match(dom.byId('budget-confirm-text').textContent, /実額は不明のまま/);
  dom.byId('budget-submit').fire('click');
  await flush();
  assert.equal(acks.length, 1);
  assert.deepEqual(acks[0], {
    scopeId: 'tenant', period: '2026-10', reservationKey: ACK_KEY, evidence: 'invoice 123', reason: 'lost response',
  });
  assert.equal(dom.byId('budget-dialog').open, false);
  assert.match(dom.byId('budget-msg').textContent, /解除しました/);
});

test('a typed actual is sent as the decimal string; a 0 is sent as 0', async () => {
  const acks = [];
  const dom = await openBudget(budgetResponder({
    ack: (p, init) => { acks.push(JSON.parse(init.body)); return { status: 200, data: { status: 'ALREADY_ACKNOWLEDGED', reservationKey: ACK_KEY } }; },
  }));
  openFirstAck(dom);
  fillAck(dom, { actual: '12.5' });
  dom.byId('budget-next').fire('click');
  dom.byId('budget-submit').fire('click');
  await flush();
  assert.equal(acks[0].actualAmountJpy, '12.5');
  assert.match(dom.byId('budget-msg').textContent, /既に解除済み/);
});

test('an actual that is not plain digits keeps the send disabled and says why', async () => {
  const dom = await openBudget(budgetResponder());
  openFirstAck(dom);
  fillAck(dom, { actual: '1e3' });
  assert.equal(dom.byId('budget-next').disabled, true);
  assert.equal(dom.byId('budget-actual-hint').className, 'meta error');
  assert.match(dom.byId('budget-actual-hint').textContent, /円の数字/);
  fillAck(dom, { actual: '' });
  assert.equal(dom.byId('budget-next').disabled, false, 'an empty actual is allowed (unknown)');
});

test('the evidence and the reason are required and cannot be only spaces', async () => {
  const dom = await openBudget(budgetResponder());
  openFirstAck(dom);
  fillAck(dom, { evidence: '   ' });
  assert.equal(dom.byId('budget-next').disabled, true);
  fillAck(dom, { evidence: 'ok', reason: '' });
  assert.equal(dom.byId('budget-next').disabled, true);
});

test('the refusals show fixed texts and never the body; the dialog stays open for an edit', async () => {
  const cases = [
    [400, { error: 'LEAK-400', code: 'TOO_LARGE' }, /100 倍を超えています/],
    [400, { error: 'LEAK-400', code: 'NOT_COMPARABLE' }, /単位が予約と合いません/],
    [400, { error: 'LEAK-400' }, /入力を確認してください/],
    [404, { error: 'LEAK-404' }, /見つかりません/],
    [409, { error: 'LEAK-409' }, /未確定ではありません/],
    [503, { error: 'LEAK-503' }, /台帳に書けませんでした/],
  ];
  for (const [code, data, re] of cases) {
    const dom = await openBudget(budgetResponder({
      ack: () => ({ status: code, data }),
    }));
    openFirstAck(dom);
    fillAck(dom, { actual: '5' });
    dom.byId('budget-next').fire('click');
    dom.byId('budget-submit').fire('click');
    await flush();
    const text = dom.byId('budget-dialog-error').textContent;
    assert.match(text, re, String(code));
    assert.doesNotMatch(text, /LEAK/);
    assert.equal(dom.byId('budget-dialog-error').hidden, false);
    assert.equal(dom.byId('budget-dialog').open, true, 'the dialog stays open');
    assert.equal(dom.byId('budget-dialog-edit').hidden, false, 'back to the edit step');
  }
});

test('while an acknowledgement is in flight a second submit sends nothing', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  const dom = await openBudget(budgetResponder({
    ack: () => gate.then(() => ({ status: 200, data: { status: 'ACKNOWLEDGED', reservationKey: ACK_KEY } })),
  }));
  openFirstAck(dom);
  fillAck(dom);
  dom.byId('budget-next').fire('click');
  dom.byId('budget-submit').fire('click');
  dom.byId('budget-submit').fire('click');
  await flush();
  assert.equal(dom.calls.filter((c) => c.path === '/api/admin/budget/acknowledge').length, 1);
  assert.equal(dom.byId('budget-submit').disabled, true);
  assert.equal(dom.byId('budget-cancel').disabled, true);
  release();
  await flush();
});

test('the back button returns to the edit step without sending', async () => {
  const dom = await openBudget(budgetResponder());
  openFirstAck(dom);
  fillAck(dom);
  dom.byId('budget-next').fire('click');
  dom.byId('budget-back').fire('click');
  assert.equal(dom.byId('budget-dialog-edit').hidden, false);
  assert.equal(dom.calls.filter((c) => c.path === '/api/admin/budget/acknowledge').length, 0);
});

// ---- Execution (ADR 0012 PR 4): "実行の経過" in the detail of a self-development run ----------------------------

const ATT = '01ATTEMPTAAAAAAAAAAAAAAAAAAA';
const EXEC_PATH = '/api/admin/runs/' + SELF_RUN_ID + '/execution';
const STREAM_PATH = EXEC_PATH + '/stream';

/** A snapshot as the server answers it (fields as in RunExecutionRoutes / MonitorExecutionView). Times are the fake clock. */
function snapshot(over = {}) {
  return {
    runId: SELF_RUN_ID,
    runState: 'DEVELOPING',
    snapshotAt: '2026-10-19T00:00:00Z',
    revision: { attemptVersion: 3, eventCount: 10 },
    freshness: 'LIVE',
    lastReceivedAt: '2026-10-19T00:00:00Z',
    attempts: [{ attemptId: ATT, attemptNo: 1, state: 'RUNNING', leaseGeneration: 2, lastReceivedAt: '2026-10-19T00:00:00Z' }],
    stop: { stage: 'NONE', runCancelRequested: false, agentAcknowledged: false, processExitConfirmed: false },
    tools: { total: 12, byName: { Read: 5, Edit: 2, Bash: 3, OTHER: 1 }, errors: 1 },
    turns: 4,
    permissionDenied: 0,
    warnings: 2,
    dropped: 0,
    truncated: false,
    lastPhase: 'EDITING',
    testingReported: false,
    apiRetries: {},
    cost: { confirmedUsd: '0.000000', estimatedUsd: '0.120000', confirmedBasis: 'REPORTED_USAGE', estimatedBasis: 'RESERVE_ESTIMATE' },
    ...over,
  };
}

/**
 * Opens the detail of a self-development run. `exec` is a function returning the response of the execution snapshot
 * (default: 200 with snapshot()). Everything else answers as in `defaults(true)`.
 */
async function openExec(run, exec, opts = {}) {
  const dom = load((p) => {
    if (p === '/api/runs/' + encodeURIComponent(run.runId)) return { status: 200, data: run };
    if (p === EXEC_PATH) return exec ? exec() : { status: 200, data: snapshot() };
    return defaults(true)(p);
  }, { hash: '#/runs/' + run.runId, eventSource: opts.eventSource });
  dom.window.location.hash = '#/runs/' + run.runId;
  dom.hashListeners.forEach((fn) => fn());
  await flush();
  return dom;
}

function execSection(dom) {
  let found = null;
  dom.byId('detail-body').walk((n) => { if (n.className === 'exec-section') found = n; });
  return found;
}

function execCalls(dom) { return dom.calls.filter((c) => c.path === EXEC_PATH); }

/** The text of the dd that follows the dt with this text (the detail is a dl of dt/dd pairs). */
function valueAfter(section, label) {
  let out = null;
  section.walk((n) => {
    if (n.tagName !== 'DL') return;
    n.children.forEach((c, i) => {
      if (c.tagName === 'DT' && c.textContent === label && n.children[i + 1]) out = n.children[i + 1].textContent;
    });
  });
  assert.notEqual(out, null, 'no value for ' + label);
  return out;
}

/** A stub EventSource: records the instances and lets the test fire open, messages and errors. */
function makeEventSource(instances) {
  return class StubEventSource {
    constructor(url) {
      this.url = url;
      this.readyState = 0;
      this.closed = false;
      this.onopen = null;
      this.onerror = null;
      this.listeners = {};
      instances.push(this);
    }
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
    emit(type, data) { (this.listeners[type] || []).forEach((fn) => fn({ data: JSON.stringify(data || {}) })); }
    open() { this.readyState = 1; if (this.onopen) this.onopen({}); }
    fail() { this.readyState = 0; if (this.onerror) this.onerror({}); }
    close() { this.closed = true; this.readyState = 2; }
  };
}

test('a simulation run has no execution section and makes no execution request', async () => {
  const sim = { runId: SIM_RUN_ID, taskId: 'api:1', title: 'sim', state: 'CREATED', mode: 'SIMULATED', executed: false, events: [] };
  const dom = await openDetail(sim);
  assert.equal(execSection(dom), null);
  assert.equal(execCalls(dom).length, 0);
});

test('an API-path run (404 on the execution view) hides the section, and the watch does not poll again', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 404, data: { error: 'run not found' } }));
  assert.equal(execSection(dom).hidden, true);
  assert.equal(execCalls(dom).length, 1);
  await dom.clock.advance(60000);
  assert.equal(execCalls(dom).length, 1, 'no further request after the 404');
});

test('a 200 with no attempt is not a CLI run: hidden, and not polled', async () => {
  const dom = await openExec(selfRun('CREATED'), () => ({ status: 200, data: snapshot({ attempts: [] }) }));
  assert.equal(execSection(dom).hidden, true);
  await dom.clock.advance(60000);
  assert.equal(execCalls(dom).length, 1);
});

test('a 403 (not the creator, not an operator) hides the section', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 403, data: { error: 'forbidden' } }));
  assert.equal(execSection(dom).hidden, true);
});

test('the section shows the attempt, the turn count, the tool counts and the warnings as numbers and fixed labels', async () => {
  const dom = await openExec(selfRun('DEVELOPING'));
  const sec = execSection(dom);
  assert.equal(sec.hidden, false);
  const text = sec.textContent;
  assert.match(text, /試行 #1：稼働中（CLI）（世代 2）/);
  assert.equal(valueAfter(sec, 'メッセージ数'), '4 件');
  assert.equal(valueAfter(sec, 'ツール呼び出し（合計）'), '12 回');
  assert.equal(valueAfter(sec, 'ツール結果の失敗'), '1 件');
  assert.equal(valueAfter(sec, '読み取り（Read）'), '5 回');
  assert.equal(valueAfter(sec, '編集（Edit）'), '2 回');
  assert.equal(valueAfter(sec, 'その他'), '1 回');
  assert.match(text, /警告: 2 件/);
  assert.match(text, /最後の段階（エージェントの報告）/);
  assert.equal(valueAfter(sec, '最後の段階（エージェントの報告）'), '編集中');
  // The count is a number of calls, never a percentage: an active attempt shows an indeterminate bar with words.
  assert.doesNotMatch(text, /%/);
  assert.match(text, /不確定/);
  const bars = []; sec.walk((n) => { if (n.tagName === 'PROGRESS') bars.push(n); });
  assert.equal(bars.length, 1);
  assert.equal(bars[0].attrs.value, undefined, 'no value: indeterminate');
});

test('no bar once every attempt is over; the body of the section is a polite live region', async () => {
  const dom = await openExec(selfRun('FAILED'), () => ({ status: 200, data: snapshot({ runState: 'FAILED', attempts: [{ attemptId: ATT, attemptNo: 1, state: 'FAILED', leaseGeneration: 2, lastReceivedAt: null }] }) }));
  const sec = execSection(dom);
  const bars = []; sec.walk((n) => { if (n.tagName === 'PROGRESS') bars.push(n); });
  assert.equal(bars.length, 0);
  let live = null;
  sec.walk((n) => { if (n.attrs['aria-live'] !== undefined) live = n.attrs['aria-live']; });
  assert.equal(live, 'polite');
});

test('the confirmed cost and the estimate are labelled apart, and the estimate says 推定', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 200, data: snapshot({ cost: { confirmedUsd: '0.250000', estimatedUsd: '1.500000' } }) }));
  const sec = execSection(dom);
  assert.equal(valueAfter(sec, '確定（CLI の報告。終了後に確定します）'), 'USD 0.250000');
  const est = valueAfter(sec, '推定（予約からの見積り。確定ではありません）');
  assert.equal(est, 'USD 1.500000（推定）');
  // The estimate is never shown under the confirmed label.
  assert.notEqual(valueAfter(sec, '確定（CLI の報告。終了後に確定します）'), est);
});

test('a malformed money value is shown as unknown, never as a number', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 200, data: snapshot({ cost: { confirmedUsd: '1e9', estimatedUsd: null } }) }));
  const sec = execSection(dom);
  assert.equal(valueAfter(sec, '確定（CLI の報告。終了後に確定します）'), '不明');
  assert.equal(valueAfter(sec, '推定（予約からの見積り。確定ではありません）'), '不明（推定）');
});

test('STALE is shown in words with the minutes since the last receipt, in the warning style', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 200, data: snapshot({ freshness: 'STALE', lastReceivedAt: '2026-10-18T23:53:00Z' }) }));
  const sec = execSection(dom);
  const stale = []; sec.walk((n) => { if (n.className === 'exec-stale') stale.push(n); });
  assert.equal(stale.length, 1);
  assert.equal(stale[0].textContent, '最終の受信から 7 分経過。表示は古い可能性があります');
  assert.equal(stale[0].attrs.role, 'status');
});

test('STALE with nothing ever received says so in words', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 200, data: snapshot({ freshness: 'STALE', lastReceivedAt: null, attempts: [{ attemptId: ATT, attemptNo: 1, state: 'CLAIMED', leaseGeneration: 1, lastReceivedAt: null }] }) }));
  assert.match(execSection(dom).textContent, /受信の記録がありません。表示は古い可能性があります/);
});

test('LIVE shows no stale sentence', async () => {
  const dom = await openExec(selfRun('DEVELOPING'));
  const sec = execSection(dom);
  const stale = []; sec.walk((n) => { if (n.className === 'exec-stale') stale.push(n); });
  assert.equal(stale.length, 0);
  assert.doesNotMatch(sec.textContent, /古い可能性/);
  assert.match(sec.textContent, /鮮度: LIVE/);
});

test('a reason code with a Japanese explanation shows the explanation and the code as text', async () => {
  const dom = await openDetail(selfRun('FAILED', { reasonCode: 'CODING_OUTPUT_LIMIT' }));
  const text = dom.byId('detail-body').textContent;
  assert.ok(text.includes('CLI の出力が上限を超えた'), 'the explanation is shown');
  assert.ok(text.includes('（CODING_OUTPUT_LIMIT）'), 'the code is shown after the explanation');
});

test('a reason code without an explanation is shown as the unknown reason with its code only', async () => {
  const dom = await openDetail(selfRun('FAILED', { reasonCode: 'CODING_SOMETHING_NEW' }));
  assert.ok(dom.byId('detail-body').textContent.includes('不明な理由（CODING_SOMETHING_NEW）'));
});

test('a reason code that is markup is shown as text, never as an element', async () => {
  const dom = await openDetail(selfRun('FAILED', { reasonCode: EVIL }));
  const tags = dom.byId('detail-body').tags();
  assert.equal(tags.filter((t) => t === 'IMG' || t === 'SCRIPT').length, 0);
  assert.ok(dom.byId('detail-body').textContent.includes(EVIL));
});

test('every coding failure code the runbook names has an explanation (none falls back to the unknown reason)', async () => {
  const codes = ['CODING_AUTH_REQUIRED', 'CODING_NEEDS_HUMAN', 'CODING_TIMEOUT', 'CODING_OUTPUT_LIMIT', 'CODING_RATE_LIMITED',
    'CODING_BAD_OUTPUT', 'CODING_CLI_NONZERO', 'CODING_CANCELLED', 'CODING_FAILED', 'CODING_ORPHANED', 'CODING_QUEUE_EXPIRED',
    'CODING_RESULT_MISSING', 'CODING_NO_ATTEMPT', 'CODING_NOT_DEVELOPING', 'CODING_ENQUEUE_REFUSED', 'CODING_EMPTY_PATCH',
    'CODING_BUDGET_EXCEEDED', 'CODING_OVERLOADED', 'OPERATOR_RELEASED_UNBOUND_PR'];
  for (const code of codes) {
    const dom = await openDetail(selfRun('FAILED', { reasonCode: code }));
    const text = dom.byId('detail-body').textContent;
    assert.ok(!text.includes('不明な理由（' + code + '）'), code + ' has an explanation');
    assert.ok(text.includes('（' + code + '）'), code + ' is shown with its code');
  }
});

test('a finished run (COMPLETED, FAILED, CANCELLED) shows no stale warning, even with STALE freshness', async () => {
  for (const runState of ['COMPLETED', 'FAILED', 'CANCELLED']) {
    const dom = await openExec(selfRun(runState), () => ({ status: 200, data: snapshot({ runState, freshness: 'STALE', lastReceivedAt: '2026-10-18T23:53:00Z' }) }));
    const sec = execSection(dom);
    const stale = []; sec.walk((n) => { if (n.className === 'exec-stale') stale.push(n); });
    assert.equal(stale.length, 0, runState + ': no exec-stale element');
    assert.doesNotMatch(sec.textContent, /古い可能性/, runState);
    assert.match(sec.textContent, /終了済み/, runState);
  }
});

test('a running run with STALE freshness still shows the stale warning', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 200, data: snapshot({ runState: 'DEVELOPING', freshness: 'STALE', lastReceivedAt: '2026-10-18T23:53:00Z' }) }));
  const sec = execSection(dom);
  const stale = []; sec.walk((n) => { if (n.className === 'exec-stale') stale.push(n); });
  assert.equal(stale.length, 1);
  assert.match(sec.textContent, /表示は古い可能性があります/);
  assert.doesNotMatch(sec.textContent, /終了済み/);
});

test('the turn count is labelled as messages, with a note that it may differ from the API turn count', async () => {
  const dom = await openExec(selfRun('DEVELOPING'));
  const sec = execSection(dom);
  assert.equal(valueAfter(sec, 'メッセージ数'), '4 件');
  assert.match(sec.textContent, /assistant のメッセージの数です。API のターン数とは異なることがあります/);
  assert.doesNotMatch(sec.textContent, /ターン 4/);
});

test('a failed attempt with no CLI report shows its reserve estimate as a reserve, not a real cost', async () => {
  const failed = [{ attemptId: ATT, attemptNo: 1, state: 'FAILED', leaseGeneration: 2, lastReceivedAt: null }];
  const dom = await openExec(selfRun('FAILED'), () => ({ status: 200, data: snapshot({ runState: 'FAILED', attempts: failed, cost: { confirmedUsd: '0.000000', estimatedUsd: '0.500000', confirmedBasis: 'REPORTED_USAGE', estimatedBasis: 'RESERVE_ESTIMATE' } }) }));
  assert.equal(valueAfter(execSection(dom), '推定（予約からの見積り。確定ではありません）'), 'USD 0.500000（推定）。予約額。実費ではない');
});

test('the reserve note is not shown when the attempt did not fail, or when a confirmed cost is there', async () => {
  const running = await openExec(selfRun('DEVELOPING'), () => ({ status: 200, data: snapshot({ cost: { confirmedUsd: '0.000000', estimatedUsd: '0.500000' } }) }));
  assert.equal(valueAfter(execSection(running), '推定（予約からの見積り。確定ではありません）'), 'USD 0.500000（推定）');
  const failed = [{ attemptId: ATT, attemptNo: 1, state: 'FAILED', leaseGeneration: 2, lastReceivedAt: null }];
  const confirmed = await openExec(selfRun('FAILED'), () => ({ status: 200, data: snapshot({ runState: 'FAILED', attempts: failed, cost: { confirmedUsd: '0.250000', estimatedUsd: '0.500000' } }) }));
  assert.equal(valueAfter(execSection(confirmed), '推定（予約からの見積り。確定ではありません）'), 'USD 0.500000（推定）');
});

test('a non-terminal run is read every 5 seconds; a terminal run is read once and not again', async () => {
  const dom = await openExec(selfRun('DEVELOPING'));
  assert.equal(execCalls(dom).length, 1);
  await dom.clock.advance(5000);
  assert.equal(execCalls(dom).length, 2);
  await dom.clock.advance(5000);
  assert.equal(execCalls(dom).length, 3);

  const done = await openExec(selfRun('COMPLETED'), () => ({ status: 200, data: snapshot({ runState: 'COMPLETED' }) }));
  assert.equal(execCalls(done).length, 1);
  await done.clock.advance(60000);
  assert.equal(execCalls(done).length, 1, 'terminal: no polling');
});

test('leaving the detail stops the watch at once', async () => {
  const dom = await openExec(selfRun('DEVELOPING'));
  assert.equal(execCalls(dom).length, 1);
  dom.window.location.hash = '#/';
  dom.hashListeners.forEach((fn) => fn());
  await flush();
  await dom.clock.advance(60000);
  assert.equal(execCalls(dom).length, 1, 'no request after leaving the detail');
});

test('a hidden tab reads every 30 seconds, and a shown tab reads at once', async () => {
  const dom = await openExec(selfRun('DEVELOPING'));
  dom.document.hidden = true;
  dom.fireDocument('visibilitychange');
  await dom.clock.advance(5000);
  assert.equal(execCalls(dom).length, 1, 'no read at the visible interval while hidden');
  await dom.clock.advance(25000);
  assert.equal(execCalls(dom).length, 2, 'read at 30 seconds');
  dom.document.hidden = false;
  dom.fireDocument('visibilitychange');
  await flush();
  assert.equal(execCalls(dom).length, 3, 'shown again: read at once');
});

test('consecutive failures back off (10, 20, 40, 60, 60 seconds); the last shown state stays, with fixed words', async () => {
  const times = [];
  let failing = false;
  let dom;
  // The first read happens while the page loads (the clock has not moved yet); later reads use the fake clock.
  dom = await openExec(selfRun('DEVELOPING'), () => {
    times.push(dom ? dom.clock.now : Date.parse('2026-10-19T00:00:00Z'));
    return failing ? { status: 500, data: { error: 'LEAK' } } : { status: 200, data: snapshot() };
  });
  failing = true;
  await dom.clock.advance(5000 + 10000 + 20000 + 40000 + 60000 + 60000);
  const gaps = times.slice(1).map((t, i) => t - times[i]);
  // Each failure doubles the wait (from the normal interval), capped at 60 seconds.
  assert.deepEqual(gaps, [5000, 10000, 20000, 40000, 60000, 60000]);
  const sec = execSection(dom);
  assert.match(sec.textContent, /監視の情報を取得できませんでした/);
  assert.match(sec.textContent, /Run の状態ではありません/);
  assert.match(sec.textContent, /メッセージ数4 件/, 'the last snapshot is still shown');
  assert.doesNotMatch(sec.textContent, /LEAK/);
  assert.doesNotMatch(sec.textContent, /正常|解消/);

  failing = false;
  await dom.clock.advance(60000);
  assert.doesNotMatch(sec.textContent, /監視の情報を取得できませんでした/, 'a good answer clears the failure');
  const before = execCalls(dom).length;
  await dom.clock.advance(5000);
  assert.equal(execCalls(dom).length, before + 1, 'back to the normal interval');
});

test('a network failure before any answer shows the section with the fixed message, and the watch tries again', async () => {
  let fail = true;
  const dom = await openExec(selfRun('DEVELOPING'), () => {
    if (fail) throw new Error('network down');
    return { status: 200, data: snapshot() };
  });
  const sec = execSection(dom);
  assert.equal(sec.hidden, false);
  assert.match(sec.textContent, /監視の情報を取得できませんでした/);
  fail = false;
  await dom.clock.advance(10000);
  assert.match(execSection(dom).textContent, /メッセージ数4 件/);
});

test('a malformed answer is a failure with the fixed message, not a blank or a crash', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 200, data: { attempts: 'x' } }));
  assert.equal(execSection(dom).hidden, false);
  assert.match(execSection(dom).textContent, /監視の情報を取得できませんでした/);
});

test('an older snapshot (by revision) never replaces the one shown', async () => {
  let n = 0;
  const dom = await openExec(selfRun('DEVELOPING'), () => {
    n += 1;
    if (n === 1) return { status: 200, data: snapshot({ turns: 9, revision: { attemptVersion: 5, eventCount: 10 } }) };
    return { status: 200, data: snapshot({ turns: 2, revision: { attemptVersion: 4, eventCount: 10 } }) };
  });
  assert.equal(valueAfter(execSection(dom), 'メッセージ数'), '9 件');
  await dom.clock.advance(5000);
  assert.equal(execCalls(dom).length, 2);
  assert.equal(valueAfter(execSection(dom), 'メッセージ数'), '9 件', 'the older answer is ignored');
});

test('a stop requested is shown as waiting for the agent, never as stopped', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({
    status: 200,
    data: snapshot({ stop: { stage: 'REQUESTED', runCancelRequested: true, agentAcknowledged: false, processExitConfirmed: false } }),
  }));
  const sec = execSection(dom);
  assert.equal(valueAfter(sec, '段階'), '停止要求あり（エージェントの確認待ち）');
  assert.equal(valueAfter(sec, '停止要求'), 'あり');
  assert.doesNotMatch(sec.textContent, /停止済み|停止しました/);
});

test('XSS: attempt states, tool names and retry kinds from the server are text only; unknown tools count as OTHER', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({
    status: 200,
    data: snapshot({
      attempts: [{ attemptId: ATT, attemptNo: 1, state: EVIL, leaseGeneration: 1, lastReceivedAt: null }],
      tools: { total: 3, byName: { [EVIL]: 3 }, errors: 0 },
      apiRetries: { [EVIL]: 1 },
      lastPhase: EVIL,
    }),
  }));
  const sec = execSection(dom);
  sec.walk((n) => {
    assert.ok(!['IMG', 'SCRIPT', 'B', 'A'].includes(n.tagName), 'no markup element from server data: ' + n.tagName);
  });
  assert.ok(sec.textContent.includes(EVIL), 'the unknown state is shown as literal text');
  assert.equal(valueAfter(sec, 'その他'), '3 回');
  assert.equal(valueAfter(sec, '最後の段階（エージェントの報告）'), '不明な値（' + EVIL + '）');
});

test('a malformed attempt number or count is shown as unknown', async () => {
  const dom = await openExec(selfRun('DEVELOPING'), () => ({
    status: 200,
    data: snapshot({ turns: -1, tools: { total: 1.5, byName: {}, errors: 'x' } }),
  }));
  const sec = execSection(dom);
  assert.equal(valueAfter(sec, 'メッセージ数'), '不明 件');
  assert.equal(valueAfter(sec, 'ツール呼び出し（合計）'), '不明 回');
  assert.equal(valueAfter(sec, 'ツール結果の失敗'), '不明 件');
});

test('the stream is started only after the first answer with an attempt, and a stream message reads the snapshot soon', async () => {
  const instances = [];
  const dom = await openExec(selfRun('DEVELOPING'), null, { eventSource: makeEventSource(instances) });
  assert.equal(instances.length, 1);
  assert.equal(instances[0].url, STREAM_PATH);
  instances[0].open();
  assert.match(execSection(dom).textContent, /取得方法: 常時接続（SSE）/);
  const before = execCalls(dom).length;
  instances[0].emit('monitor', { attemptId: ATT, seq: 1 });
  await dom.clock.advance(500);
  assert.equal(execCalls(dom).length, before + 1, 'read soon after a message');
});

test('a stream that fails three times falls back to the REST poll, which keeps running', async () => {
  const instances = [];
  const dom = await openExec(selfRun('DEVELOPING'), null, { eventSource: makeEventSource(instances) });
  const es = instances[0];
  for (let i = 0; i < 3; i++) es.fail();
  assert.equal(es.closed, true, 'the stream is closed after repeated failures');
  assert.match(execSection(dom).textContent, /常時接続は使えなかったため、定期取得に切り替えました/);
  const before = execCalls(dom).length;
  await dom.clock.advance(5000);
  assert.equal(execCalls(dom).length, before + 1, 'polling continues every 5 seconds');
  assert.equal(instances.length, 1, 'no new stream after the fallback');
});

test('a stream that closes at once (readyState CLOSED) falls back to the REST poll without waiting for three errors', async () => {
  const instances = [];
  const dom = await openExec(selfRun('DEVELOPING'), null, { eventSource: makeEventSource(instances) });
  // The browser gives up: readyState CLOSED with the error event.
  instances[0].readyState = 2;
  instances[0].onerror({});
  assert.equal(instances[0].closed, true);
  const before = execCalls(dom).length;
  await dom.clock.advance(5000);
  assert.equal(execCalls(dom).length, before + 1);
});

test('without EventSource the page polls and shows the poll method', async () => {
  const dom = await openExec(selfRun('DEVELOPING'));
  assert.match(execSection(dom).textContent, /取得方法: 定期取得（5 秒ごと）/);
  await dom.clock.advance(5000);
  assert.equal(execCalls(dom).length, 2);
});

test('a resync reads the snapshot again and opens a new stream; a second resync stops using the stream', async () => {
  const instances = [];
  const dom = await openExec(selfRun('DEVELOPING'), null, { eventSource: makeEventSource(instances) });
  instances[0].emit('resync', { action: 'refetch_snapshot' });
  assert.equal(instances[0].closed, true);
  await dom.clock.advance(500);
  assert.equal(instances.length, 2, 'a new stream after the snapshot');
  assert.equal(execCalls(dom).length, 2, 'the snapshot was read again');
  instances[1].open();
  instances[1].emit('resync', {});
  await dom.clock.advance(500);
  assert.equal(instances.length, 2, 'after the second resync: no third stream');
  assert.match(execSection(dom).textContent, /常時接続は使えなかったため、定期取得に切り替えました/);
});

test('leaving the detail closes the stream', async () => {
  const instances = [];
  const dom = await openExec(selfRun('DEVELOPING'), null, { eventSource: makeEventSource(instances) });
  dom.window.location.hash = '#/';
  dom.hashListeners.forEach((fn) => fn());
  await flush();
  assert.equal(instances[0].closed, true);
});

test('a terminal run closes the stream and stops', async () => {
  const instances = [];
  const dom = await openExec(selfRun('DEVELOPING'), () => ({ status: 200, data: snapshot({ runState: 'COMPLETED' }) }), { eventSource: makeEventSource(instances) });
  assert.equal(instances.length, 0, 'no stream for a terminal run');
  await dom.clock.advance(60000);
  assert.equal(execCalls(dom).length, 1);
});

// ---- Self-development orders (ADR 0015 stage 1, PR 2b): the order list and detail, pause and resume, the stop approval ----

const O1 = '01ORDER' + 'A'.repeat(19);
const O2 = '01ORDER' + 'B'.repeat(19);
const R1 = '01REQUEST' + 'A'.repeat(17);
const R2 = '01REQUEST' + 'B'.repeat(17);
const DIGEST = 'ab'.repeat(32);
const SECRET_TITLE = 'ORDER-TITLE-SECRET';
const SECRET_POLICY = 'POLICY-SECRET-TEXT';
const SECRET_OWNER = 'SECRET-OWNER-KEY';
const APPROVE = '/api/selfdev/stop-requests/' + R1 + '/approve';

function orderOf(over = {}) {
  return {
    orderId: O1, parentOrderId: null, depth: 0, state: 'ACTIVE', stateReason: null, cyclesStarted: 0, maxCycles: 1,
    currentGoalId: null, consecutiveFailedCycles: 0, expiresAt: '2026-11-19T00:00:00Z', version: 3,
    createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T10:00:00Z', ...over,
  };
}

function orderDetailOf(over = {}, pending = null) {
  return { order: orderOf(over), title: SECRET_TITLE, policy: SECRET_POLICY, pendingStopRequest: pending };
}

function stopRequestOf(over = {}) {
  return {
    requestId: R1, orderId: O1, requesterKind: 'AI', reasonCode: 'RUNAWAY_SUSPECTED', state: 'PENDING', orderVersion: 3,
    digest: DIGEST, expiresAt: '2026-10-19T12:00:00Z', createdAt: '2026-10-18T12:00:00Z', ...over,
  };
}

/** The order routes. `s.orderList`, `s.orderDetail` and `s.requestList` are { status, data }; `s.orderPost` may answer a write. */
function ordersOther(s) {
  return (p, init) => {
    if (p === '/api/selfdev/orders' && init.method === 'GET') return s.orderList;
    if (p === '/api/selfdev/stop-requests?state=PENDING' && init.method === 'GET') return s.requestList;
    if (p === '/api/selfdev/orders/' + O1 && init.method === 'GET') return s.orderDetail;
    if (s.orderPost) return s.orderPost(p, init);
    return null;
  };
}

function ordState(over = {}) {
  return apState({
    orderList: { status: 200, data: [orderOf()] },
    orderDetail: { status: 200, data: orderDetailOf() },
    requestList: { status: 403, data: { error: 'forbidden' } },
    ...over,
  });
}

function ordLoad(s) {
  s.other = ordersOther(s);
  return load(apResponder(s));
}

function attrsOf(el) {
  const out = [];
  el.walk((n) => Object.values(n.attrs).forEach((v) => out.push(v)));
  return out.join('\n');
}

function hasButton(container, text) {
  return container.buttons().some((b) => b.textContent === text);
}

test('the order sections stay hidden on 404 and 503 and while the autopilot is off; a non-approver sees no approval section', async () => {
  let dom = ordLoad(ordState({ orderList: { status: 404, data: { error: 'x' } } }));
  await flush();
  assert.equal(dom.byId('orders-section').hidden, true, '404: no order section');
  assert.equal(dom.byId('approval-section').hidden, true, '404: no approval section');

  dom = ordLoad(ordState({ orderList: { status: 503, data: { error: 'x' } } }));
  await flush();
  assert.equal(dom.byId('orders-section').hidden, true, '503: no order section');

  dom = ordLoad(ordState({ autopilot: 503 }));
  await flush();
  assert.equal(dom.byId('orders-section').hidden, true, 'autopilot off: no order section');
  assert.equal(dom.calls.filter((c) => c.path === '/api/selfdev/orders').length, 0, 'the order list is not read while the autopilot is off');

  dom = ordLoad(ordState());
  await flush();
  assert.equal(dom.byId('orders-section').hidden, false, 'an operator sees the order list');
  assert.equal(dom.byId('approval-section').hidden, true, '403 on the approvals: no approval section');
  assert.match(dom.byId('orders-list').textContent, new RegExp('order ' + O1));
});

test('the order list shows the state, the cycles and the reason as fixed Japanese text; a code outside the table is shown as unknown', async () => {
  const s = ordState({
    orderList: {
      status: 200,
      data: [
        orderOf({ state: 'PAUSED', stateReason: 'LIMIT_REACHED', cyclesStarted: 1, maxCycles: 1 }),
        orderOf({ orderId: O2, state: 'WEIRD', stateReason: 'NEW_CODE' }),
        orderOf({ orderId: R2, state: 'ACTIVE', stateReason: '<img src=x onerror=alert(1)>' }),
      ],
    },
  });
  const dom = ordLoad(s);
  await flush();
  const text = dom.byId('orders-list').textContent;
  assert.match(text, /状態: 一時停止中/);
  assert.match(text, /周の上限に達しました（再開できません。新しい order が必要です）（LIMIT_REACHED）/);
  assert.match(text, /周: 1 \/ 1/);
  assert.match(text, /状態: 不明（WEIRD）/, 'an unknown state shows the code');
  assert.match(text, /理由: 不明（NEW_CODE）/, 'an unknown reason shows the code only');
  assert.equal(text.includes('<img'), false, 'markup is never shown as markup');
  const tags = [];
  dom.byId('orders-list').walk((n) => tags.push(n.tagName));
  assert.equal(tags.includes('IMG'), false, 'no image element is made from a response value');
});

test('the detail offers pause only when ACTIVE, and resume only when PAUSED for a resumable reason; pause sends the version', async () => {
  const s = ordState({
    orderPost: (p) => (p === '/api/selfdev/orders/' + O1 + '/pause'
      ? { status: 200, data: orderOf({ state: 'PAUSED', stateReason: 'OPERATOR_PAUSED', version: 4 }) }
      : null),
  });
  let dom = ordLoad(s);
  await flush();
  buttonIn(dom.byId('orders-list'), '詳細').fire('click');
  await flush();
  assert.equal(hasButton(dom.byId('orders-list'), '一時停止'), true, 'ACTIVE: pause');
  assert.equal(hasButton(dom.byId('orders-list'), '再開'), false, 'ACTIVE: no resume');
  buttonIn(dom.byId('orders-list'), '一時停止').fire('click');
  await flush();
  const sent = postsTo(dom.calls, '/api/selfdev/orders/' + O1 + '/pause');
  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].body, { version: 3 });
  assert.match(dom.byId('orders-msg').textContent, /一時停止しました/);

  const cases = [
    [{ state: 'PAUSED', stateReason: 'STOP_NOT_APPROVED' }, true],
    [{ state: 'PAUSED', stateReason: 'LIMIT_REACHED' }, false],
    [{ state: 'PAUSED', stateReason: 'EXPIRED' }, false],
  ];
  for (const [over, expectResume] of cases) {
    dom = ordLoad(ordState({ orderDetail: { status: 200, data: orderDetailOf(over) } }));
    await flush();
    buttonIn(dom.byId('orders-list'), '詳細').fire('click');
    await flush();
    assert.equal(hasButton(dom.byId('orders-list'), '再開'), expectResume, `${over.stateReason}: resume`);
    assert.equal(hasButton(dom.byId('orders-list'), '一時停止'), false, `${over.stateReason}: no pause`);
  }
});

test('the approval list shows the reason, the requester kind and the times as text, and never the digest, the title, the policy or a requester ID', async () => {
  const s = ordState({
    orderList: { status: 200, data: [orderOf({ title: SECRET_TITLE, policy: SECRET_POLICY, requestedBy: SECRET_OWNER })] },
    requestList: { status: 200, data: [stopRequestOf({ title: SECRET_TITLE, policy: SECRET_POLICY, requestedBy: SECRET_OWNER })] },
  });
  const dom = ordLoad(s);
  await flush();
  assert.equal(dom.byId('approval-section').hidden, false, 'the approver sees the approval section');
  const text = dom.byId('approval-list').textContent;
  assert.match(text, new RegExp('対象の order: ' + O1));
  assert.match(text, /理由: 暴走の疑い（RUNAWAY_SUSPECTED）/);
  assert.match(text, /要求した主体: AI の主体/);
  assert.match(text, /要求の時刻: /);
  assert.match(text, /期限: /);
  for (const secret of [DIGEST, 'digest', SECRET_TITLE, SECRET_POLICY, SECRET_OWNER]) {
    assert.equal(text.includes(secret), false, `not shown: ${secret}`);
    assert.equal(attrsOf(dom.byId('approval-list')).includes(secret), false, `not in an attribute: ${secret}`);
    assert.equal(dom.byId('orders-list').textContent.includes(secret), false, `the order list does not show: ${secret}`);
  }
});

test('approve opens a confirmation first (nothing is sent until it is confirmed), then sends the digest as it was read', async () => {
  let approveCount = 0;
  const s = ordState({
    requestList: { status: 200, data: [stopRequestOf()] },
    orderPost: (p) => {
      if (p !== APPROVE) return null;
      approveCount++;
      return { status: 200, data: orderOf({ state: 'STOPPED', stateReason: 'OPERATOR_STOPPED', version: 4 }) };
    },
  });
  const dom = ordLoad(s);
  await flush();
  buttonIn(dom.byId('approval-list'), '承認').fire('click');
  assert.equal(dom.byId('order-approve-dialog').open, true, 'the confirmation opens');
  assert.match(dom.byId('order-approve-text').textContent, /この order を停止します。元に戻せません/);
  assert.equal(dom.byId('order-approve-target').textContent.includes(DIGEST), false, 'the digest is not in the dialog');
  assert.equal(approveCount, 0, 'nothing is sent before the confirmation');

  dom.byId('order-approve-submit').fire('click');
  await flush();
  assert.equal(approveCount, 1);
  const sent = postsTo(dom.calls, APPROVE);
  assert.deepEqual(sent[0].body, { digest: DIGEST }, 'the body is the digest as it was read, and nothing else');
  assert.equal(dom.byId('order-approve-dialog').open, false);
  assert.match(dom.byId('approval-msg').textContent, /停止を承認しました/);
});

test('cancel in the confirmation sends nothing and closes it', async () => {
  const s = ordState({ requestList: { status: 200, data: [stopRequestOf()] } });
  const dom = ordLoad(s);
  await flush();
  buttonIn(dom.byId('approval-list'), '承認').fire('click');
  dom.byId('order-approve-cancel').fire('click');
  await flush();
  assert.equal(dom.byId('order-approve-dialog').open, false);
  assert.equal(postsTo(dom.calls, APPROVE).length, 0);
});

test('reject sends an empty JSON object (it takes no reason) and shows the fixed text', async () => {
  const s = ordState({
    requestList: { status: 200, data: [stopRequestOf()] },
    orderPost: (p) => (p === '/api/selfdev/stop-requests/' + R1 + '/reject'
      ? { status: 200, data: orderOf({ state: 'PAUSED', stateReason: 'STOP_NOT_APPROVED', version: 4 }) }
      : null),
  });
  const dom = ordLoad(s);
  await flush();
  buttonIn(dom.byId('approval-list'), '却下').fire('click');
  await flush();
  const sent = postsTo(dom.calls, '/api/selfdev/stop-requests/' + R1 + '/reject');
  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].body, {});
  assert.match(dom.byId('approval-msg').textContent, /却下しました/);
});

test('while an approval is in flight a second click sends nothing, and the buttons are disabled', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  let approveCount = 0;
  const s = ordState({
    requestList: { status: 200, data: [stopRequestOf()] },
    orderPost: (p) => {
      if (p !== APPROVE) return null;
      approveCount++;
      return gate.then(() => ({ status: 200, data: orderOf({ state: 'STOPPED', version: 4 }) }));
    },
  });
  const dom = ordLoad(s);
  await flush();
  buttonIn(dom.byId('approval-list'), '承認').fire('click');
  dom.byId('order-approve-submit').fire('click');
  dom.byId('order-approve-submit').fire('click');
  await flush();
  assert.equal(approveCount, 1, 'one request while in flight');
  assert.equal(dom.byId('order-approve-submit').disabled, true, 'the confirm button is disabled while in flight');
  assert.equal(buttonIn(dom.byId('approval-list'), '承認').disabled, true, 'the approve button is disabled while in flight');
  release();
  await flush();
  assert.equal(approveCount, 1, 'still one request after the answer');
});

test('a 409 on the approval shows the fixed text, never the server text; the dialog closes and the list is read again', async () => {
  const s = ordState({
    requestList: { status: 200, data: [stopRequestOf()] },
    orderPost: (p) => (p === APPROVE
      ? { status: 409, data: { error: 'the digest does not match the stop request', code: 'DIGEST_MISMATCH' } }
      : null),
  });
  const dom = ordLoad(s);
  await flush();
  const readsBefore = dom.calls.filter((c) => c.path === '/api/selfdev/stop-requests?state=PENDING').length;
  buttonIn(dom.byId('approval-list'), '承認').fire('click');
  dom.byId('order-approve-submit').fire('click');
  await flush();
  const msg = dom.byId('approval-msg').textContent;
  assert.match(msg, /状態が変わっていました/);
  assert.equal(msg.includes('DIGEST_MISMATCH'), false, 'the fixed code of the body is not shown');
  assert.equal(msg.includes('digest'), false, 'the server text is not shown');
  assert.equal(dom.byId('order-approve-dialog').open, false);
  const readsAfter = dom.calls.filter((c) => c.path === '/api/selfdev/stop-requests?state=PENDING').length;
  assert.equal(readsAfter > readsBefore, true, 'the list is read again');
});

test('a response that carries a title, a policy or a requester ID shows none of them in the order views', async () => {
  const s = ordState({
    orderList: { status: 200, data: [orderOf({ title: SECRET_TITLE, policy: SECRET_POLICY, requestedBy: SECRET_OWNER })] },
    orderDetail: {
      status: 200,
      data: orderDetailOf({ requestedBy: SECRET_OWNER }, stopRequestOf({ requestedBy: SECRET_OWNER, policy: SECRET_POLICY })),
    },
  });
  const dom = ordLoad(s);
  await flush();
  buttonIn(dom.byId('orders-list'), '詳細').fire('click');
  await flush();
  const text = dom.byId('orders-list').textContent;
  assert.match(text, /停止の要求: あり（理由: 暴走の疑い（RUNAWAY_SUSPECTED）/);
  for (const secret of [SECRET_TITLE, SECRET_POLICY, SECRET_OWNER, DIGEST]) {
    assert.equal(text.includes(secret), false, `not shown: ${secret}`);
    assert.equal(attrsOf(dom.byId('orders-list')).includes(secret), false, `not in an attribute: ${secret}`);
  }
});

test('an approval with an unknown requester kind or reason shows unknown with the code only, and markup is text', async () => {
  const s = ordState({
    requestList: {
      status: 200,
      data: [
        stopRequestOf({ requesterKind: 'NEW_KIND', reasonCode: 'NEW_CODE' }),
        stopRequestOf({ requestId: R2, reasonCode: '<b>x</b>' }),
      ],
    },
  });
  const dom = ordLoad(s);
  await flush();
  const text = dom.byId('approval-list').textContent;
  assert.match(text, /理由: 不明（NEW_CODE）/);
  assert.match(text, /要求した主体: 不明/);
  assert.equal(text.includes('<b>'), false);
  const tags = [];
  dom.byId('approval-list').walk((n) => tags.push(n.tagName));
  assert.equal(tags.includes('B'), false, 'no element is made from a response value');
});
