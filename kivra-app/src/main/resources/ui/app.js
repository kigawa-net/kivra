/*
 * Kivra minimal UI (see README "UI"). Plain JavaScript, no framework, no HTML string injection.
 * Server data enters the DOM only through textContent (see h() and str()).
 * Never display a Run as executed: every Run here is SIMULATED and executed:false.
 */
(function () {
  'use strict';

  var REFRESH_MS = 30000;
  var TICK_MS = 1000;
  var MAX_TITLE = 200;
  var CONTROL_CHARS = /[\u0000-\u001f\u007f-\u009f]/;

  var MESSAGES = {
    unauthorized: 'ログインが必要です、または権限がありません。ログインし直してから、もう一度お試しください。',
    notFound: '見つかりません（他のユーザーの Run は見えません）',
    badRequest: '入力内容を確認してください（1〜200 文字、制御文字は使えません）',
    conflict: 'この投入は受け付けられませんでした（同じ投入キーで別のタイトルが使われていました）。もう一度投入してください。',
    server: 'サーバーでエラーが起きました。時間をおいてもう一度お試しください。',
    network: '通信に失敗しました。',
    submitNetwork: '通信に失敗しました。Run が作成されたかどうかは確認できていません。同じ内容で投入し直しても、同じ Run として扱われます（二重には作られません）。',
    invalid: '応答の形式が想定と違います。ログインが切れている可能性があります。ページを再読み込みしてください。'
  };

  var state = {
    idempotencyKey: newKey(),
    runs: null,
    runsOkAt: 0,
    runsFailed: false,
    runsErrorKind: null,
    runsLoading: false,
    runsLastAttemptAt: 0,
    healthOk: null,
    healthCheckedAt: 0,
    healthOkAt: 0,
    healthLoading: false,
    submitting: false,
    detailSeq: 0,
    timer: null
  };

  function $(id) {
    return document.getElementById(id);
  }

  /** Builds an element. Text only goes through textContent; attributes are restricted to a fixed list. */
  function h(tag, props, children) {
    var node = document.createElement(tag);
    var p = props || {};
    Object.keys(p).forEach(function (k) {
      var v = p[k];
      if (k === 'className') node.className = v;
      else if (k === 'text') node.textContent = v;
      else if (k === 'href') node.setAttribute('href', v);
      else if (k === 'type') node.setAttribute('type', v);
      else throw new Error('unsupported prop: ' + k);
    });
    (children || []).forEach(function (c) {
      if (c === null || c === undefined) return;
      node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    });
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function str(v) {
    return v === null || v === undefined ? '' : String(v);
  }

  function newKey() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
    var b = new Uint8Array(16);
    window.crypto.getRandomValues(b);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var hex = Array.prototype.map.call(b, function (x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
  }

  function parseTime(s) {
    var t = Date.parse(str(s));
    return isNaN(t) ? null : t;
  }

  function fmtLocal(ms) {
    return new Date(ms).toLocaleString('ja-JP');
  }

  function fmtUtc(ms) {
    return new Date(ms).toISOString().replace('T', ' ').replace(/\.\d{3}Z$/, ' UTC');
  }

  function fmtClock(ms) {
    return new Date(ms).toLocaleTimeString('ja-JP');
  }

  function relative(ms, now) {
    var sec = Math.round((now - ms) / 1000);
    if (sec < 60) return sec <= 0 ? 'たった今' : sec + ' 秒前';
    if (sec < 3600) return Math.floor(sec / 60) + ' 分前';
    if (sec < 86400) return Math.floor(sec / 3600) + ' 時間前';
    return Math.floor(sec / 86400) + ' 日前';
  }

  function stateDescription(s) {
    if (s === 'CREATED') return '作成済みで、まだ何も実行されていません';
    return 'この画面では説明を用意していません（この API が作る Run は CREATED のみです）';
  }

  /** Maps a non-2xx status to a fixed message key. The response body is never shown. */
  function kindOf(status) {
    if (status === 400) return 'badRequest';
    if (status === 401) return 'unauthorized';
    if (status === 404) return 'notFound';
    if (status === 409) return 'conflict';
    return 'server';
  }

  /** Same-origin only. This page sets no credential header: the reverse proxy (oauth2-proxy) adds the token. */
  async function apiFetch(path, method, body) {
    var init = {
      method: method,
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    var res;
    try {
      res = await fetch(path, init);
    } catch (e) {
      return { ok: false, kind: 'network' };
    }
    if (res.redirected) return { ok: false, kind: 'unauthorized' };
    if (!res.ok) return { ok: false, kind: kindOf(res.status), status: res.status };
    try {
      return { ok: true, status: res.status, data: await res.json() };
    } catch (e) {
      return { ok: false, kind: 'invalid' };
    }
  }

  function stateLabel(s) {
    return str(s) === '' ? '（不明）' : str(s);
  }

  // ---- Submit -----------------------------------------------------------

  function showResult(text, cls) {
    var el = $('submit-result');
    el.textContent = text;
    el.className = 'result' + (cls ? ' ' + cls : '');
  }

  function updateCounter() {
    var n = Array.from($('title').value).length;
    $('title-count').textContent = n + ' / ' + MAX_TITLE;
  }

  async function onSubmit(ev) {
    ev.preventDefault();
    if (state.submitting) return;
    var title = $('title').value.trim();
    var n = Array.from(title).length;
    if (n === 0 || n > MAX_TITLE || CONTROL_CHARS.test(title)) {
      showResult(MESSAGES.badRequest, 'error');
      return;
    }
    state.submitting = true;
    $('submit-btn').disabled = true;
    showResult('送信中です…', '');

    var r = await apiFetch('/api/tasks', 'POST', { title: title, idempotencyKey: state.idempotencyKey });

    state.submitting = false;
    $('submit-btn').disabled = false;

    var accepted = r.ok && (r.status === 202 || r.status === 200) && r.data && typeof r.data.runId === 'string';
    if (accepted) {
      // A new key only after success. (Same key + same title is a replay that returns the same Run.)
      state.idempotencyKey = newKey();
      $('title').value = '';
      updateCounter();
      var stateText = '状態: ' + stateLabel(r.data.state) + '（シミュレーション。executed: false）';
      if (r.status === 202) {
        showResult('受け付けました。Run ID: ' + str(r.data.runId) + ' / ' + stateText, 'ok');
      } else {
        showResult('同じ投入として既に受け付け済みです（既存の Run）。Run ID: ' + str(r.data.runId) + ' / ' + stateText, 'ok');
      }
      refreshRuns();
      return;
    }

    if (r.kind === 'conflict') state.idempotencyKey = newKey();
    var msg = r.kind === 'network' ? MESSAGES.submitNetwork : (r.ok ? MESSAGES.invalid : (MESSAGES[r.kind] || MESSAGES.server));
    showResult(msg, 'error');
  }

  // ---- Run list ---------------------------------------------------------

  function runRow(run) {
    var id = str(run.runId);
    var created = parseTime(run.createdAt);
    var when = created === null ? str(run.createdAt) : fmtLocal(created) + '（' + relative(created, Date.now()) + '）';
    var executedOk = run.executed === false;
    return h('li', {}, [
      h('a', { className: 'run-link', href: '#/runs/' + encodeURIComponent(id) }, [
        h('div', { className: 'run-head' }, [
          h('span', { className: 'run-title', text: str(run.title) }),
          h('span', { className: 'badge', text: '状態: ' + stateLabel(run.state) })
        ]),
        h('div', { className: 'run-meta' }, [
          h('span', { className: 'badge', text: str(run.mode) || 'mode 不明' }),
          h('span', { className: executedOk ? 'badge' : 'badge warn', text: 'executed: ' + str(run.executed) }),
          h('span', { className: 'time', text: when })
        ])
      ])
    ]);
  }

  function renderRuns() {
    var ul = $('runs-list');
    clear(ul);
    if (!state.runs) return;
    if (state.runs.length === 0) {
      ul.appendChild(h('li', { className: 'empty', text: 'まだ Run はありません。上のフォームから Task を投入できます。' }));
      return;
    }
    state.runs.forEach(function (run) {
      if (run && typeof run === 'object' && typeof run.runId === 'string') ul.appendChild(runRow(run));
    });
  }

  function renderRunsStatus(now) {
    var el = $('runs-status');
    var list = $('runs-list');
    if (state.runsFailed) {
      list.classList.add('stale');
      var reason = MESSAGES[state.runsErrorKind] || MESSAGES.server;
      if (state.runsOkAt === 0) {
        el.textContent = '一覧を取得できていません（更新に失敗: ' + reason + '）';
      } else {
        var sec = Math.max(0, Math.round((now - state.runsOkAt) / 1000));
        el.textContent = sec + ' 秒前のデータ（更新に失敗: ' + reason + '）';
      }
      el.className = 'meta error';
      return;
    }
    list.classList.remove('stale');
    el.className = 'meta';
    if (state.runsOkAt === 0) {
      el.textContent = '未取得';
    } else {
      el.textContent = '最終取得: ' + fmtLocal(state.runsOkAt) + '（' + relative(state.runsOkAt, now) + '）';
    }
  }

  async function refreshRuns() {
    if (state.runsLoading) return;
    state.runsLoading = true;
    state.runsLastAttemptAt = Date.now();
    try {
      var r = await apiFetch('/api/runs', 'GET');
      if (r.ok && Array.isArray(r.data)) {
        state.runs = r.data;
        state.runsOkAt = Date.now();
        state.runsFailed = false;
        state.runsErrorKind = null;
        renderRuns();
      } else {
        state.runsFailed = true;
        state.runsErrorKind = r.ok ? 'invalid' : r.kind;
      }
    } finally {
      state.runsLoading = false;
      renderRunsStatus(Date.now());
    }
  }

  // ---- Health -----------------------------------------------------------

  async function checkHealth() {
    if (state.healthLoading) return;
    state.healthLoading = true;
    try {
      var r = await apiFetch('/health', 'GET');
      state.healthCheckedAt = Date.now();
      state.healthOk = !!(r.ok && r.data && r.data.status === 'ok');
      if (state.healthOk) state.healthOkAt = state.healthCheckedAt;
    } finally {
      state.healthLoading = false;
      renderHealth();
    }
  }

  function renderHealth() {
    var el = $('health-status');
    if (state.healthOk === null) {
      el.textContent = '接続: 未確認';
      el.className = 'conn';
    } else if (state.healthOk) {
      el.textContent = '接続: OK（確認 ' + fmtClock(state.healthCheckedAt) + '）';
      el.className = 'conn ok';
    } else {
      var last = state.healthOkAt ? '、最後に成功 ' + fmtClock(state.healthOkAt) : '、成功したことはありません';
      el.textContent = '接続: 失敗（確認 ' + fmtClock(state.healthCheckedAt) + last + '）';
      el.className = 'conn ng';
    }
  }

  // ---- Detail -----------------------------------------------------------

  function renderDetail(container, run) {
    clear(container);
    if (!run || typeof run !== 'object') {
      container.appendChild(h('p', { className: 'error', text: MESSAGES.invalid }));
      return;
    }
    var created = parseTime(run.createdAt);
    var executedText = run.executed === false
      ? 'false（実行されていません）'
      : str(run.executed) + '（想定外の値です。運用者に確認してください）';
    var modeText = run.mode === 'SIMULATED'
      ? 'SIMULATED（シミュレーション。Agent や外部システムは起動されません）'
      : str(run.mode) + '（想定外の値です）';
    var rows = [
      ['タイトル', str(run.title)],
      ['状態', stateLabel(run.state) + ' — ' + stateDescription(run.state)],
      ['モード', modeText],
      ['executed', executedText],
      ['Run ID', str(run.runId)],
      ['Task ID', str(run.taskId)],
      ['作成日時（ローカル）', created === null ? str(run.createdAt) : fmtLocal(created)],
      ['作成日時（UTC）', created === null ? '' : fmtUtc(created)],
      ['この画面の取得時刻', fmtLocal(Date.now())]
    ];
    var dl = h('dl', {}, []);
    rows.forEach(function (row) {
      dl.appendChild(h('dt', { text: row[0] }));
      dl.appendChild(h('dd', { text: row[1] }));
    });
    container.appendChild(dl);
  }

  async function loadDetail(id) {
    var seq = ++state.detailSeq;
    var body = $('detail-body');
    clear(body);
    if (id === '') {
      body.appendChild(h('p', { className: 'error', text: 'リンクが正しくありません' }));
      return;
    }
    body.appendChild(h('p', { className: 'empty', text: '読み込み中…' }));
    var r = await apiFetch('/api/runs/' + encodeURIComponent(id), 'GET');
    if (seq !== state.detailSeq) return;
    if (!r.ok) {
      clear(body);
      body.appendChild(h('p', { className: 'error', text: MESSAGES[r.kind] || MESSAGES.server }));
      return;
    }
    renderDetail(body, r.data);
  }

  /** Returns null for the list view, the decoded run id for #/runs/<id>, or '' for a malformed id. */
  function runIdFromHash() {
    var m = /^#\/runs\/(.+)$/.exec(window.location.hash);
    if (!m) return null;
    try {
      return decodeURIComponent(m[1]);
    } catch (e) {
      return '';
    }
  }

  function route() {
    var id = runIdFromHash();
    var showDetail = id !== null;
    $('view-list').hidden = showDetail;
    $('view-detail').hidden = !showDetail;
    if (showDetail) {
      loadDetail(id);
    } else {
      window.scrollTo(0, 0);
    }
  }

  // ---- Timers (stopped while the tab is hidden) --------------------------

  function tick() {
    var now = Date.now();
    if (now - state.runsLastAttemptAt >= REFRESH_MS) refreshRuns();
    if (now - state.healthCheckedAt >= REFRESH_MS) checkHealth();
    renderRunsStatus(now);
  }

  function startTimers() {
    if (state.timer) return;
    state.timer = setInterval(tick, TICK_MS);
    tick();
  }

  function stopTimers() {
    if (state.timer) clearInterval(state.timer);
    state.timer = null;
  }

  function init() {
    $('submit-form').addEventListener('submit', onSubmit);
    $('title').addEventListener('input', updateCounter);
    $('refresh-btn').addEventListener('click', function () {
      refreshRuns();
      checkHealth();
    });
    window.addEventListener('hashchange', route);
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) stopTimers();
      else startTimers();
    });
    updateCounter();
    renderHealth();
    renderRunsStatus(Date.now());
    route();
    if (!document.hidden) startTimers();
  }

  init();
})();
