const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
let storage;
test.beforeEach(() => {
  storage = {};
  global.wx = {
    getStorageSync: key => storage[key],
    setStorageSync: (key, value) => { storage[key] = JSON.parse(JSON.stringify(value)); },
  };
});

const COMPONENT_PATH = require.resolve('../../components/cy/npc-chat/index.js');
let definition;
let activeApp;

function loadDefinition() {
  const previousComponent = global.Component;
  global.Component = (options) => { definition = options; };
  delete require.cache[COMPONENT_PATH];
  require(COMPONENT_PATH);
  global.Component = previousComponent;
}

function createChat(app, properties) {
  if (!app.getUserID) app.getUserID = () => '9';
  activeApp = app;
  const instance = {
    properties: Object.assign({ show: true, sessionId: 91, scope: 'roam' }, properties),
    data: JSON.parse(JSON.stringify(definition.data)),
    events: [],
    setData(update) { Object.assign(this.data, update); },
    triggerEvent(name, detail) { this.events.push({ name, detail }); },
  };
  Object.assign(instance, definition.methods);
  return instance;
}

function inputAndSend(chat, message) {
  chat.onInput({ detail: { value: message } });
  chat.onSend();
}

function response(requestId, text, audioUrl) {
  return {
    code: 200,
    data: {
      requestId,
      outcomeStatus: 'SUCCEEDED',
      safetyDecision: 'PASS',
      errorCode: '',
      safeText: text,
      audioUrl,
    },
  };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

global.getApp = () => activeApp;
loadDefinition();

test('cy-npc-chat 用普通 POST 接收审核完成结果后才本地逐字呈现', async () => {
  const requests = [];
  const chat = createChat({
    sendRequest(options) {
      requests.push(options);
      return { abort() {} };
    },
  });

  inputAndSend(chat, '下一步怎么探索？');
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/ai/npc/chat');
  assert.equal(requests[0].method, 'POST');
  assert.equal(requests[0].header['content-type'], 'application/json');
  assert.equal(requests[0].timeout, 30000, '需覆盖模型生成与随后语音合成的串行预算');
  assert.equal(requests[0].data.bizId, 91);
  assert.match(requests[0].data.requestId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

  requests[0].success(response(requests[0].data.requestId, '安全回复'));
  await sleep(80);
  assert.equal(chat.data.messages.at(-1).role, 'assistant');
  assert.equal(chat.data.messages.at(-1).text, '安全回复');
  assert.equal(chat.data.state, 'DONE');
});

test('商家分身回复带 audioUrl 时播放语音，关闭时销毁', () => {
  const requests = [];
  const audio = { src: '', played: 0, stopped: 0, destroyed: 0,
    play() { this.played++; }, stop() { this.stopped++; }, destroy() { this.destroyed++; },
    onEnded(fn) { this.ended = fn; }, onError(fn) { this.failed = fn; } };
  const previousWx = global.wx;
  global.wx = Object.assign({}, previousWx, { createInnerAudioContext: () => audio });
  const chat = createChat({
    sendRequest(options) { requests.push(options); return { abort() {} }; },
  }, { scope: 'merchant' });

  inputAndSend(chat, '你们几点关门？');
  requests[0].success(response(requests[0].data.requestId, '晚上十点', 'https://cdn.example/npc.mp3'));
  assert.equal(audio.src, 'https://cdn.example/npc.mp3');
  assert.equal(audio.played, 1);
  chat.cancelPending();
  assert.equal(audio.stopped, 1);
  assert.equal(audio.destroyed, 1);
  global.wx = previousWx;
});

test('等待原回答时禁止发送新问题', async () => {
  const requests = [];
  let aborts = 0;
  const chat = createChat({
    sendRequest(options) {
      requests.push(options);
      return { abort() { aborts++; } };
    },
  });

  inputAndSend(chat, '第一条');
  const first = requests[0];
  inputAndSend(chat, '第二条');
  assert.equal(requests.length, 1);
  assert.equal(aborts, 0);
  first.fail();
  inputAndSend(chat, '第三条');
  assert.equal(requests.length, 1, '未知结果不能生成新请求');
  chat.onRecover();
  assert.equal(requests[1].method, 'GET');
  assert.deepEqual(requests[1].data, { requestId: first.data.requestId, scope: 'roam', bizId: '91' }, '恢复只查原 ID/对象，不重发原文');
  first.success(response(first.data.requestId, '旧响应不应出现'));
  requests[1].success(response(first.data.requestId, '已恢复'));
  await sleep(80);
  assert.equal(chat.data.messages.at(-1).text, '已恢复');
  assert.equal(chat.data.canRecover, false);
});

test('cancelPending 终止挂起请求并隔离关闭后的迟到回调', () => {
  const requests = [];
  let aborts = 0;
  const chat = createChat({
    sendRequest(options) {
      requests.push(options);
      return { abort() { aborts++; } };
    },
  });

  inputAndSend(chat, '离开页面前的问题');
  chat.cancelPending();
  requests[0].success(response(requests[0].data.requestId, '关闭后不能显示'));

  assert.equal(aborts, 1);
  assert.equal(chat.data.messages.length, 1, '只保留已发送的用户消息，迟到回答不得追加');
  assert.equal(chat.data.state, 'CANCELLED');
});

test('HTTP 非 2xx 会收敛为失败，不会永远停在生成中', () => {
  const requests = [];
  const chat = createChat({
    sendRequest(options) {
      requests.push(options);
      return { abort() {} };
    },
  });

  inputAndSend(chat, '服务器异常时怎么办？');
  requests[0].successStatusAbnormal({ code: 500 });

  assert.equal(chat.data.state, 'UNKNOWN');
  assert.equal(chat.data.isThinking, false);
  assert.match(chat.data.messages.at(-1).text, /回答结果待确认/);
});


test('关闭再打开对话仍保留原问题恢复，不重建 ID', () => {
  const requests = [];
  const chat = createChat({ sendRequest(opts) { requests.push(opts); return { abort() {} }; } });
  inputAndSend(chat, '问题');
  chat.cancelPending();
  chat._resetForClose();
  assert.equal(chat.data.canRecover, true);
  chat.onRecover();
  assert.equal(requests[1].method, 'GET');
  assert.equal(requests[1].data.requestId, requests[0].data.requestId);
});

test('F0231 real Node process exit/restart restores only original UUID; B cannot see A and no second model POST occurs', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'npc-restart-'));
  const file = path.join(dir, 'storage.json');
  const harness = `
    const fs = require('node:fs');
    const [componentPath, file, phase, owner] = process.argv.slice(1);
    const requests = [];
    let stored = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
    global.wx = { getStorageSync: key => stored[key], setStorageSync: (key, value) => {
      stored[key] = value; fs.writeFileSync(file, JSON.stringify(stored));
    } };
    global.getApp = () => ({ getUserID: () => owner, sendRequest: options => {
      requests.push(options); return { abort() {} };
    } });
    let definition;
    global.Component = value => { definition = value; };
    require(componentPath);
    const chat = Object.assign({ properties: { show: true, sessionId: 91, scope: 'merchant' },
      data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch); }
    }, definition.methods);
    if (phase === 'send') { chat.onInput({ detail: { value: 'private original text' } }); chat.onSend(); }
    else definition.lifetimes.attached.call(chat);
    if (phase === 'resolve') requests[0].success({ code: 200, data: { requestId: requests[0].data.requestId,
      outcomeStatus: 'SUCCEEDED', safetyDecision: 'PASS', safeText: 'OK' } });
    process.stdout.write(JSON.stringify({ requests: requests.map(r => ({ url: r.url, method: r.method, data: r.data })),
      messages: chat.data.messages, canRecover: chat.data.canRecover }));
  `;
  function run(phase, owner = '9') {
    const result = spawnSync(process.execPath, ['-e', harness, COMPONENT_PATH, file, phase, owner], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return JSON.parse(result.stdout);
  }
  try {
    const sent = run('send');
    const original = sent.requests[0].data.requestId;
    assert.equal(sent.requests[0].method, 'POST');
    assert.doesNotMatch(fs.readFileSync(file, 'utf8'), /private|message|audio|token/);
    assert.deepEqual(run('recover', '10').requests, []);
    assert.deepEqual(run('recover', '').requests, []);
    const recovered = run('recover');
    assert.deepEqual(recovered.requests, [{ url: '/api/ai/npc/request-status', method: 'GET',
      data: { requestId: original, scope: 'merchant', bizId: '91' } }]);
    assert.equal(recovered.messages.length, 0, 'reentry must not reconstruct the private input');
    const resolved = run('resolve');
    assert.equal(resolved.requests[0].data.requestId, original);
    assert.equal(resolved.messages.at(-1).text, 'OK');
    assert.equal(resolved.canRecover, false);
    assert.deepEqual(run('recover').requests, [], 'terminal cleanup survives another process restart');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('F0231 recreated component rejects different NPC/scope and stale owner/page callbacks without clearing A', () => {
  let owner = '9';
  const requests = [];
  const app = { getUserID: () => owner, sendRequest: opts => { requests.push(opts); return { abort() {} }; } };
  const first = createChat(app);
  inputAndSend(first, 'original');
  const original = requests[0].data.requestId;
  for (const properties of [{ sessionId: 92 }, { scope: 'merchant' }]) {
    const other = createChat(app, properties);
    definition.lifetimes.attached.call(other);
    assert.equal(other.data.canRecover, false);
  }
  owner = '10';
  requests[0].success(response(original, 'old owner text'));
  assert.equal(first.data.messages.length, 1);
  owner = '9';
  definition.lifetimes.detached.call(first);
  requests[0].success(response(original, 'closed page text'));
  assert.equal(first.data.messages.length, 1);
  const recreated = createChat(app);
  definition.lifetimes.attached.call(recreated);
  assert.equal(requests.length, 2);
  assert.equal(requests[1].data.requestId, original);
  requests[1].success({ code: 403 });
  assert.equal(recreated.data.canRecover, true);
  assert.equal(storage['npc_pending_requests_v1:9'].requests[0].requestId, original);
});

test('F0231 component storage failures stop POST; malformed terminal and cleanup failure keep original locked', () => {
  const requests = [];
  const app = { sendRequest: opts => { requests.push(opts); return { abort() {} }; } };
  const originalWrite = wx.setStorageSync;
  wx.setStorageSync = () => { throw Error('quota'); };
  inputAndSend(createChat(app), 'unsent');
  assert.equal(requests.length, 0);
  wx.setStorageSync = originalWrite;
  const chat = createChat(app);
  inputAndSend(chat, 'original');
  const original = requests[0].data.requestId;
  requests[0].fail();
  chat.onRecover();
  requests[1].success({ code: 200, data: { requestId: original, outcomeStatus: 'REJECTED',
    safetyDecision: 'NOT_RUN', errorCode: 'INVALID_REQUEST', safeText: 'invalid' } });
  assert.equal(chat.data.canRecover, true);
  chat.onRecover();
  wx.setStorageSync = () => { throw Error('quota'); };
  requests[2].success(response(original, 'OK'));
  wx.setStorageSync = originalWrite;
  inputAndSend(chat, 'next');
  assert.equal(requests.length, 3, 'failed cleanup cannot permit a second billable send');
  assert.equal(chat.data.canRecover, true);
  chat.onRecover();
  assert.equal(requests[3].method, 'GET');
  assert.equal(requests[3].data.requestId, original);
});
