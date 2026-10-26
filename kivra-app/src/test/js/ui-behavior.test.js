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
  removeAttribute(n) { delete this.attrs[n]; }
  getAttribute(n) { return this.attrs[n] === undefined ? null : this.attrs[n]; }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  removeEventListener(type, fn) {
    const list = this.listeners[type] || [];
    const i = list.indexOf(fn);
    if (i !== -1) list.splice(i, 1);
  }
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
  const intervals = [];
  const ctx = {
    document: dom.document,
    window: dom.window,
    console,
    setInterval: (fn) => { intervals.push(fn); return 1; },
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
  return { ...dom, calls, clock, tickNow: () => intervals.forEach((fn) => fn()) };
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
  dom.byId('refresh-all-btn').fire('click');
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

test('a release dialog cancelled and opened again sends the request once (the listeners of the first opening are removed)', async () => {
  const dom = await openDetail(selfRun('UNKNOWN'), (p) => {
    if (p === '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release') {
      return { status: 200, data: { runId: SELF_RUN_ID, state: 'CANCELLED', reasonCode: 'OPERATOR_RELEASED' } };
    }
    return null;
  });
  findButton(dom, '運用者の解放…').fire('click');
  dom.byId('release-reason').value = 'やめる';
  dom.byId('release-reason').fire('input');
  dom.byId('release-cancel').fire('click');
  dom.byId('release-dialog').fire('close'); // a browser fires close on cancel; the stub's close() does not
  assert.equal(postsTo(dom.calls, '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release').length, 0, 'cancel sends nothing');

  findButton(dom, '運用者の解放…').fire('click');
  dom.byId('release-reason').value = '確認した';
  dom.byId('release-reason').fire('input');
  dom.byId('release-submit').fire('click');
  await flush();
  assert.equal(postsTo(dom.calls, '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release').length, 1, 'one request after reopening');
});

test('a release dialog closed without cancel (Esc) removes its listeners and the next opening starts with an empty reason', async () => {
  const dom = await openDetail(selfRun('UNKNOWN'), (p) => {
    if (p === '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release') {
      return { status: 200, data: { runId: SELF_RUN_ID, state: 'CANCELLED', reasonCode: 'OPERATOR_RELEASED' } };
    }
    return null;
  });
  findButton(dom, '運用者の解放…').fire('click');
  dom.byId('release-reason').value = 'やめる';
  dom.byId('release-reason').fire('input');
  dom.byId('release-dialog').fire('close'); // Esc closes the dialog without a click on cancel
  dom.byId('release-submit').fire('click'); // the listeners are gone: a click after the close sends nothing
  await flush();
  assert.equal(postsTo(dom.calls, '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release').length, 0, 'no request after the close');

  findButton(dom, '運用者の解放…').fire('click');
  assert.equal(dom.byId('release-reason').value, '', 'the reopened reason is empty');
  assert.equal(dom.byId('release-submit').disabled, true, 'the reopened submit needs a reason');
  dom.byId('release-reason').value = '確認した';
  dom.byId('release-reason').fire('input');
  dom.byId('release-submit').fire('click');
  await flush();
  assert.equal(postsTo(dom.calls, '/api/admin/selfdev/runs/' + SELF_RUN_ID + '/release').length, 1, 'one request after reopening');
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
    if (p === '/api/selfdev/autopilot/promotion') return s.promotion || { status: 404, data: { error: 'not found' } };
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

test('a stop dialog cancelled and opened again sends the request once (the listeners of the first opening are removed)', async () => {
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
  dom.byId('stop-reason').value = 'やめる';
  dom.byId('stop-reason').fire('input');
  dom.byId('stop-cancel').fire('click');
  dom.byId('stop-dialog').fire('close'); // a browser fires close on cancel; the stub's close() does not
  assert.equal(postsTo(dom.calls, '/api/selfdev/goals/' + G1 + '/stop').length, 0, 'cancel sends nothing');

  buttonIn(dom.byId('plans-list'), '中止…').fire('click');
  dom.byId('stop-reason').value = 'もう要らない';
  dom.byId('stop-reason').fire('input');
  dom.byId('stop-submit').fire('click');
  await flush();
  assert.equal(postsTo(dom.calls, '/api/selfdev/goals/' + G1 + '/stop').length, 1, 'one request after reopening');
});

test('while a stop is in flight the send and cancel buttons are disabled, and they are enabled again after the answer', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  const goal = goalSummary({ state: 'TASKS_QUEUED', version: 4 });
  const s = apState({ goals: [goal], detail: detailOf(goal, [taskOf({ version: 0 })]) });
  s.other = (p, init) => (p === '/api/selfdev/goals/' + G1 + '/stop' && init.method === 'POST'
    ? gate.then(() => ({ status: 200, data: goalSummary({ state: 'STOPPED', version: 5 }) }))
    : null);
  const dom = load(apResponder(s));
  await flush();
  buttonIn(dom.byId('plans-list'), '詳細').fire('click');
  await flush();
  buttonIn(dom.byId('plans-list'), '中止…').fire('click');
  dom.byId('stop-reason').value = 'もう要らない';
  dom.byId('stop-reason').fire('input');
  dom.byId('stop-submit').fire('click');
  dom.byId('stop-submit').fire('click');
  await flush();
  assert.equal(dom.byId('stop-submit').disabled, true, 'disabled while the stop is in flight');
  assert.equal(dom.byId('stop-cancel').disabled, true, 'cancel is disabled while the stop is in flight');
  assert.equal(postsTo(dom.calls, '/api/selfdev/goals/' + G1 + '/stop').length, 1, 'a second click sends nothing');
  release();
  await flush();
  assert.equal(dom.byId('stop-submit').disabled, false, 'submit is enabled again after the answer');
  assert.equal(dom.byId('stop-cancel').disabled, false, 'cancel is enabled again after the answer');
});

test('a stop reason that is blank, longer than 500 characters or has a control character cannot be sent', async () => {
  const goal = goalSummary({ state: 'TASKS_QUEUED', version: 4 });
  const s = apState({ goals: [goal], detail: detailOf(goal, [taskOf({ version: 0 })]) });
  const dom = load(apResponder(s));
  await flush();
  buttonIn(dom.byId('plans-list'), '詳細').fire('click');
  await flush();
  buttonIn(dom.byId('plans-list'), '中止…').fire('click');

  for (const reason of ['', '   ', 'x'.repeat(501), 'あ'.repeat(500) + 'い', 'やめる\u0007']) {
    dom.byId('stop-reason').value = reason;
    dom.byId('stop-reason').fire('input');
    assert.equal(dom.byId('stop-submit').disabled, true, `${JSON.stringify(reason).slice(0, 20)}: cannot send`);
    dom.byId('stop-submit').fire('click');
    await flush();
    assert.equal(postsTo(dom.calls, '/api/selfdev/goals/' + G1 + '/stop').length, 0, `${JSON.stringify(reason).slice(0, 20)}: no request`);
  }
  assert.equal(dom.byId('stop-dialog').open, true, 'the dialog stays open');
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

const PROMOTION_OK = {
  conditions: [
    { id: 'APPROVED_COMPLETED', measured: true, current: 10, required: 10, met: true },
    { id: 'POLICY_REJECTED', measured: true, current: 2, required: 0, met: false },
    { id: 'OUT_OF_SCOPE', measured: true, current: 0, required: 0, met: true },
    { id: 'BUDGET_CONFIRMED', measured: false, current: null, required: null, met: null },
  ],
};

test('the promotion readout shows each condition as fixed words and numbers, and the unmeasured one says so', async () => {
  const dom = load(apResponder(apState({ promotion: { status: 200, data: PROMOTION_OK } })));
  await flush();
  const items = dom.byId('promotion-list').children.map((c) => c.textContent);
  assert.equal(items[0], '承認したタスクのうち完了したもの：10 件（必要: 10 件以上、達成）');
  assert.equal(items[1], 'ポリシーで止まった承認済みタスク：2 件（必要: ゼロ、未達）');
  assert.equal(items[2], '範囲外の変更として止まったタスク：0 件（必要: ゼロ、達成）');
  assert.match(items[3], /^予算の計上の実機確認（#230）：未計測/);
  assert.match(dom.byId('promotion-status').textContent, /未達が 1 件/);
  const reads = dom.calls.filter((c) => c.path === '/api/selfdev/autopilot/promotion');
  assert.equal(reads.length >= 1 && reads.every((c) => c.method === 'GET'), true, 'the readout is a GET only');
});

test('a failed or malformed readout shows a fixed message, and no server text is shown', async () => {
  const down = load(apResponder(apState({ promotion: { status: 500, data: { error: 'LEAK-500' } } })));
  await flush();
  assert.match(down.byId('promotion-status').textContent, /取得できませんでした/);
  assert.equal(down.byId('promotion-list').children.length, 0);
  assert.doesNotMatch(down.byId('promotion-status').textContent, /LEAK/);

  const bad = load(apResponder(apState({ promotion: { status: 200, data: { conditions: [{ id: 'X', measured: true, current: '3', required: 0, met: true }] } } })));
  await flush();
  assert.match(bad.byId('promotion-status').textContent, /取得できませんでした/);
  assert.equal(bad.byId('promotion-list').children.length, 0, 'a value that is not a number is not shown');
});

test('an id the page does not know is shown as an unknown condition, never with the server text', async () => {
  const dom = load(apResponder(apState({ promotion: { status: 200, data: { conditions: [
    { id: '<img src=x onerror=alert(1)>', measured: false, current: null, required: null, met: null },
  ] } } })));
  await flush();
  const items = dom.byId('promotion-list').children;
  assert.equal(items[0].textContent, '不明な条件');
  assert.equal(items[0].children.filter((c) => c.nodeType !== 3).length, 0, 'no element is built from the id');
});

test('the readout is not read while the autopilot is off', async () => {
  const off = load(apResponder(apState({ autopilot: 503 })));
  await flush();
  assert.equal(off.calls.filter((c) => c.path === '/api/selfdev/autopilot/promotion').length, 0);
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
  dom.byId('refresh-all-btn').fire('click');
  await flush();
  assert.ok(dom.calls.some((c) => c.path === '/api/runs'), 'the run list is still requested');
  assert.equal(dom.byId('refresh-all-btn').disabled, false);
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

const C1 = '01ORDER' + 'C'.repeat(19);
const C2 = '01ORDER' + 'D'.repeat(19);
const ORPHAN = '01ORDER' + 'E'.repeat(19);
const ROOT2 = '01ORDER' + 'F'.repeat(19);

test('the order list is a tree: a child follows its parent with one more indent, an orphan is top-level, and no title or policy is shown', async () => {
  // The list is in the API's order. C1 is a child of O1, C2 a child of C1, ORPHAN's parent is not in the list, ROOT2 is a second root.
  const list = [
    orderOf({ orderId: O1, depth: 0, childCount: 1, spentJpy: 300, orderBudgetJpy: 1000, title: SECRET_TITLE, policy: SECRET_POLICY }),
    orderOf({ orderId: ROOT2, depth: 0, childCount: 0, spentJpy: 0, orderBudgetJpy: 500 }),
    orderOf({ orderId: C1, parentOrderId: O1, depth: 1, childCount: 1, spentJpy: 200, orderBudgetJpy: 600 }),
    orderOf({ orderId: C2, parentOrderId: C1, depth: 2, childCount: 0, spentJpy: 0, orderBudgetJpy: 100 }),
    orderOf({ orderId: ORPHAN, parentOrderId: '01ORDER' + 'Z'.repeat(19), depth: 3, childCount: 0, spentJpy: 0, orderBudgetJpy: 50 }),
  ];
  const dom = ordLoad(ordState({ orderList: { status: 200, data: list } }));
  await flush();
  const items = dom.byId('orders-list').children.filter((c) => c.tagName === 'LI');
  const ids = items.map((li) => li.textContent.match(/order (\S+?)状態/)[1]);
  assert.deepEqual(ids, [O1, C1, C2, ROOT2, ORPHAN], 'a parent, then its children in depth-first order, then the next root');
  const levels = items.map((li) => li.className.match(/order-level-(\d)/)[1]);
  assert.deepEqual(levels, ['0', '1', '2', '0', '0'], 'the indent follows the tree level, and an orphan is level 0');
  assert.match(items[0].textContent, /子の数: 1/);
  assert.match(items[0].textContent, /使用: 300 円 \/ 予算: 1,000 円/);
  assert.match(items[2].textContent, /深さ: 2/, 'the depth is still shown as a number');
  const text = dom.byId('orders-list').textContent;
  assert.equal(text.includes(SECRET_TITLE), false, 'a title is never shown');
  assert.equal(text.includes(SECRET_POLICY), false, 'a policy is never shown');
});

test('a child stop reason shows its fixed text in the detail, and an unknown child-like code shows only the code', async () => {
  const s = ordState({
    orderDetail: { status: 200, data: orderDetailOf({ state: 'STOP_REQUESTED', stateReason: 'STOP_REQUESTED' }, stopRequestOf({ reasonCode: 'CHILD_BUDGET_EXHAUSTED', digest: null })) },
  });
  const dom = ordLoad(s);
  await flush();
  buttonIn(dom.byId('orders-list'), '詳細').fire('click');
  await flush();
  const text = dom.byId('orders-list').textContent;
  assert.match(text, /親が自動で停止しました（子の予算を使い切ったため）（CHILD_BUDGET_EXHAUSTED）/);
  assert.equal(text.includes(DIGEST), false, 'the digest is never shown');
});

// ---- Self-development memory (ADR 0016 P2, operator): the card, the list, the filters, the deletions ----

const MEM_ID1 = '01MEMORY' + 'A'.repeat(18);
const MEM_ID2 = '01MEMORY' + 'B'.repeat(18);
const MEM_ID3 = '01MEMORY' + 'C'.repeat(18);

function memOf(over = {}) {
  return {
    recordId: MEM_ID1, kind: 'OPERATOR_NOTE', source: 'OPERATOR', tier: 'TRUSTED_OPERATOR', goalId: null,
    body: '覚え書き', bodyWithheld: false, code: null, countValue: null, createdAt: '2026-10-20T10:00:00Z', ...over,
  };
}

function memList(items, over = {}) {
  return { status: 200, data: { items, total: items.length, limit: 20, offset: 0, tombstones: 0, deletionAudit: 0, ...over } };
}

/** The memory routes. `m.list` is a response or a function of the path; `m.cond` and `m.one` answer the deletions. */
function memLoad(m, over = {}) {
  const s = apState(over);
  s.other = (p, init) => {
    if (p.startsWith('/api/selfdev/memory?') && init.method === 'GET') return typeof m.list === 'function' ? m.list(p) : m.list;
    if (p === '/api/selfdev/memory/delete-by-condition' && init.method === 'POST') {
      return m.cond ? m.cond(init) : { status: 200, data: { matched: 0, deleted: 0, dryRun: true } };
    }
    const one = /^\/api\/selfdev\/memory\/([0-9A-Z]{26})\/delete$/.exec(p);
    if (one && init.method === 'POST') return m.one ? m.one(one[1], init) : { status: 200, data: { recordId: one[1], deleted: true } };
    return null;
  };
  return load(apResponder(s));
}

test('the memory card stays hidden on 503, 404 and 403, and while the autopilot is off; on a 200 it shows and is read once', async () => {
  const hidden = [{ status: 503, data: { error: 'x' } }, { status: 404, data: { error: 'x' } }, { status: 403, data: { error: 'forbidden' } }];
  for (const r of hidden) {
    const dom = memLoad({ list: r });
    await flush();
    assert.equal(dom.byId('memory-section').hidden, true, 'hidden on ' + r.status);
  }
  let dom = memLoad({ list: memList([memOf()]) }, { autopilot: 503 });
  await flush();
  assert.equal(dom.byId('memory-section').hidden, true, 'the autopilot is off: no card');
  assert.equal(dom.calls.filter((c) => c.path.startsWith('/api/selfdev/memory')).length, 0, 'nothing is read while the autopilot is off');

  dom = memLoad({ list: memList([memOf()]) });
  await flush();
  assert.equal(dom.byId('memory-section').hidden, false, 'an operator sees the card');
  assert.equal(dom.calls.filter((c) => c.path.startsWith('/api/selfdev/memory?')).length, 1, 'read once when the autopilot is first seen on');
});

test('the memory list shows fixed labels, withholds an UNTRUSTED text, shows an unknown kind as unknown, and never makes markup', async () => {
  const dom = memLoad({
    list: memList(
      [
        memOf({ recordId: MEM_ID1, kind: 'OPERATOR_NOTE', body: EVIL }),
        memOf({ recordId: MEM_ID2, kind: 'PLAN_RATIONALE', tier: 'UNTRUSTED', body: null, bodyWithheld: true }),
        memOf({ recordId: MEM_ID3, kind: 'WEIRD<img src=x onerror=alert(1)>', code: 'NEW<img>', countValue: null }),
      ],
      { tombstones: 2, deletionAudit: 2 },
    ),
  });
  await flush();
  const text = dom.byId('memory-list').textContent;
  assert.match(text, /運用者の覚え書き/);
  assert.match(text, /計画の理由（モデル、未検証）/);
  assert.match(text, /本文は表示しません（未検証の文章）/);
  assert.match(text, /不明/, 'an unknown kind and an unknown code show as unknown');
  assert.equal(text.includes('WEIRD'), false, 'an unknown kind is not shown');
  assert.equal(text.includes('NEW<'), false, 'an unknown code is not shown');
  assert.equal(text.includes(EVIL), true, 'an operator text is shown as text, character for character');
  const tags = [];
  dom.byId('memory-list').walk((n) => tags.push(n.tagName));
  assert.equal(tags.includes('IMG'), false, 'no image is made from a value');
  assert.equal(tags.includes('SCRIPT'), false, 'no script is made from a value');
  assert.equal(dom.byId('memory-counts').textContent, '墓標（削除された文章）: 2 件、削除の監査: 2 件');
});

test('the filter sends the chosen kind and tier, and the paging moves by 20 within the total', async () => {
  const m = {
    list: (p) => (p.includes('offset=20')
      ? memList([memOf({ recordId: MEM_ID2 })], { total: 21, offset: 20 })
      : memList([memOf()], { total: 21 })),
  };
  const dom = memLoad(m);
  await flush();
  assert.equal(dom.byId('memory-prev').disabled, true, 'the first page: prev is off');
  assert.equal(dom.byId('memory-next').disabled, false, 'more records: next is on');
  dom.byId('memory-next').fire('click');
  await flush();
  assert.equal(dom.calls.some((c) => c.path.includes('offset=20')), true, 'the next page is read with offset 20');
  assert.equal(dom.byId('memory-next').disabled, true, 'the last page: next is off');
  assert.equal(dom.byId('memory-prev').disabled, false);

  dom.byId('memory-kind').value = 'OPERATOR_NOTE';
  dom.byId('memory-tier').value = 'TRUSTED_OPERATOR';
  dom.byId('memory-apply').fire('click');
  await flush();
  const applied = dom.calls.filter((c) => c.path.startsWith('/api/selfdev/memory?')).pop();
  assert.match(applied.path, /kind=OPERATOR_NOTE/);
  assert.match(applied.path, /tier=TRUSTED_OPERATOR/);
  assert.match(applied.path, /offset=0/, 'a new filter starts at the first page');

  dom.byId('memory-kind').value = 'NOT_A_KIND';
  dom.byId('memory-tier').value = '';
  dom.byId('memory-apply').fire('click');
  await flush();
  const unknown = dom.calls.filter((c) => c.path.startsWith('/api/selfdev/memory?')).pop();
  assert.equal(unknown.path.includes('kind='), false, 'a value that is not in the list is not sent');
});

test('a deletion asks first: nothing is sent until a reason is entered and confirmed; then the list is read again', async () => {
  let posted = null;
  const m = {
    list: memList([memOf()]),
    one: (id, init) => {
      posted = { id, body: JSON.parse(init.body) };
      return { status: 200, data: { recordId: id, deleted: true } };
    },
  };
  const dom = memLoad(m);
  await flush();
  dom.byId('memory-list').buttons()[0].fire('click');
  await flush();
  assert.equal(dom.byId('memory-dialog').open, true, 'the confirmation opens');
  assert.equal(posted, null, 'nothing is sent before the confirmation');
  assert.equal(dom.byId('memory-submit').disabled, true, 'no reason, no submit');
  dom.byId('memory-reason').value = '  誤って書いた  ';
  dom.byId('memory-reason').fire('input');
  assert.equal(dom.byId('memory-submit').disabled, false);
  dom.byId('memory-submit').fire('click');
  await flush();
  assert.deepEqual(posted, { id: MEM_ID1, body: { reason: '誤って書いた' } }, 'the reason is sent trimmed');
  assert.equal(dom.byId('memory-dialog').open, false, 'the dialog closes on success');
  assert.equal(dom.calls.filter((c) => c.path.startsWith('/api/selfdev/memory?')).length >= 2, true, 'the list is read again');
});

test('a second confirm while a deletion is in flight sends nothing more', async () => {
  let count = 0;
  let release = null;
  const m = {
    list: memList([memOf()]),
    one: () => {
      count += 1;
      return new Promise((resolve) => { release = resolve; });
    },
  };
  const dom = memLoad(m);
  await flush();
  dom.byId('memory-list').buttons()[0].fire('click');
  await flush();
  dom.byId('memory-reason').value = 'x';
  dom.byId('memory-reason').fire('input');
  dom.byId('memory-submit').fire('click');
  dom.byId('memory-submit').fire('click');
  await flush();
  assert.equal(count, 1, 'one request for two confirms');
  release({ status: 200, data: { recordId: MEM_ID1, deleted: true } });
  await flush();
});

test('a failure shows a fixed text and never the server text; a 404 reads the list again and keeps the dialog open', async () => {
  const m = {
    list: memList([memOf()]),
    one: () => ({ status: 404, data: { error: 'SERVER-SECRET-TEXT' } }),
  };
  const dom = memLoad(m);
  await flush();
  dom.byId('memory-list').buttons()[0].fire('click');
  await flush();
  dom.byId('memory-reason').value = 'x';
  dom.byId('memory-reason').fire('input');
  dom.byId('memory-submit').fire('click');
  await flush();
  assert.equal(dom.byId('memory-dialog').open, true, 'the dialog stays open on failure');
  assert.equal(dom.byId('memory-error').hidden, false);
  assert.equal(dom.byId('memory-error').textContent, '記録が見つかりませんでした（削除済みの可能性があります）。一覧を読み直しました。');
  assert.equal(dom.byId('memory-error').textContent.includes('SERVER'), false, 'the server text is never shown');
  assert.equal(dom.calls.filter((c) => c.path.startsWith('/api/selfdev/memory?')).length >= 2, true, 'the list is read again');
});

test('the conditional deletion: a dry run needs a condition, shows the count, and enables the delete only for that filter; a real one sends the filter and the reason', async () => {
  const bodies = [];
  const m = {
    list: memList([memOf()]),
    cond: (init) => {
      const b = JSON.parse(init.body);
      bodies.push(b);
      return { status: 200, data: b.dryRun ? { matched: 4, deleted: 0, dryRun: true } : { matched: 4, deleted: 4, dryRun: false } };
    },
  };
  const dom = memLoad(m);
  await flush();
  assert.equal(dom.byId('memory-cond-delete').disabled, true, 'no dry run yet: the delete is off');

  dom.byId('memory-dryrun').fire('click');
  await flush();
  assert.equal(bodies.length, 0, 'no condition: nothing is sent');
  assert.match(dom.byId('memory-dryrun-result').textContent, /条件を 1 つ以上/);

  dom.byId('memory-kind').value = 'OPERATOR_NOTE';
  dom.byId('memory-apply').fire('click');
  await flush();
  dom.byId('memory-dryrun').fire('click');
  await flush();
  assert.deepEqual(bodies[0], { kind: 'OPERATOR_NOTE', dryRun: true }, 'the dry run sends the condition only');
  assert.match(dom.byId('memory-dryrun-result').textContent, /該当: 4 件/);
  assert.equal(dom.byId('memory-cond-delete').disabled, false, 'the same filter with a count of 1 or more: the delete is on');

  dom.byId('memory-cond-delete').fire('click');
  assert.equal(dom.byId('memory-dialog').open, true, 'the confirmation opens');
  dom.byId('memory-reason').value = '整理';
  dom.byId('memory-reason').fire('input');
  dom.byId('memory-submit').fire('click');
  await flush();
  assert.deepEqual(bodies[1], { kind: 'OPERATOR_NOTE', dryRun: false, reason: '整理' });
  assert.equal(dom.byId('memory-dialog').open, false);
});

test('the reason must be 1 to 500 characters without control characters, and its length is shown', async () => {
  const dom = memLoad({ list: memList([memOf()]) });
  await flush();
  dom.byId('memory-list').buttons()[0].fire('click');
  await flush();
  const input = dom.byId('memory-reason');
  input.value = 'a\u0001b';
  input.fire('input');
  assert.equal(dom.byId('memory-submit').disabled, true, 'a control character disables the submit');
  input.value = 'a'.repeat(501);
  input.fire('input');
  assert.equal(dom.byId('memory-submit').disabled, true, '501 characters disable the submit');
  input.value = 'a'.repeat(500);
  input.fire('input');
  assert.equal(dom.byId('memory-submit').disabled, false, '500 characters are allowed');
  assert.equal(dom.byId('memory-count').textContent, '500 / 500');
});

test('the list and the run detail are shown one at a time, and the hash switches between them', async () => {
  const dom = load(defaults(true));
  await flush();
  assert.equal(dom.byId('view-list').hidden, false, 'no hash: the run list is shown');
  assert.equal(dom.byId('view-detail').hidden, true, 'no hash: the detail is hidden');

  dom.window.location.hash = '#/runs/' + SELF_RUN_ID;
  dom.hashListeners.forEach((fn) => fn());
  await flush();
  assert.equal(dom.byId('view-list').hidden, true, 'a run hash hides the list');
  assert.equal(dom.byId('view-detail').hidden, false, 'a run hash shows the detail');

  dom.window.location.hash = '';
  dom.hashListeners.forEach((fn) => fn());
  await flush();
  assert.equal(dom.byId('view-list').hidden, false, 'back to no hash: the list is shown again');
  assert.equal(dom.byId('view-detail').hidden, true, 'back to no hash: the detail is hidden again');
});

// ---- Order create (ADR 0015): the form on the order section posts the title and the policy; neither is shown back ----

const ORDER_TITLE = 'ROOT-TITLE-SECRET';
const ORDER_POLICY = 'ROOT-POLICY-SECRET-TEXT';
const ORDER_CREATE_MSG_SAVED = 'order を作成しました。';

/** Answers the order create POST with `post(init)`; every other route is left to the order routes. */
function orderCreateState(post) {
  return ordState({
    orderPost: (p, init) => (p === '/api/selfdev/orders' && init.method === 'POST' ? post(init) : null),
  });
}

function orderCreateSubmit(dom) {
  dom.byId('order-create-form').fire('submit', { preventDefault() {} });
  return flush();
}

function fillOrderCreate(dom, title, policy) {
  dom.byId('order-title').value = title;
  dom.byId('order-policy').value = policy;
}

function orderCreatePosts(dom) { return postsTo(dom.calls, '/api/selfdev/orders'); }

test('a create posts the trimmed title and policy as JSON, clears the inputs, and reads the order list again', async () => {
  const s = orderCreateState(() => ({ status: 201, data: orderOf({ orderId: O2 }) }));
  const dom = ordLoad(s);
  await flush();
  const listReadsBefore = dom.calls.filter((c) => c.method === 'GET' && c.path === '/api/selfdev/orders').length;
  fillOrderCreate(dom, '  ' + ORDER_TITLE + '  ', '\n' + ORDER_POLICY + '\n');
  await orderCreateSubmit(dom);
  const sent = orderCreatePosts(dom);
  assert.equal(sent.length, 1, 'one POST');
  assert.deepEqual(sent[0].body, { title: ORDER_TITLE, policy: ORDER_POLICY, maxCycles: 1 }, 'the trimmed title and policy and the default cycle count, and no other field');
  assert.equal(dom.byId('order-title').value, '', 'the title is cleared after success');
  assert.equal(dom.byId('order-policy').value, '', 'the policy is cleared after success');
  assert.equal(dom.byId('order-create-result').textContent, ORDER_CREATE_MSG_SAVED);
  assert.equal(dom.byId('order-create-result').className.includes('ok'), true);
  const listReadsAfter = dom.calls.filter((c) => c.method === 'GET' && c.path === '/api/selfdev/orders').length;
  assert.equal(listReadsAfter > listReadsBefore, true, 'the list is read again');
});

test('the cycle count is sent as an integer and reset to 1 after success', async () => {
  const dom = ordLoad(orderCreateState(() => ({ status: 201, data: orderOf({ orderId: O2 }) })));
  await flush();
  fillOrderCreate(dom, ORDER_TITLE, ORDER_POLICY);
  dom.byId('order-max-cycles').value = '3';
  await orderCreateSubmit(dom);
  const sent = orderCreatePosts(dom);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].body.maxCycles, 3, 'a number, not a string');
  assert.equal(dom.byId('order-max-cycles').value, '1', 'reset after success');
});

test('a cycle count of 0, 11, a decimal or text is refused before any request', async () => {
  const dom = ordLoad(orderCreateState(() => ({ status: 201, data: orderOf({ orderId: O2 }) })));
  await flush();
  for (const bad of ['0', '11', '2.5', 'abc', '-1', '1e1']) {
    fillOrderCreate(dom, ORDER_TITLE, ORDER_POLICY);
    dom.byId('order-max-cycles').value = bad;
    await orderCreateSubmit(dom);
    assert.equal(orderCreatePosts(dom).length, 0, 'sends nothing for ' + bad);
    assert.match(dom.byId('order-create-result').textContent, /最大の周の数/);
  }
});

test('a 400 MAX_CYCLES_INVALID shows a fixed text and not the server text or code', async () => {
  const dom = ordLoad(orderCreateState(() => ({ status: 400, data: { error: 'maxCycles must be a whole number from 1 to the configured cap', code: 'MAX_CYCLES_INVALID' } })));
  await flush();
  fillOrderCreate(dom, ORDER_TITLE, ORDER_POLICY);
  dom.byId('order-max-cycles').value = '5';
  await orderCreateSubmit(dom);
  const msg = dom.byId('order-create-result').textContent;
  assert.match(msg, /^最大の周の数は 1 以上の整数で、サーバーの上限以下にしてください$/);
  assert.equal(msg.includes('MAX_CYCLES_INVALID') || msg.includes('configured cap'), false);
  assert.equal(dom.byId('order-title').value, ORDER_TITLE, 'kept for a retry');
});

test('409 ROOT_EXISTS, 400, 403 and 503 give the fixed texts, and a server text or code is never shown', async () => {
  const cases = [
    // The real server answers a second root with 409 and only its error text; there is no `code` field (SelfDevOrderRoutes, ROOT_EXISTS).
    [409, { error: 'the root order already exists' }, /^ルートの order は既にあります$/],
    [409, { error: 'the root order already exists', code: 'ROOT_EXISTS' }, /^ルートの order は既にあります$/],
    [400, { error: 'policy must be 1-2000 characters', code: 'POLICY_INVALID' }, /^題名\/方針が不正です（長さ、制御文字）$/],
    [403, { error: 'the AI principal cannot create an order' }, /^この操作は許可されていません$/],
    [503, { error: 'orders are disabled' }, /^order は無効です$/],
    [500, { error: 'boom' }, /^作成に失敗しました$/],
  ];
  for (const [status, data, expected] of cases) {
    const dom = ordLoad(orderCreateState(() => ({ status, data })));
    await flush();
    fillOrderCreate(dom, ORDER_TITLE, ORDER_POLICY);
    await orderCreateSubmit(dom);
    const msg = dom.byId('order-create-result').textContent;
    assert.match(msg, expected, 'status ' + status);
    assert.equal(msg.includes('boom'), false, 'the server text is not shown');
    assert.equal(msg.includes('ROOT_EXISTS') || msg.includes('POLICY_INVALID'), false, 'no fixed code is shown');
    assert.equal(dom.byId('order-title').value, ORDER_TITLE, 'a failed create keeps the title for a retry');
  }
});

test('a create never shows the title or the policy in the result, the errors or the list, on success or failure', async () => {
  for (const post of [
    () => ({ status: 201, data: orderOf({ orderId: O2 }) }),
    () => ({ status: 400, data: { error: 'title ' + ORDER_TITLE + ' ' + ORDER_POLICY } }),
    () => ({ status: 409, data: { error: ORDER_TITLE, code: 'ROOT_EXISTS' } }),
  ]) {
    const dom = ordLoad(orderCreateState(post));
    await flush();
    fillOrderCreate(dom, ORDER_TITLE, ORDER_POLICY);
    await orderCreateSubmit(dom);
    const shown = dom.byId('order-create-result').textContent + dom.byId('orders-msg').textContent + dom.byId('orders-status').textContent;
    assert.equal(shown.includes(ORDER_TITLE), false, 'the title is not shown');
    assert.equal(shown.includes(ORDER_POLICY), false, 'the policy is not shown');
  }
});

test('a double submit while a create is in flight sends one request and disables the controls', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  const s = orderCreateState(() => gate.then(() => ({ status: 201, data: orderOf({ orderId: O2 }) })));
  const dom = ordLoad(s);
  await flush();
  fillOrderCreate(dom, ORDER_TITLE, ORDER_POLICY);
  dom.byId('order-create-form').fire('submit', { preventDefault() {} });
  dom.byId('order-create-form').fire('submit', { preventDefault() {} });
  await flush();
  assert.equal(orderCreatePosts(dom).length, 1, 'one request while in flight');
  assert.equal(dom.byId('order-create-submit').disabled, true, 'the submit button is disabled while in flight');
  assert.equal(dom.byId('order-title').disabled, true, 'the title is read-only while in flight');
  release();
  await flush();
  assert.equal(orderCreatePosts(dom).length, 1, 'still one request after the answer');
  assert.equal(dom.byId('order-create-submit').disabled, false, 'enabled again after the answer');
});

test('a control character, an empty title or an over-long policy is refused before any request', async () => {
  const dom = ordLoad(orderCreateState(() => ({ status: 201, data: orderOf({ orderId: O2 }) })));
  await flush();
  fillOrderCreate(dom, 'a\u0007b', ORDER_POLICY);
  await orderCreateSubmit(dom);
  assert.equal(orderCreatePosts(dom).length, 0, 'a control character in the title sends nothing');
  assert.equal(dom.byId('order-create-result').textContent.includes('a\u0007b'), false, 'the title is not shown');

  fillOrderCreate(dom, '   ', ORDER_POLICY);
  await orderCreateSubmit(dom);
  assert.equal(orderCreatePosts(dom).length, 0, 'a blank title sends nothing');

  fillOrderCreate(dom, ORDER_TITLE, 'x'.repeat(2001));
  await orderCreateSubmit(dom);
  assert.equal(orderCreatePosts(dom).length, 0, 'a policy over 2000 characters sends nothing');

  fillOrderCreate(dom, ORDER_TITLE, 'line one\n\tline two\u0000');
  await orderCreateSubmit(dom);
  assert.equal(orderCreatePosts(dom).length, 0, 'a NUL in the policy sends nothing; a line break and a tab would be allowed');
  assert.match(dom.byId('order-create-result').textContent, /制御文字は使えません/);
});

test('the create is not sent while the order list is hidden (autopilot off)', async () => {
  const dom = ordLoad(ordState({ autopilot: 503, orderPost: (p, init) => (p === '/api/selfdev/orders' && init.method === 'POST' ? { status: 201, data: orderOf({ orderId: O2 }) } : null) }));
  await flush();
  assert.equal(dom.byId('orders-section').hidden, true, 'autopilot off: the section is hidden');
  fillOrderCreate(dom, ORDER_TITLE, ORDER_POLICY);
  dom.byId('order-create-form').fire('submit', { preventDefault() {} });
  await flush();
  assert.equal(orderCreatePosts(dom).length, 0);
});

test('the title and policy counters count code points, not UTF-16 units', async () => {
  const dom = ordLoad(orderCreateState(() => ({ status: 201, data: orderOf({ orderId: O2 }) })));
  await flush();
  dom.byId('order-title').value = '𠮷a';
  dom.byId('order-title').fire('input');
  assert.equal(dom.byId('order-title-count').textContent, '2 / 200');
  dom.byId('order-policy').value = 'あ'.repeat(5);
  dom.byId('order-policy').fire('input');
  assert.equal(dom.byId('order-policy-count').textContent, '5 / 2000');
});

// ---- Stop request and cancel (ADR 0015, docs/runbook-selfdev.md §7.6.7): the request is sent with the version read and a requestable code ----

const STOP_REQ_PATH = '/api/selfdev/orders/' + O1 + '/stop-requests';
const CANCEL_PATH = '/api/selfdev/stop-requests/' + R1 + '/cancel';
const STOP_REQ_SECRET = 'STOP-REQUEST-SECRET-BODY';

async function openStopDialog(dom) {
  buttonIn(dom.byId('orders-list'), '詳細').fire('click');
  await flush();
  buttonIn(dom.byId('orders-list'), '停止を要求').fire('click');
}

test('the stop request button appears only for ACTIVE or PAUSED with no waiting request; the cancel button only when one waits', async () => {
  for (const over of [{ state: 'ACTIVE' }, { state: 'PAUSED', stateReason: 'OPERATOR_PAUSED' }]) {
    const dom = ordLoad(ordState({ orderDetail: { status: 200, data: orderDetailOf(over) } }));
    await flush();
    buttonIn(dom.byId('orders-list'), '詳細').fire('click');
    await flush();
    assert.equal(hasButton(dom.byId('orders-list'), '停止を要求'), true, `${over.state}: the request button`);
    assert.equal(hasButton(dom.byId('orders-list'), '要求を取り消す'), false, `${over.state}: no cancel without a request`);
  }

  const withPending = ordLoad(ordState({
    orderDetail: { status: 200, data: orderDetailOf({ state: 'STOP_REQUESTED', stateReason: 'STOP_REQUESTED', version: 4 }, stopRequestOf()) },
  }));
  await flush();
  buttonIn(withPending.byId('orders-list'), '詳細').fire('click');
  await flush();
  assert.equal(hasButton(withPending.byId('orders-list'), '停止を要求'), false, 'a waiting request: no second request');
  assert.equal(hasButton(withPending.byId('orders-list'), '要求を取り消す'), true, 'a waiting request: the cancel is offered');

  const stopped = ordLoad(ordState({ orderDetail: { status: 200, data: orderDetailOf({ state: 'STOPPED', stateReason: 'OPERATOR_STOPPED' }) } }));
  await flush();
  buttonIn(stopped.byId('orders-list'), '詳細').fire('click');
  await flush();
  assert.equal(hasButton(stopped.byId('orders-list'), '停止を要求'), false, 'STOPPED: no request');
});

test('the request dialog offers only the requestable codes; nothing is sent until a code is chosen and confirmed; then the detail and the list are read again', async () => {
  let requestCount = 0;
  const s = ordState({
    orderPost: (p, init) => {
      if (p !== STOP_REQ_PATH) return null;
      requestCount++;
      return { status: 201, data: { requestId: R1, orderId: O1, state: 'PENDING' } };
    },
  });
  const dom = ordLoad(s);
  await flush();
  await openStopDialog(dom);
  assert.equal(dom.byId('order-stop-dialog').open, true, 'the dialog opens');
  assert.equal(dom.byId('order-stop-submit').disabled, true, 'no code chosen: confirm is disabled');
  const options = dom.byId('order-stop-reason').children.map((o) => o.value);
  assert.deepEqual(options, ['', 'OPERATOR_REQUEST', 'RUNAWAY_SUSPECTED', 'BUDGET_CONCERN', 'SCOPE_CONCERN'], 'the requestable codes only');
  assert.equal(options.includes('CHILD_CYCLE_FAILURES') || options.includes('CHILD_BUDGET_EXHAUSTED'), false, 'the automatic codes are not offered');
  assert.equal(requestCount, 0, 'nothing sent before a choice');

  const readsBefore = dom.calls.filter((c) => c.path === '/api/selfdev/orders' && c.method === 'GET').length;
  dom.byId('order-stop-reason').value = 'BUDGET_CONCERN';
  dom.byId('order-stop-reason').fire('change');
  assert.equal(dom.byId('order-stop-submit').disabled, false, 'a code is chosen: confirm is enabled');
  assert.equal(requestCount, 0, 'choosing sends nothing');

  dom.byId('order-stop-submit').fire('click');
  await flush();
  assert.equal(requestCount, 1);
  const sent = postsTo(dom.calls, STOP_REQ_PATH);
  assert.deepEqual(sent[0].body, { version: 3, reasonCode: 'BUDGET_CONCERN' }, 'the body is the version read and the code, and nothing else');
  assert.equal(dom.byId('order-stop-dialog').open, false, 'the dialog closes on success');
  assert.match(dom.byId('orders-msg').textContent, /停止を要求しました/);
  const readsAfter = dom.calls.filter((c) => c.path === '/api/selfdev/orders' && c.method === 'GET').length;
  assert.equal(readsAfter > readsBefore, true, 'the list is read again');
});

test('cancelling the request dialog sends nothing', async () => {
  let requestCount = 0;
  const dom = ordLoad(ordState({
    orderPost: (p) => { if (p === STOP_REQ_PATH) requestCount++; return null; },
  }));
  await flush();
  await openStopDialog(dom);
  dom.byId('order-stop-reason').value = 'SCOPE_CONCERN';
  dom.byId('order-stop-reason').fire('change');
  dom.byId('order-stop-cancel').fire('click');
  await flush();
  assert.equal(dom.byId('order-stop-dialog').open, false);
  assert.equal(requestCount, 0);
});

test('cancel sends the waiting request id with an empty JSON object, and the detail is read again', async () => {
  let cancelCount = 0;
  const s = ordState({
    orderDetail: { status: 200, data: orderDetailOf({ state: 'STOP_REQUESTED', stateReason: 'STOP_REQUESTED', version: 4 }, stopRequestOf()) },
    orderPost: (p) => {
      if (p !== CANCEL_PATH) return null;
      cancelCount++;
      return { status: 200, data: orderOf({ state: 'PAUSED', stateReason: 'STOP_NOT_APPROVED', version: 5 }) };
    },
  });
  const dom = ordLoad(s);
  await flush();
  buttonIn(dom.byId('orders-list'), '詳細').fire('click');
  await flush();
  buttonIn(dom.byId('orders-list'), '要求を取り消す').fire('click');
  await flush();
  assert.equal(cancelCount, 1);
  const sent = postsTo(dom.calls, CANCEL_PATH);
  assert.deepEqual(sent[0].body, {}, 'the body is an empty JSON object');
  assert.match(dom.byId('orders-msg').textContent, /停止の要求を取り消しました/);
  assert.equal(dom.calls.filter((c) => c.path === '/api/selfdev/orders/' + O1 && c.method === 'GET').length >= 2, true, 'the detail is read again');
});

test('a refused request or cancel shows a fixed text, never the server text, the title or the policy', async () => {
  const cases = [
    { path: STOP_REQ_PATH, status: 409, text: /状態が変わっていました/ },
    { path: STOP_REQ_PATH, status: 403, text: /権限では行えません/ },
    { path: STOP_REQ_PATH, status: 503, text: /今は無効です/ },
    { path: STOP_REQ_PATH, status: 400, text: /入力を確認できませんでした/ },
  ];
  for (const c of cases) {
    const dom = ordLoad(ordState({
      orderPost: (p) => (p === c.path ? { status: c.status, data: { error: STOP_REQ_SECRET + ' ' + SECRET_TITLE, code: 'X' } } : null),
    }));
    await flush();
    await openStopDialog(dom);
    dom.byId('order-stop-reason').value = 'OPERATOR_REQUEST';
    dom.byId('order-stop-reason').fire('change');
    dom.byId('order-stop-submit').fire('click');
    await flush();
    const msg = dom.byId('orders-msg').textContent;
    assert.match(msg, c.text, `${c.status}: the fixed text`);
    for (const secret of [STOP_REQ_SECRET, SECRET_TITLE, SECRET_POLICY, 'X']) {
      assert.equal(msg.includes(secret), false, `${c.status}: not shown: ${secret}`);
    }
    assert.equal(dom.byId('order-stop-dialog').open, false, `${c.status}: the dialog closes`);
  }

  const cancelDenied = ordLoad(ordState({
    orderDetail: { status: 200, data: orderDetailOf({ state: 'STOP_REQUESTED', stateReason: 'STOP_REQUESTED', version: 4 }, stopRequestOf()) },
    orderPost: (p) => (p === CANCEL_PATH ? { status: 403, data: { error: STOP_REQ_SECRET, code: 'FORBIDDEN' } } : null),
  }));
  await flush();
  buttonIn(cancelDenied.byId('orders-list'), '詳細').fire('click');
  await flush();
  buttonIn(cancelDenied.byId('orders-list'), '要求を取り消す').fire('click');
  await flush();
  const msg = cancelDenied.byId('orders-msg').textContent;
  assert.match(msg, /権限では行えません/, 'cancel 403: the fixed text');
  assert.equal(msg.includes(STOP_REQ_SECRET), false, 'cancel 403: the server text is not shown');
  assert.equal(attrsOf(cancelDenied.byId('orders-list')).includes(STOP_REQ_SECRET), false, 'not in an attribute');
});

test('while a request is in flight a second confirm sends nothing, and the controls are disabled', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  let requestCount = 0;
  const dom = ordLoad(ordState({
    orderPost: (p) => {
      if (p !== STOP_REQ_PATH) return null;
      requestCount++;
      return gate.then(() => ({ status: 201, data: { requestId: R1, orderId: O1, state: 'PENDING' } }));
    },
  }));
  await flush();
  await openStopDialog(dom);
  dom.byId('order-stop-reason').value = 'RUNAWAY_SUSPECTED';
  dom.byId('order-stop-reason').fire('change');
  dom.byId('order-stop-submit').fire('click');
  dom.byId('order-stop-submit').fire('click');
  await flush();
  assert.equal(requestCount, 1, 'one request while in flight');
  assert.equal(dom.byId('order-stop-submit').disabled, true, 'confirm is disabled while in flight');
  assert.equal(dom.byId('order-stop-reason').disabled, true, 'the reason is disabled while in flight');
  release();
  await flush();
  assert.equal(requestCount, 1, 'still one request after the answer');
});

test('while a cancel is in flight a second click sends nothing', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  let cancelCount = 0;
  const dom = ordLoad(ordState({
    orderDetail: { status: 200, data: orderDetailOf({ state: 'STOP_REQUESTED', stateReason: 'STOP_REQUESTED', version: 4 }, stopRequestOf()) },
    orderPost: (p) => {
      if (p !== CANCEL_PATH) return null;
      cancelCount++;
      return gate.then(() => ({ status: 200, data: orderOf({ state: 'PAUSED', version: 5 }) }));
    },
  }));
  await flush();
  buttonIn(dom.byId('orders-list'), '詳細').fire('click');
  await flush();
  buttonIn(dom.byId('orders-list'), '要求を取り消す').fire('click');
  await flush();
  assert.equal(buttonIn(dom.byId('orders-list'), '要求を取り消す').disabled, true, 'the cancel button is disabled while in flight');
  buttonIn(dom.byId('orders-list'), '要求を取り消す').fire('click');
  await flush();
  assert.equal(cancelCount, 1, 'one cancel while in flight');
  release();
  await flush();
  assert.equal(cancelCount, 1, 'still one cancel after the answer');
});

// ---- The header refresh and the budget / memory auto refresh --------------

const BUDGET_PATH = '/api/admin/budget/status';
const isMem = (p) => p.startsWith('/api/selfdev/memory?');

/** Autopilot on, the memory card on, and the budget card on unless `budget` says otherwise. `hook(p)` may throw or return a promise. */
function allLoad({ budget = true, hook, hash = '' } = {}) {
  const s = apState();
  s.other = (p, init) => {
    if (hook) {
      const h = hook(p);
      if (h) return h;
    }
    if (isMem(p) && init.method === 'GET') return memList([memOf()]);
    if (p === BUDGET_PATH) return budget ? { status: 200, data: BUDGET_STATUS } : { status: 404, data: {} };
    if (p === '/api/admin/budget/fx') return budget ? { status: 200, data: BUDGET_FX_OK } : { status: 404, data: {} };
    return null;
  };
  return load(apResponder(s), { hash });
}

const count = (dom, from, pred) => dom.calls.slice(from).filter((c) => pred(c.path)).length;

test('the header refresh reads only what is shown: a hidden budget is not read, a shown memory is', async () => {
  const dom = allLoad({ budget: false, hash: '#/memory' });
  await flush();
  assert.equal(dom.byId('budget-section').hidden, true);
  assert.equal(dom.byId('memory-section').hidden, false);
  const from = dom.calls.length;
  dom.byId('refresh-all-btn').fire('click');
  await flush();
  assert.equal(count(dom, from, (p) => p === BUDGET_PATH), 0, 'hidden budget: not read');
  assert.equal(count(dom, from, isMem), 1, 'shown memory: read');
  assert.equal(count(dom, from, (p) => p === '/health'), 1);
  assert.equal(count(dom, from, (p) => p === '/api/selfdev/status'), 1);
  assert.equal(count(dom, from, (p) => p === '/api/selfdev/autopilot'), 0, 'the memory page does not read the autopilot');
  assert.equal(count(dom, from, (p) => p === '/api/selfdev/goals'), 0);

  const shown = allLoad({ hash: '#/budget' });
  await flush();
  const from2 = shown.calls.length;
  shown.byId('refresh-all-btn').fire('click');
  await flush();
  assert.equal(count(shown, from2, (p) => p === BUDGET_PATH), 1, 'shown budget: read');
});

test('a double click on the header refresh does not call twice, and the button is off while it runs', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  let slow = false;
  const dom = allLoad({ hash: '#/memory', hook: (p) => (slow && p === '/health' ? gate.then(() => ({ status: 200, data: { status: 'ok' } })) : null) });
  await flush();
  const from = dom.calls.length;
  slow = true;
  dom.byId('refresh-all-btn').fire('click');
  await flush();
  assert.equal(dom.byId('refresh-all-btn').disabled, true);
  dom.byId('refresh-all-btn').fire('click');
  await flush();
  release();
  await flush();
  assert.equal(count(dom, from, (p) => p === '/health'), 1);
  assert.equal(count(dom, from, isMem), 1);
  assert.equal(dom.byId('refresh-all-btn').disabled, false);
});

test('one failing read does not stop the others, and the button comes back', async () => {
  let down = false;
  const dom = allLoad({ hash: '#/memory', hook: (p) => { if (down && p === '/health') throw new Error('network down'); return null; } });
  await flush();
  const from = dom.calls.length;
  down = true;
  dom.byId('refresh-all-btn').fire('click');
  await flush();
  assert.equal(count(dom, from, (p) => p === '/health'), 1);
  assert.equal(count(dom, from, isMem), 1, 'memory is still read');
  assert.equal(dom.byId('refresh-all-btn').disabled, false);
  assert.match(dom.byId('refresh-all-at').textContent, /^最終更新: \d+:\d\d:\d\d/);
});

test('the last refresh time is written after a refresh and changes on the next one', async () => {
  const dom = allLoad();
  await flush();
  assert.doesNotMatch(dom.byId('refresh-all-at').textContent, /\d:\d\d:\d\d/, 'not refreshed from the header yet');
  assert.equal(dom.byId('refresh-all-at').getAttribute('role'), null);
  dom.byId('refresh-all-btn').fire('click');
  await flush();
  const first = dom.byId('refresh-all-at').textContent;
  assert.match(first, /^最終更新: \d+:\d\d:\d\d/);
  await dom.clock.advance(5000);
  dom.byId('refresh-all-btn').fire('click');
  await flush();
  assert.notEqual(dom.byId('refresh-all-at').textContent, first);
});

test('the tick refreshes the budget and memory only while shown, and skips them while a dialog or a submission is open', async () => {
  const hiddenBudget = allLoad({ budget: false });
  await flush();
  await hiddenBudget.clock.advance(31000);
  let from = hiddenBudget.calls.length;
  hiddenBudget.tickNow();
  await flush();
  assert.equal(count(hiddenBudget, from, (p) => p === BUDGET_PATH), 0, 'hidden budget: no auto read');
  assert.equal(count(hiddenBudget, from, isMem), 1, 'shown memory: auto read');

  const dom = allLoad();
  await flush();
  await dom.clock.advance(31000);
  from = dom.calls.length;
  dom.tickNow();
  await flush();
  assert.equal(count(dom, from, (p) => p === BUDGET_PATH), 1, 'shown budget: auto read');
  assert.equal(count(dom, from, isMem), 1);

  // A dialog is open: neither is rewritten.
  openFirstAck(dom);
  dom.byId('memory-dialog').open = true;
  await dom.clock.advance(31000);
  from = dom.calls.length;
  dom.tickNow();
  await flush();
  assert.equal(count(dom, from, (p) => p === BUDGET_PATH), 0, 'budget dialog open: skipped');
  assert.equal(count(dom, from, isMem), 0, 'memory dialog open: skipped');

  // A memory deletion in flight: skipped too.
  const busy = memLoad({ list: memList([memOf()]), one: () => new Promise(() => {}) });
  await flush();
  busy.byId('memory-list').buttons()[0].fire('click');
  busy.byId('memory-reason').value = '誤って書いた';
  busy.byId('memory-reason').fire('input');
  busy.byId('memory-submit').fire('click');
  await flush();
  busy.byId('memory-dialog').open = false;
  await busy.clock.advance(31000);
  from = busy.calls.length;
  busy.tickNow();
  await flush();
  assert.equal(count(busy, from, isMem), 0, 'submission in flight: skipped');
});

// ---- The side navigation ----------------------------------------------------

const PAGE_IDS = ['tasks', 'goals', 'orders', 'approvals', 'budget', 'memory'];
const shownPages = (dom) => PAGE_IDS.filter((id) => dom.byId('page-' + id).hidden === false);

/** Everything on: autopilot, orders, an approver with one pending request, budget and memory. */
function navLoad({ hash = '', budget = true, approver = true, requests = [stopRequestOf()], autopilot = 200 } = {}) {
  const s = ordState({
    autopilot,
    requestList: approver ? { status: 200, data: requests } : { status: 403, data: { error: 'forbidden' } },
  });
  const orders = ordersOther(s);
  s.other = (p, init) => {
    const o = orders(p, init);
    if (o) return o;
    if (isMem(p) && init.method === 'GET') return memList([memOf()]);
    if (p === BUDGET_PATH) return budget ? { status: 200, data: BUDGET_STATUS } : { status: 404, data: {} };
    if (p === '/api/admin/budget/fx') return budget ? { status: 200, data: BUDGET_FX_OK } : { status: 404, data: {} };
    return null;
  };
  return load(apResponder(s), { hash });
}

test('each hash shows only its own page, and an unknown hash goes to #/', async () => {
  const want = { '': 'tasks', '#/': 'tasks', '#/goals': 'goals', '#/orders': 'orders', '#/approvals': 'approvals', '#/budget': 'budget', '#/memory': 'memory' };
  for (const [hash, page] of Object.entries(want)) {
    const dom = navLoad({ hash });
    await flush();
    assert.deepEqual(shownPages(dom), [page], 'hash "' + hash + '"');
    assert.equal(dom.byId('view-detail').hidden, true);
    assert.equal(dom.byId('nav-' + page).getAttribute('aria-current'), 'page', 'aria-current on ' + page);
    for (const other of PAGE_IDS.filter((id) => id !== page)) {
      assert.equal(dom.byId('nav-' + other).getAttribute('aria-current'), null, other + ' is not current');
    }
  }
  const dom = navLoad({ hash: '#/nope' });
  await flush();
  assert.deepEqual(shownPages(dom), ['tasks']);
  assert.equal(dom.window.location.hash, '#/', 'the hash is rewritten');
  dom.window.location.hash = '#/orders';
  dom.hashListeners.forEach((fn) => fn());
  assert.deepEqual(shownPages(dom), ['orders'], 'a hash change switches the page');
  dom.window.location.hash = '#/zzz';
  dom.hashListeners.forEach((fn) => fn());
  assert.deepEqual(shownPages(dom), ['tasks']);
});

test('a run detail keeps working and selects the tasks item in the navigation', async () => {
  const dom = navLoad({ hash: '#/runs/' + SELF_RUN_ID });
  await flush();
  assert.equal(dom.byId('view-list').hidden, true);
  assert.equal(dom.byId('view-detail').hidden, false);
  assert.equal(dom.byId('nav-tasks').getAttribute('aria-current'), 'page');
  assert.equal(dom.byId('nav-goals').getAttribute('aria-current'), null);
  assert.equal(dom.document.title, 'Kivra \u2014 Run \u306e\u8a73\u7d30');
  dom.window.location.hash = '#/goals';
  dom.hashListeners.forEach((fn) => fn());
  assert.equal(dom.byId('view-list').hidden, false);
  assert.equal(dom.byId('view-detail').hidden, true);
  assert.deepEqual(shownPages(dom), ['goals']);
});

test('a nav item is shown only while its section is not hidden by the API, and a hidden current page returns to #/', async () => {
  const on = navLoad();
  await flush();
  for (const id of PAGE_IDS) assert.equal(on.byId('nav-' + id).hidden, false, id + ' is shown');

  const noBudget = navLoad({ budget: false });
  await flush();
  assert.equal(noBudget.byId('budget-section').hidden, true);
  assert.equal(noBudget.byId('nav-budget').hidden, true);
  assert.equal(noBudget.byId('nav-memory').hidden, false);

  const noApprover = navLoad({ approver: false });
  await flush();
  assert.equal(noApprover.byId('nav-approvals').hidden, true);
  assert.equal(noApprover.byId('nav-orders').hidden, false);

  const off = navLoad({ autopilot: 503 });
  await flush();
  for (const id of ['goals', 'orders', 'approvals', 'memory']) assert.equal(off.byId('nav-' + id).hidden, true, id + ' is hidden');
  assert.equal(off.byId('nav-tasks').hidden, false);

  // A deep link to a page that is not available goes back to #/ once the answer is known.
  const deep = navLoad({ hash: '#/budget', budget: false });
  await flush();
  assert.deepEqual(shownPages(deep), ['tasks']);
  assert.equal(deep.window.location.hash, '#/');
  assert.equal(deep.byId('nav-tasks').getAttribute('aria-current'), 'page');

  // A deep link to an available page stays (it is not sent back before the answer arrives).
  const ok = navLoad({ hash: '#/memory' });
  await flush();
  assert.deepEqual(shownPages(ok), ['memory']);

  // The current page's section becomes unavailable later.
  let budgetOn = true;
  const s = apState();
  s.other = (p) => {
    if (p === BUDGET_PATH) return budgetOn ? { status: 200, data: BUDGET_STATUS } : { status: 404, data: {} };
    if (p === '/api/admin/budget/fx') return budgetOn ? { status: 200, data: BUDGET_FX_OK } : { status: 404, data: {} };
    return null;
  };
  const later = load(apResponder(s), { hash: '#/budget' });
  await flush();
  assert.deepEqual(shownPages(later), ['budget']);
  budgetOn = false;
  later.byId('refresh-all-btn').fire('click');
  await flush();
  assert.deepEqual(shownPages(later), ['tasks']);
});

test('the pending count is a badge on the approvals item and a link in the header, hidden at 0 and before it is read', async () => {
  const dom = navLoad({ requests: [stopRequestOf(), stopRequestOf({ requestId: '01RQSTAAAAAAAAAAAAAAAAAAAB' })] });
  await flush();
  assert.equal(dom.byId('nav-approvals-badge').hidden, false);
  assert.equal(dom.byId('nav-approvals-badge').textContent, '2');
  assert.equal(dom.byId('nav-approvals-badge').getAttribute('aria-label'), '保留中の要求 2 件');
  assert.equal(dom.byId('header-approvals').hidden, false);
  assert.match(dom.byId('header-approvals').textContent, /2/);
  assert.equal(dom.byId('header-approvals').getAttribute('aria-label'), '保留中の要求 2 件');

  const zero = navLoad({ requests: [] });
  await flush();
  assert.equal(zero.byId('header-approvals').hidden, true, '0 pending: no header link');
  assert.equal(zero.byId('nav-approvals-badge').textContent, '0');

  const nonApprover = navLoad({ approver: false });
  await flush();
  assert.equal(nonApprover.byId('header-approvals').hidden, true);
  assert.equal(nonApprover.byId('nav-approvals-badge').hidden, true);

  const gate = new Promise(() => {});
  const s = ordState();
  s.other = (p, init) => (p === '/api/selfdev/stop-requests?state=PENDING' ? gate : ordersOther(s)(p, init));
  const before = load(apResponder(s));
  await flush();
  assert.equal(before.byId('header-approvals').hidden, true, 'not read yet: hidden');
  assert.equal(before.byId('nav-approvals-badge').hidden, true);
});

test('the five per-section refresh buttons are gone from the page and the script', () => {
  const html = fs.readFileSync(path.join(path.dirname(APP_JS), 'index.html'), 'utf8');
  const js = fs.readFileSync(APP_JS, 'utf8');
  for (const id of ['refresh-btn', 'plans-refresh', 'orders-refresh', 'budget-refresh', 'memory-refresh']) {
    assert.ok(!html.includes('id="' + id + '"'), id + ' is not in index.html');
    assert.ok(!js.includes("'" + id + "'"), id + ' is not in app.js');
  }
  assert.ok(html.includes('id="refresh-all-btn"'));
  assert.ok(html.includes('<nav class="side-nav" id="side-nav" aria-label="主要なページ">'));
  assert.ok(html.includes('class="skip-link" id="skip-link" href="#main"'));
});

test('the header refresh reads the data of the page shown now', async () => {
  const reads = (dom, from) => ({
    goals: count(dom, from, (p) => p === '/api/selfdev/goals'),
    autopilot: count(dom, from, (p) => p === '/api/selfdev/autopilot'),
    orders: count(dom, from, (p) => p === '/api/selfdev/orders'),
    requests: count(dom, from, (p) => p === '/api/selfdev/stop-requests?state=PENDING'),
    budget: count(dom, from, (p) => p === BUDGET_PATH),
    mem: count(dom, from, isMem),
    runs: count(dom, from, (p) => p === '/api/runs'),
    health: count(dom, from, (p) => p === '/health'),
    status: count(dom, from, (p) => p === '/api/selfdev/status'),
  });
  const cases = {
    '#/': { goals: 0, autopilot: 0, orders: 0, requests: 0, budget: 0, mem: 0 },
    '#/goals': { goals: 1, autopilot: 1, orders: 0, requests: 0, budget: 0, mem: 0 },
    '#/orders': { goals: 0, autopilot: 0, orders: 1, requests: 1, budget: 0, mem: 0 },
    '#/approvals': { goals: 0, autopilot: 0, orders: 1, requests: 1, budget: 0, mem: 0 },
    '#/budget': { goals: 0, autopilot: 0, orders: 0, requests: 0, budget: 1, mem: 0 },
    '#/memory': { goals: 0, autopilot: 0, orders: 0, requests: 0, budget: 0, mem: 1 },
  };
  for (const [hash, want] of Object.entries(cases)) {
    const dom = navLoad({ hash });
    await flush();
    const from = dom.calls.length;
    dom.byId('refresh-all-btn').fire('click');
    await flush();
    const got = reads(dom, from);
    assert.equal(got.runs, 1, hash + ': runs');
    assert.equal(got.health, 1, hash + ': health');
    assert.equal(got.status, 1, hash + ': status');
    for (const k of Object.keys(want)) assert.equal(got[k], want[k], hash + ': ' + k);
  }
});

test('the tick still refreshes what the API shows, whatever page is open (for the badge)', async () => {
  const dom = navLoad({ hash: '#/' });
  await flush();
  await dom.clock.advance(31000);
  const from = dom.calls.length;
  dom.tickNow();
  await flush();
  assert.equal(count(dom, from, (p) => p === '/api/selfdev/stop-requests?state=PENDING'), 1);
  assert.equal(count(dom, from, (p) => p === '/api/selfdev/goals'), 1);
  assert.equal(count(dom, from, isMem), 1);
  assert.equal(count(dom, from, (p) => p === BUDGET_PATH), 1);
});

test('document.title follows the page', async () => {
  const dom = navLoad();
  await flush();
  assert.equal(dom.document.title, 'Kivra \u2014 \u30bf\u30b9\u30af');
  for (const [hash, name] of [['#/goals', '開発目標'], ['#/orders', 'order'], ['#/approvals', '承認'], ['#/budget', '予算'], ['#/memory', '記憶']]) {
    dom.window.location.hash = hash;
    dom.hashListeners.forEach((fn) => fn());
    assert.equal(dom.document.title, 'Kivra \u2014 ' + name);
  }
});

test('the skip link focuses the body without changing the page', async () => {
  const dom = navLoad({ hash: '#/goals' });
  await flush();
  let prevented = false;
  dom.byId('skip-link').fire('click', { preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(dom.byId('main').focused, true);
  assert.equal(dom.window.location.hash, '#/goals');
});

// ---- Roles and the assignee page (ADR 0020 T4) --------------------------------------------------------------------------

const ALL_PERMISSIONS = ['READ', 'READ_TEXT', 'GOAL_STOP', 'ORDER_MANAGE', 'STOP_REQUEST', 'STOP_DECIDE', 'GOAL_WRITE', 'SPEND_START', 'BUDGET_OVERRIDE', 'ASSIGNEE_MANAGE'];
const OPERATOR_PERMISSIONS = ['READ', 'READ_TEXT', 'GOAL_STOP', 'ORDER_MANAGE', 'STOP_REQUEST', 'GOAL_WRITE', 'SPEND_START', 'BUDGET_OVERRIDE'];
const ME_PATH = '/api/selfdev/me';

const meOf = (mode, role, permissions) => ({ status: 200, data: { mode, role, permissions, source: 'ASSIGNEE' } });

const AS_AI = '01ASSIGNEE' + 'A'.repeat(16);
const AS_ADMIN = '01ASSIGNEE' + 'B'.repeat(16);
const AS_VIEWER = '01ASSIGNEE' + 'C'.repeat(16);
const AS_OPERATOR = '01ASSIGNEE' + 'D'.repeat(16);
const MEMBER_REF = 'abcdef0123456789';
const TOKEN_REF = '0123456789abcdef';
const TOKEN_PLAIN = 'kvt_' + 'Qz3-'.repeat(10) + 'Qz3';
const SUB_VALUE = 'f1e2d3c4-sub-value-1234';

function assigneeOf(over = {}) {
  return { assigneeId: AS_AI, displayName: 'claude-code', role: 'AI', state: 'ACTIVE', version: 4, memberCount: 0, activeTokenCount: 1, ...over };
}

/**
 * Everything the roles UI touches. `s.me` answers /me (default: 404), `s.assignees` is the list, `s.members` and `s.tokens` are
 * functions of the assignee id, and `s.write(p, init)` answers a write (return null to fall through to a 500).
 */
function asgLoad(s = {}, { hash = '' } = {}) {
  const st = apState({
    orderList: { status: 200, data: [orderOf()] },
    orderDetail: { status: 200, data: orderDetailOf({}, s.pending || null) },
    requestList: { status: 200, data: [stopRequestOf()] },
    goals: [goalSummary({ state: 'PLAN_PROPOSED', version: 2 })],
    detail: detailOf(goalSummary({ state: 'PLAN_PROPOSED', version: 2 }), [taskOf({ version: 0 })]),
  });
  const orders = ordersOther(st);
  st.other = (p, init) => {
    if (p === ME_PATH) return s.me || { status: 404, data: { error: 'not found' } };
    if (p === '/api/selfdev/assignees' && init.method === 'GET') return s.listResponse || { status: 200, data: s.assignees || [] };
    const sub = /^\/api\/selfdev\/assignees\/([0-9A-Z]{26})\/(members|tokens)$/.exec(p);
    if (sub && init.method === 'GET') {
      const fn = sub[2] === 'members' ? s.members : s.tokens;
      return { status: 200, data: fn ? fn(sub[1]) : [] };
    }
    if (s.write && init.method !== 'GET') {
      const w = s.write(p, init);
      if (w) return w;
    }
    const o = orders(p, init);
    if (o) return o;
    if (p.startsWith('/api/selfdev/memory?') && init.method === 'GET') return memList([memOf()]);
    if (p === BUDGET_PATH) return { status: 200, data: BUDGET_STATUS };
    if (p === '/api/admin/budget/fx') return { status: 200, data: BUDGET_FX_OK };
    return null;
  };
  const dom = load(apResponder(st), { hash });
  dom.state = st;
  return dom;
}

const adminMe = () => meOf('enforce', 'ADMIN', ALL_PERMISSIONS);

function inputsIn(el) {
  const out = [];
  el.walk((n) => { if (n.tagName === 'INPUT' || n.tagName === 'SELECT') out.push(n); });
  return out;
}

function asgOpen(dom) {
  buttonIn(dom.byId('assignees-list'), '詳細').fire('click');
}

async function confirmDialog(dom) {
  assert.equal(dom.byId('assignee-confirm-dialog').open, true, 'a confirmation dialog is open');
  dom.byId('assignee-confirm-submit').fire('click');
  await flush();
}

test('/me is read once at the start; the role is shown as text, and a 404 leaves the page as it was', async () => {
  const dom = asgLoad({ me: meOf('enforce', 'OPERATOR', OPERATOR_PERMISSIONS) });
  await flush();
  assert.equal(dom.calls.filter((c) => c.path === ME_PATH).length, 1);
  assert.equal(dom.byId('me-role').hidden, false);
  assert.equal(dom.byId('me-role').textContent, '役割: OPERATOR');
  assert.equal(dom.byId('me-note').hidden, true, 'enforce: no note');
  dom.byId('refresh-all-btn').fire('click');
  await flush();
  assert.equal(dom.calls.filter((c) => c.path === ME_PATH).length, 1, 'not read again');

  const none = asgLoad({});
  await flush();
  assert.equal(none.byId('me-role').hidden, true, '404: no role');
  assert.equal(none.byId('nav-assignees').hidden, true);
  assert.equal(none.byId('goal-form').hidden, false, '404: nothing is hidden');
  assert.equal(none.byId('order-create').hidden, false);

  const broken = asgLoad({ me: { status: 200, data: { mode: 'weird', role: 'ADMIN', permissions: ['READ'] } } });
  await flush();
  assert.equal(broken.byId('me-role').hidden, true, 'a malformed answer is the fallback');
  assert.equal(broken.byId('goal-form').hidden, false);

  const shadow = asgLoad({ me: meOf('shadow', 'VIEWER', ['READ']) });
  await flush();
  assert.equal(shadow.byId('me-role').textContent, '役割: VIEWER');
  assert.match(shadow.byId('me-note').textContent, /判定は従来どおり（shadow）/);
  assert.equal(shadow.byId('me-note').hidden, false);

  const evil = asgLoad({ me: { status: 200, data: { mode: 'enforce', role: EVIL, permissions: ['READ'] } } });
  await flush();
  assert.equal(evil.byId('me-role').hidden, true, 'a role that is not a code is not shown');
});

test('the assignee page and its nav item appear only with ASSIGNEE_MANAGE and a mode that is not off', async () => {
  const admin = asgLoad({ me: adminMe(), assignees: [assigneeOf()] }, { hash: '#/assignees' });
  await flush();
  assert.equal(admin.byId('nav-assignees').hidden, false);
  assert.equal(admin.byId('assignees-section').hidden, false);
  assert.equal(admin.byId('page-assignees').hidden, false);
  assert.equal(admin.byId('nav-assignees').getAttribute('aria-current'), 'page');
  assert.match(admin.byId('assignees-list').textContent, /claude-code/);
  assert.equal(admin.document.title, 'Kivra — 担当');

  const shadow = asgLoad({ me: meOf('shadow', 'ADMIN', ALL_PERMISSIONS), assignees: [assigneeOf()] });
  await flush();
  assert.equal(shadow.byId('nav-assignees').hidden, false, 'shadow: the admin can manage');

  for (const [label, me] of [
    ['operator', meOf('enforce', 'OPERATOR', OPERATOR_PERMISSIONS)],
    ['off with the permission', meOf('off', 'ADMIN', ALL_PERMISSIONS)],
    ['no /me', null],
  ]) {
    const dom = asgLoad({ me, assignees: [assigneeOf()] }, { hash: '#/assignees' });
    await flush();
    assert.equal(dom.byId('nav-assignees').hidden, true, label + ': no nav item');
    assert.equal(dom.byId('assignees-section').hidden, true, label + ': no section');
    assert.equal(dom.calls.filter((c) => c.path === '/api/selfdev/assignees').length, 0, label + ': the list is not read');
    assert.deepEqual(shownPages(dom), ['tasks'], label + ': a deep link goes back to #/');
    assert.equal(dom.window.location.hash, '#/');
  }
});

test('the list shows the name as text, the role, the state and the counts; a display name with markup makes no element', async () => {
  const dom = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf({ displayName: '<script>alert(1)</script>', memberCount: 2, activeTokenCount: 1 }), assigneeOf({ assigneeId: AS_ADMIN, role: 'ADMIN', state: 'DISABLED', displayName: 'root' })],
  });
  await flush();
  const list = dom.byId('assignees-list');
  list.walk((n) => assert.ok(!['SCRIPT', 'IMG'].includes(n.tagName), 'no element from a display name'));
  assert.ok(list.textContent.includes('<script>alert(1)</script>'), 'the name is literal text');
  assert.match(list.textContent, /ロール: AI/);
  assert.match(list.textContent, /メンバー: 2 人/);
  assert.match(list.textContent, /有効なトークン: 1 個/);
  assert.match(list.textContent, /状態: 無効/);
});

test('a create validates the name and the role, sends once, and clears the form', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  const dom = asgLoad({
    me: adminMe(),
    write: (p) => (p === '/api/selfdev/assignees' ? gate.then(() => ({ status: 201, data: assigneeOf() })) : null),
  });
  await flush();
  const form = dom.byId('assignee-create-form');
  dom.byId('assignee-name').value = '   ';
  dom.byId('assignee-role').value = 'AI';
  form.fire('submit');
  assert.match(dom.byId('assignees-msg').textContent, /表示名は 1〜64 文字/);
  dom.byId('assignee-name').value = 'x'.repeat(65);
  form.fire('submit');
  assert.match(dom.byId('assignees-msg').textContent, /表示名は 1〜64 文字/);
  dom.byId('assignee-name').value = 'a\u0007b';
  form.fire('submit');
  dom.byId('assignee-name').value = 'bot';
  dom.byId('assignee-role').value = '';
  form.fire('submit');
  assert.match(dom.byId('assignees-msg').textContent, /ロールを選んでください/);
  await flush();
  assert.equal(postsTo(dom.calls, '/api/selfdev/assignees').length, 0, 'nothing is sent while the input is invalid');

  dom.byId('assignee-role').value = 'AI';
  form.fire('submit');
  form.fire('submit');
  await flush();
  assert.equal(dom.byId('assignee-create-submit').disabled, true, 'disabled while in flight');
  assert.equal(postsTo(dom.calls, '/api/selfdev/assignees').length, 1, 'one request for a double submit');
  assert.deepEqual(postsTo(dom.calls, '/api/selfdev/assignees')[0].body, { displayName: 'bot', role: 'AI' });
  release();
  await flush();
  assert.equal(dom.byId('assignee-create-submit').disabled, false);
  assert.equal(dom.byId('assignee-name').value, '');
  assert.equal(dom.byId('assignees-msg').textContent, '担当を作りました。');
});

test('a role change asks first, sends the version, and a stale version says to reload', async () => {
  let reply = { status: 409, data: { error: 'LEAK-server-text', code: 'STALE_VERSION' } };
  const dom = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf({ version: 7 })],
    write: (p) => (p === '/api/selfdev/assignees/' + AS_AI + '/role' ? reply : null),
  });
  await flush();
  asgOpen(dom);
  await flush();
  const box = dom.byId('assignees-list');
  const sel = inputsIn(box).find((n) => n.tagName === 'SELECT');
  sel.value = 'VIEWER';
  buttonIn(box, 'ロールを変更…').fire('click');
  assert.equal(dom.byId('assignee-confirm-dialog').open, true);
  assert.equal(postsTo(dom.calls, '/api/selfdev/assignees/' + AS_AI + '/role').length, 0, 'nothing is sent before the confirmation');
  await confirmDialog(dom);
  const posts = postsTo(dom.calls, '/api/selfdev/assignees/' + AS_AI + '/role');
  assert.deepEqual(posts[0].body, { role: 'VIEWER', version: 7 });
  assert.equal(dom.byId('assignees-msg').textContent, '更新されています。再読み込みしてください。');
  assert.doesNotMatch(dom.byId('assignees-msg').textContent, /LEAK/);
  assert.equal(dom.byId('assignee-confirm-dialog').open, false);

  // The same role: no request at all.
  const before = dom.calls.length;
  buttonIn(dom.byId('assignees-list'), 'ロールを変更…').fire('click');
  assert.equal(dom.byId('assignee-confirm-dialog').open, false);
  assert.equal(dom.calls.length, before);

  reply = { status: 200, data: assigneeOf({ role: 'VIEWER', version: 8 }) };
  inputsIn(dom.byId('assignees-list')).find((n) => n.tagName === 'SELECT').value = 'VIEWER';
  buttonIn(dom.byId('assignees-list'), 'ロールを変更…').fire('click');
  await confirmDialog(dom);
  assert.equal(dom.byId('assignees-msg').textContent, 'ロールを変更しました。');
});

test('the confirmation dialog is sent once while in flight, and cancel sends nothing', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  const dom = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf()],
    write: (p) => (p === '/api/selfdev/assignees/' + AS_AI + '/disable' ? gate.then(() => ({ status: 200, data: assigneeOf({ state: 'DISABLED', version: 5 }) })) : null),
  });
  await flush();
  asgOpen(dom);
  await flush();
  buttonIn(dom.byId('assignees-list'), '担当を無効にする…').fire('click');
  dom.byId('assignee-confirm-cancel').fire('click');
  await flush();
  assert.equal(postsTo(dom.calls, '/api/selfdev/assignees/' + AS_AI + '/disable').length, 0, 'cancel sends nothing');
  assert.equal(dom.byId('assignee-confirm-dialog').open, false);

  buttonIn(dom.byId('assignees-list'), '担当を無効にする…').fire('click');
  assert.match(dom.byId('assignee-confirm-text').textContent, /トークンは、すべて失効します/);
  dom.byId('assignee-confirm-submit').fire('click');
  dom.byId('assignee-confirm-submit').fire('click');
  await flush();
  assert.equal(dom.byId('assignee-confirm-submit').disabled, true);
  assert.equal(dom.byId('assignee-confirm-cancel').disabled, true);
  release();
  await flush();
  const posts = postsTo(dom.calls, '/api/selfdev/assignees/' + AS_AI + '/disable');
  assert.equal(posts.length, 1, 'one request');
  assert.deepEqual(posts[0].body, { version: 4 });
  assert.equal(dom.byId('assignees-msg').textContent, '担当を無効にしました（トークンはすべて失効しました）。');
});

test('enable sends the version; the refusal codes each have their own fixed text and the server text is never shown', async () => {
  const codes = {
    LAST_ADMIN: /最後の有効な管理者/,
    SELF_CHANGE: /自分自身の担当/,
    STALE_VERSION: /更新されています。再読み込みしてください。/,
    ROLE_NOT_ALLOWED: /トークンを発行できません/,
    TOO_MANY_ACTIVE: /上限（2 つ）/,
    OWNER_KEY_ALREADY_BOUND: /すでに担当に紐付いています/,
    BINDING_UNAVAILABLE: /OIDC の設定が必要/,
    ROLES_DISABLED: /今は無効/,
    RATE_LIMITED: /操作が多すぎます/,
    FORBIDDEN: /権限では行えません/,
    NO_CHANGE: /変更がありません/,
    ALREADY_REVOKED: /すでに失効/,
  };
  for (const [code, re] of Object.entries(codes)) {
    const dom = asgLoad({
      me: adminMe(),
      assignees: [assigneeOf({ state: 'DISABLED', version: 9 })],
      write: (p) => (p === '/api/selfdev/assignees/' + AS_AI + '/enable' ? { status: 409, data: { error: 'LEAK-' + code, code } } : null),
    });
    await flush();
    asgOpen(dom);
    await flush();
    buttonIn(dom.byId('assignees-list'), '担当を有効にする…').fire('click');
    await confirmDialog(dom);
    assert.deepEqual(postsTo(dom.calls, '/api/selfdev/assignees/' + AS_AI + '/enable')[0].body, { version: 9 });
    const text = dom.byId('assignees-msg').textContent;
    assert.match(text, re, code);
    assert.doesNotMatch(text, /LEAK/, code);
    assert.equal(dom.byId('assignees-msg').className, 'result error');
  }
  // A code the page does not know falls back to the status, with no server text.
  const unknown = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf({ state: 'DISABLED' })],
    write: () => ({ status: 409, data: { error: 'LEAK-x', code: 'NEW_CODE' } }),
  });
  await flush();
  asgOpen(unknown);
  await flush();
  buttonIn(unknown.byId('assignees-list'), '担当を有効にする…').fire('click');
  await confirmDialog(unknown);
  assert.equal(unknown.byId('assignees-msg').textContent, '更新されています。再読み込みしてください。');
});

test('binding a member sends the sub and never shows it again; the list shows 8 characters of the member reference; unbind sends DELETE', async () => {
  let bound = false;
  const dom = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf({ assigneeId: AS_OPERATOR, role: 'OPERATOR', displayName: 'op' })],
    members: () => (bound ? [{ memberRef: MEMBER_REF }] : []),
    write: (p, init) => {
      if (p === '/api/selfdev/assignees/' + AS_OPERATOR + '/members' && init.method === 'POST') {
        bound = true;
        return { status: 201, data: { memberRef: MEMBER_REF } };
      }
      if (p === '/api/selfdev/assignees/' + AS_OPERATOR + '/members/' + MEMBER_REF && init.method === 'DELETE') {
        bound = false;
        return { status: 204, data: undefined };
      }
      return null;
    },
  });
  await flush();
  asgOpen(dom);
  await flush();
  const subInput = inputsIn(dom.byId('assignees-list')).find((n) => n.tagName === 'INPUT');
  subInput.value = 'bad sub with spaces';
  buttonIn(dom.byId('assignees-list'), '紐付ける…').fire('click');
  assert.match(dom.byId('assignees-msg').textContent, /Keycloak の sub を/);
  assert.equal(dom.byId('assignee-confirm-dialog').open, false, 'an invalid sub opens no dialog');

  subInput.value = SUB_VALUE;
  buttonIn(dom.byId('assignees-list'), '紐付ける…').fire('click');
  assert.ok(!dom.byId('assignee-confirm-text').textContent.includes(SUB_VALUE), 'the sub is not in the dialog');
  await confirmDialog(dom);
  const bind = postsTo(dom.calls, '/api/selfdev/assignees/' + AS_OPERATOR + '/members');
  assert.deepEqual(bind[0].body, { sub: SUB_VALUE });
  assert.equal(dom.byId('assignees-msg').textContent, 'メンバーを紐付けました。');
  assert.equal(subInput.value, '', 'the field is cleared');
  let shown = '';
  for (const id of ['assignees-list', 'assignees-msg', 'assignees-status', 'assignee-confirm-text', 'assignee-confirm-title', 'me-role', 'me-note', 'mode-note']) shown += dom.byId(id).textContent + '\n';
  assert.ok(!shown.includes(SUB_VALUE), 'the sub is nowhere on the page');
  assert.ok(!shown.includes(MEMBER_REF), 'the whole member reference is not shown');
  assert.ok(shown.includes(MEMBER_REF.slice(0, 8)), 'the first 8 characters are');
  assert.ok(!attrsOf(dom.byId('assignees-list')).includes(MEMBER_REF), 'nor in an attribute');

  buttonIn(dom.byId('assignees-list'), '解除…').fire('click');
  await confirmDialog(dom);
  const del = dom.calls.filter((c) => c.method === 'DELETE');
  assert.equal(del.length, 1);
  assert.equal(del[0].path, '/api/selfdev/assignees/' + AS_OPERATOR + '/members/' + MEMBER_REF);
  assert.deepEqual(del[0].body, {}, 'a JSON body, because the server requires the JSON content type');
  assert.equal(dom.byId('assignees-msg').textContent, 'メンバーの紐付けを解除しました。', 'a 204 is a success, not an expired session');
  assert.ok(!dom.byId('assignees-list').textContent.includes(MEMBER_REF.slice(0, 8)), 'the member is gone from the list');
});

test('a token is issued for an AI and a VIEWER only, never for a human role or a disabled assignee', async () => {
  for (const [role, state, expected] of [['AI', 'ACTIVE', true], ['VIEWER', 'ACTIVE', true], ['ADMIN', 'ACTIVE', false], ['OPERATOR', 'ACTIVE', false], ['APPROVER', 'ACTIVE', false], ['AI', 'DISABLED', false]]) {
    const dom = asgLoad({ me: adminMe(), assignees: [assigneeOf({ role, state })] });
    await flush();
    asgOpen(dom);
    await flush();
    assert.equal(hasButton(dom.byId('assignees-list'), 'トークンを発行…'), expected, role + ' ' + state);
  }
});

test('an AI_APPROVER assignee is listed by its role, offers no token and no person binding, and the role is a choice only in the create form', async () => {
  const dom = asgLoad({ me: adminMe(), assignees: [assigneeOf({ role: 'AI_APPROVER', activeTokenCount: 0 })] });
  await flush();
  assert.ok(dom.byId('assignee-role').children.some((o) => o.value === 'AI_APPROVER'), 'AI_APPROVER is a role choice');
  assert.match(dom.byId('assignees-list').textContent, /ロール: AI_APPROVER/);
  asgOpen(dom);
  await flush();
  const list = dom.byId('assignees-list');
  assert.equal(hasButton(list, 'トークンを発行…'), false, 'no token button');
  assert.equal(hasButton(list, '紐付ける…'), false, 'no bind button');
  assert.ok(!list.textContent.includes('Keycloak の sub（'), 'no sub input');
  assert.match(list.textContent, /人（Keycloak の sub）を紐付けられません/);
  assert.match(list.textContent, /トークンを持てません/);
  assert.equal(hasButton(list, 'ロールを変更…'), true, 'an admin can still change the role');
});

test('GET /me with the AI_APPROVER role does not break the page', async () => {
  const dom = asgLoad({ me: meOf('enforce', 'AI_APPROVER', ['READ', 'STOP_DECIDE_AUTO']) });
  await flush();
  assert.equal(dom.byId('me-role').textContent, '役割: AI_APPROVER');
});

test('an issued token is shown once in its own dialog, copied on request, and gone when the dialog closes', async () => {
  const writes = [];
  const dom = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf()],
    tokens: () => [
      { tokenRef: TOKEN_REF, status: 'ACTIVE', createdBy: 'CREATOR-REF-SECRET', createdAt: '2026-10-25T00:00:00Z', expiresAt: '2027-01-23T00:00:00Z' },
      { tokenRef: 'ffffffffffffffff', status: 'REVOKED', createdBy: 'x', createdAt: '2026-10-01T00:00:00Z', expiresAt: '2027-01-01T00:00:00Z' },
    ],
    write: (p, init) => {
      writes.push(p);
      if (p === '/api/selfdev/assignees/' + AS_AI + '/tokens' && init.method === 'POST') {
        return { status: 201, data: { token: TOKEN_PLAIN, tokenRef: TOKEN_REF, expiresAt: '2027-01-23T00:00:00Z' } };
      }
      return null;
    },
  });
  const stored = [];
  const store = { setItem: (...a) => stored.push(a), getItem: () => null, removeItem() {} };
  dom.window.localStorage = store;
  dom.window.sessionStorage = store;
  const replaced = [];
  dom.window.history = { replaceState: (...a) => replaced.push(a), pushState: (...a) => replaced.push(a) };
  const logged = [];
  const originals = {};
  for (const k of ['log', 'info', 'warn', 'error', 'debug']) {
    originals[k] = console[k];
    console[k] = (...a) => logged.push(a.join(' '));
  }
  try {
    await flush();
    asgOpen(dom);
    await flush();
    const list = dom.byId('assignees-list');
    assert.match(list.textContent, new RegExp('参照: ' + TOKEN_REF.slice(0, 8)));
    assert.match(list.textContent, /失効済み/);
    assert.ok(!list.textContent.includes('CREATOR-REF-SECRET'), 'the creator reference is not shown');
    assert.ok(!list.textContent.includes(TOKEN_REF), 'only 8 characters of the token reference');
    assert.equal(list.buttons().filter((b) => b.textContent === '失効…').length, 1, 'only the active token has a revoke button');

    inputsIn(list).find((n) => n.tagName === 'INPUT' && n.type === 'number').value = '30';
    buttonIn(list, 'トークンを発行…').fire('click');
    assert.equal(dom.byId('token-dialog').open, false, 'asks first');
    assert.match(dom.byId('assignee-confirm-text').textContent, /30 日/);
    await confirmDialog(dom);
    const issue = postsTo(dom.calls, '/api/selfdev/assignees/' + AS_AI + '/tokens');
    assert.equal(issue.length, 1);
    assert.deepEqual(issue[0].body, { ttlDays: 30 });

    assert.equal(dom.byId('token-dialog').open, true);
    assert.equal(dom.byId('token-value').value, TOKEN_PLAIN, 'the plaintext is in the dialog field');
    assert.equal(dom.byId('assignee-confirm-dialog').open, false);
    assert.equal(dom.byId('assignees-msg').textContent, 'トークンを発行しました（平文は専用のダイアログに 1 度だけ表示しています）。');

    // The plaintext is in no other element, attribute or request.
    for (const [id, el] of Object.entries(dom.ids)) {
      if (id === 'token-value') continue;
      assert.ok(!el.textContent.includes(TOKEN_PLAIN), 'not in #' + id);
      assert.ok(!String(el.value).includes(TOKEN_PLAIN), 'not in the value of #' + id);
      assert.ok(!attrsOf(el).includes(TOKEN_PLAIN), 'not in the attributes of #' + id);
    }
    assert.equal(dom.window.location.hash.includes(TOKEN_PLAIN), false);
    assert.equal(stored.length, 0, 'nothing in localStorage or sessionStorage');
    assert.equal(replaced.filter((a) => JSON.stringify(a).includes(TOKEN_PLAIN)).length, 0, 'nothing in the history');
    assert.equal(logged.filter((l) => l.includes(TOKEN_PLAIN)).length, 0, 'nothing in the console');
    assert.equal(dom.calls.filter((c) => JSON.stringify(c.body || {}).includes(TOKEN_PLAIN)).length, 0);
    assert.equal(dom.byId('token-value').getAttribute('type'), null, 'the field is a plain readonly input (fixed in the page)');

    // The list is read again after the issue, and still holds no plaintext (a reload of the data must not bring it back).
    await flush();
    assert.ok(!dom.byId('assignees-list').textContent.includes(TOKEN_PLAIN));

    // Copy: with a clipboard, and with a clipboard that fails, and with none (no exception either way).
    const copied = [];
    dom.window.navigator = { clipboard: { writeText: async (t) => { copied.push(t); } } };
    dom.byId('token-copy').fire('click');
    await flush();
    assert.deepEqual(copied, [TOKEN_PLAIN]);
    assert.equal(dom.byId('token-copy-note').textContent, 'コピーしました。');
    dom.window.navigator = { clipboard: { writeText: async () => { throw new Error('denied'); } } };
    dom.byId('token-copy').fire('click');
    await flush();
    assert.match(dom.byId('token-copy-note').textContent, /コピーできませんでした。入力欄を選択して/);
    dom.window.navigator = { clipboard: { writeText: () => { throw new Error('sync'); } } };
    dom.byId('token-copy').fire('click');
    assert.match(dom.byId('token-copy-note').textContent, /コピーできませんでした/);
    dom.window.navigator = undefined;
    dom.byId('token-copy').fire('click');
    assert.match(dom.byId('token-copy-note').textContent, /自動でコピーできません/);

    // Closing empties the dialog.
    dom.byId('token-close').fire('click');
    assert.equal(dom.byId('token-dialog').open, false);
    assert.equal(dom.byId('token-value').value, '');
    assert.equal(dom.byId('token-meta').textContent, '');
    assert.equal(dom.byId('token-copy-note').textContent, '');
    copied.length = 0;
    dom.window.navigator = { clipboard: { writeText: async (t) => { copied.push(t); } } };
    dom.byId('token-copy').fire('click');
    await flush();
    assert.deepEqual(copied, [], 'nothing is left to copy');
    for (const [id, el] of Object.entries(dom.ids)) assert.ok(!el.textContent.includes(TOKEN_PLAIN), 'not in #' + id + ' after closing');
  } finally {
    for (const k of Object.keys(originals)) console[k] = originals[k];
  }

  // Esc (the close event) empties it as well.
  const esc = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf()],
    write: (p) => (p.endsWith('/tokens') ? { status: 201, data: { token: TOKEN_PLAIN, tokenRef: TOKEN_REF, expiresAt: '2027-01-23T00:00:00Z' } } : null),
  });
  await flush();
  asgOpen(esc);
  await flush();
  buttonIn(esc.byId('assignees-list'), 'トークンを発行…').fire('click');
  await confirmDialog(esc);
  assert.equal(esc.byId('token-value').value, TOKEN_PLAIN);
  esc.byId('token-dialog').open = false;
  esc.byId('token-dialog').fire('close');
  assert.equal(esc.byId('token-value').value, '');
});

test('the token field of the page is read-only, off for autocomplete, and the dialog says it is shown once', () => {
  const html = fs.readFileSync(path.join(path.dirname(APP_JS), 'index.html'), 'utf8');
  const field = /<input id="token-value"[^>]*>/.exec(html);
  assert.ok(field, 'token-value is in index.html');
  assert.match(field[0], /\breadonly\b/);
  assert.match(field[0], /autocomplete="off"/);
  assert.ok(html.includes('このトークンは、この 1 回しか表示されません。安全な場所に保管してください。'));
  const js = fs.readFileSync(APP_JS, 'utf8');
  assert.ok(!/localStorage|sessionStorage/.test(js), 'the script never touches web storage');
});

test('a failed issue, a bad TTL and a revoke use fixed texts; a revoke sends an empty JSON object', async () => {
  let reply = { status: 409, data: { error: 'LEAK', code: 'TOO_MANY_ACTIVE' } };
  const dom = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf()],
    tokens: () => [{ tokenRef: TOKEN_REF, status: 'ACTIVE', createdBy: 'x', createdAt: '2026-10-25T00:00:00Z', expiresAt: '2027-01-23T00:00:00Z' }],
    write: (p) => (p === '/api/selfdev/assignees/' + AS_AI + '/tokens' || p === '/api/selfdev/assignees/' + AS_AI + '/tokens/' + TOKEN_REF + '/revoke' ? reply : null),
  });
  await flush();
  asgOpen(dom);
  await flush();
  const ttl = () => inputsIn(dom.byId('assignees-list')).find((n) => n.type === 'number');
  assert.equal(ttl().value, '90', 'the default is 90 days');
  for (const bad of ['0', '366', '', 'abc', '1.5']) {
    ttl().value = bad;
    buttonIn(dom.byId('assignees-list'), 'トークンを発行…').fire('click');
    assert.match(dom.byId('assignees-msg').textContent, /1〜365 日/, bad);
    assert.equal(dom.byId('assignee-confirm-dialog').open, false, bad);
  }
  ttl().value = '365';
  buttonIn(dom.byId('assignees-list'), 'トークンを発行…').fire('click');
  await confirmDialog(dom);
  assert.match(dom.byId('assignees-msg').textContent, /有効なトークンが上限（2 つ）に達しています/);
  assert.equal(dom.byId('token-dialog').open, false, 'no token dialog on a failure');
  assert.equal(dom.byId('token-value').value, '');

  reply = { status: 204, data: undefined };
  buttonIn(dom.byId('assignees-list'), '失効…').fire('click');
  assert.match(dom.byId('assignee-confirm-text').textContent, /元に戻せません/);
  await confirmDialog(dom);
  const revoke = postsTo(dom.calls, '/api/selfdev/assignees/' + AS_AI + '/tokens/' + TOKEN_REF + '/revoke');
  assert.equal(revoke.length, 1);
  assert.deepEqual(revoke[0].body, {});
  assert.equal(dom.byId('assignees-msg').textContent, 'トークンを失効しました。');
});

// What each role sees. `actions(dom)` opens the goal and the order and lists every write control that is on the page.
async function roleActions(me, { pending = null } = {}) {
  const dom = asgLoad({ me, pending });
  await flush();
  buttonIn(dom.byId('plans-list'), '詳細').fire('click');
  buttonIn(dom.byId('orders-list'), '詳細').fire('click');
  await flush();
  const texts = [];
  for (const id of ['plans-list', 'orders-list', 'approval-list', 'budget-unpriced-list', 'memory-list']) {
    dom.byId(id).buttons().forEach((b) => texts.push(b.textContent));
  }
  return {
    dom,
    texts,
    goalForm: dom.byId('goal-form').hidden === false,
    orderCreate: dom.byId('order-create').hidden === false,
    memoryDelete: dom.byId('memory-delete-block').hidden === false,
    toggle: dom.byId('autopilot-toggle').hidden === false,
    memorySection: dom.byId('memory-section').hidden === false,
  };
}

const WRITE_BUTTONS = ['この Plan を承認', 'このタスクを承認', '見送る', '中止…', '一時停止', '停止を要求', '承認', '却下', '解除…', 'この記録を削除'];

test('every write button is on the page when /me is absent, or the mode is shadow or off, whatever the role', async () => {
  for (const [label, me] of [
    ['no /me', null],
    ['shadow viewer', meOf('shadow', 'VIEWER', ['READ'])],
    ['off viewer', meOf('off', 'VIEWER', ['READ'])],
    ['enforce admin', adminMe()],
  ]) {
    const r = await roleActions(me);
    for (const b of WRITE_BUTTONS) assert.ok(r.texts.includes(b), label + ': ' + b);
    assert.equal(r.goalForm, true, label);
    assert.equal(r.orderCreate, true, label);
    assert.equal(r.memoryDelete, true, label);
    assert.equal(r.toggle, true, label);
  }
});

test('with the roles enforced, a viewer sees no write button, and the memory list needs READ_TEXT', async () => {
  const r = await roleActions(meOf('enforce', 'VIEWER', ['READ']));
  for (const b of WRITE_BUTTONS) assert.ok(!r.texts.includes(b), 'hidden: ' + b);
  assert.equal(r.goalForm, false);
  assert.equal(r.orderCreate, false);
  assert.equal(r.memoryDelete, false);
  assert.equal(r.toggle, false);
  assert.equal(r.memorySection, false, 'no READ_TEXT: no memory section');
  assert.equal(r.dom.byId('nav-memory').hidden, true);
  assert.match(r.dom.byId('orders-list').textContent, new RegExp(O1), 'reading still works');
});

test('with the roles enforced, each permission shows exactly its own buttons', async () => {
  const cases = [
    ['ORDER_MANAGE', ['一時停止'], 'orderCreate'],
    ['STOP_REQUEST', ['停止を要求'], null],
    ['STOP_DECIDE', ['承認', '却下'], null],
    ['GOAL_WRITE', ['この Plan を承認', 'このタスクを承認', '見送る', 'この記録を削除'], 'goalForm'],
    ['GOAL_STOP', ['中止…'], null],
    ['BUDGET_OVERRIDE', ['解除…'], null],
  ];
  for (const [perm, own, flag] of cases) {
    const r = await roleActions(meOf('enforce', 'OPERATOR', ['READ', 'READ_TEXT', perm]));
    for (const b of WRITE_BUTTONS) {
      assert.equal(r.texts.includes(b), own.includes(b), perm + ': ' + b);
    }
    if (flag) assert.equal(r[flag], true, perm + ': ' + flag);
    for (const f of ['goalForm', 'orderCreate']) if (f !== flag) assert.equal(r[f], false, perm + ': ' + f + ' stays hidden');
    assert.equal(r.memorySection, true, 'READ_TEXT shows the memory list');
  }
  // Cancelling a waiting stop request is the stop-request permission too.
  const pending = stopRequestOf();
  const withPerm = await roleActions(meOf('enforce', 'AI', ['READ', 'STOP_REQUEST']), { pending });
  assert.ok(withPerm.texts.includes('要求を取り消す'));
  const without = await roleActions(meOf('enforce', 'VIEWER', ['READ']), { pending });
  assert.ok(!without.texts.includes('要求を取り消す'));
});

test('text from the server never makes an element in the assignee page (names, roles, states)', async () => {
  const dom = asgLoad({
    me: adminMe(),
    assignees: [assigneeOf({ displayName: EVIL, role: EVIL, state: EVIL, memberCount: EVIL, activeTokenCount: -1 })],
  });
  await flush();
  dom.byId('assignees-list').walk((n) => assert.ok(!['IMG', 'SCRIPT'].includes(n.tagName), n.tagName));
  assert.ok(dom.byId('assignees-list').textContent.includes(EVIL), 'the display name is literal text');
  assert.match(dom.byId('assignees-list').textContent, /メンバー: 不明 人/);
});

test('the failed list says so in fixed words, and the header refresh reads the assignees only on that page', async () => {
  const dom = asgLoad({ me: adminMe(), assignees: [assigneeOf()], listResponse: { status: 500, data: { error: 'LEAK' } } });
  await flush();
  assert.equal(dom.byId('assignees-section').hidden, false);
  assert.equal(dom.byId('assignees-status').textContent, '一覧を取得できませんでした。');
  assert.ok(!dom.byId('assignees-status').textContent.includes('LEAK'));

  const where = async (hash) => {
    const d = asgLoad({ me: adminMe(), assignees: [assigneeOf()] }, { hash });
    await flush();
    const from = d.calls.length;
    d.byId('refresh-all-btn').fire('click');
    await flush();
    return d.calls.slice(from).filter((c) => c.path === '/api/selfdev/assignees').length;
  };
  assert.equal(await where('#/assignees'), 1);
  assert.equal(await where('#/'), 0);
});
