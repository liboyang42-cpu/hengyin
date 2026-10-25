// W10b #22:简易 AI 起草带 requestId;结果未知时锁住重试、只回读那一次,不拿额度涨落当证据。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/simple/index.js';
let sent = [];
let storage = {};
let pageConfig = null;
let owner;
let authorization;
let app;

global.getApp = () => app;
function stubApp() { return {
  getUserID: () => owner,
  sendRequest: (request) => { sent.push(request); },
  getAuthorization: () => authorization,
}; }
global.wx = {
  navigateBack: () => {},
  choosePoi: () => {},
  navigateTo: () => {},
  switchTab: () => {},
  setStorageSync: (k, v) => { storage[k] = JSON.parse(JSON.stringify(v)); },
  getStorageSync: (k) => storage[k] || '',
  removeStorageSync: (k) => { delete storage[k]; },
};
global.Page = (config) => { pageConfig = config; };

beforeEach(() => {
  sent = [];
  storage = {};
  owner = 7;
  authorization = 'token-a';
  app = stubApp();
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  page.setData = (patch) => {
    Object.keys(patch).forEach((key) => {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let target = page.data;
      for (let i = 0; i < parts.length - 1; i++) target = target[parts[i]];
      target[parts[parts.length - 1]] = patch[key];
    });
  };
  return page;
}

const DRAFT = { traceId: 't1', remainingQuota: 1, draft: { title: '第一份', nodes: [{ merchantName: '一站' }] } };

function loaded(remaining) {
  const page = makePage();
  page.onLoad({ mode: '2' });
  sent.shift().success({ code: 200, data: { limited: true, remaining } });
  page.setData({ aiIdea: '老城咖啡' });
  return page;
}

test('起草请求带 UUID requestId', () => {
  const page = loaded(3);
  page.generateAiPlan();
  assert.match(JSON.parse(sent[0].data).requestId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

test('网络失败 = 结果未知:锁住重试,再点只回读原 requestId,拿回第一份草稿', () => {
  const page = loaded(3);
  page.generateAiPlan();
  const post = sent.shift();
  const rid = JSON.parse(post.data).requestId;
  post.fail({ errMsg: 'request:fail timeout' });
  post.complete();
  assert.equal(page.data.aiDraftPending, true);
  assert.match(page.data.aiError, /结果还没确认/);

  page.generateAiPlan();
  const read = sent.shift();
  assert.equal(read.url, '/api/ai/theme/draft/request');
  assert.equal(read.method, 'GET');
  assert.deepEqual(read.data, { requestId: rid });
  read.success({ code: 200, data: { requestId: rid, state: 'SUCCEEDED', result: DRAFT } });
  read.complete();
  assert.equal(page.data.aiPlan.title, '第一份');
  assert.equal(page.data.aiDraftPending, false);
  assert.equal(page.data.aiRemaining, 3, '回放结果里的旧额度不当现值');
  assert.equal(sent.shift().url, '/api/ai/theme/draft/quota', '改为重新读额度');
  assert.equal(sent.filter((r) => r.url === '/api/ai/theme/draft').length, 0, '从未再次提交起草');
});

test('额度变化不是生成证据:未知期间额度变少也不解锁、不当作已生成', () => {
  const page = loaded(3);
  page.generateAiPlan();
  const post = sent.shift();
  post.fail({});
  post.complete();
  page.loadAiQuota();
  sent.shift().success({ code: 200, data: { limited: true, remaining: 2 } });
  assert.equal(page.data.aiDraftPending, true);
  assert.equal(page.data.aiPlan, null);
  page.generateAiPlan();
  assert.equal(sent.shift().url, '/api/ai/theme/draft/request');
});

test('服务端说仍在处理 / 无法确认:保持锁;无法确认且改了灵感才另起新 requestId', () => {
  const page = loaded(3);
  page.generateAiPlan();
  const post = sent.shift();
  const rid = JSON.parse(post.data).requestId;
  post.success({ code: 409, errorCode: 'AI_DRAFT_IN_PROGRESS', msg: '上一次起草还在进行中', requestId: rid });
  post.complete();
  assert.equal(page.data.aiDraftPending, true);

  page.generateAiPlan();
  const read = sent.shift();
  read.success({ code: 200, data: { requestId: rid, state: 'UNKNOWN' } });
  read.complete();
  assert.equal(page.data.aiDraftPending, true);
  assert.match(page.data.aiError, /可能再用一次额度/);

  page.generateAiPlan();
  assert.equal(sent.shift().url, '/api/ai/theme/draft/request', '灵感没改:仍只刷新');
  sent.length = 0;
  page.data.aiGenerating = false;
  page.setData({ aiIdea: '换个灵感' });
  page.generateAiPlan();
  const fresh = sent.shift();
  assert.equal(fresh.url, '/api/ai/theme/draft');
  assert.notEqual(JSON.parse(fresh.data).requestId, rid);
});

test('明确失败才解锁;关页重开会恢复锁并自动回读', () => {
  const page = loaded(3);
  page.generateAiPlan();
  const post = sent.shift();
  const rid = JSON.parse(post.data).requestId;
  post.fail({});
  post.complete();
  assert.equal(storage['ai_topic_draft_request:m7'].requestId, rid);

  const reopened = makePage();
  reopened.onLoad({ mode: '2' });
  const urls = sent.map((r) => r.url);
  assert.deepEqual(urls, ['/api/ai/theme/draft/quota', '/api/ai/theme/draft/request']);
  assert.equal(reopened.data.aiDraftPending, true);
  assert.equal(reopened.data.aiIdea, '老城咖啡');
  const read = sent[1];
  read.success({ code: 200, data: { requestId: rid, state: 'FAILED', message: '今日AI次数已用完' } });
  read.complete();
  assert.equal(reopened.data.aiDraftPending, false);
  assert.equal(reopened.data.aiError, '今日AI次数已用完');
  assert.equal(storage['ai_topic_draft_request:m7'], undefined);
});

test('明确的成功/失败响应直接收尾,不留锁', () => {
  const page = loaded(3);
  page.generateAiPlan();
  sent.shift().success({ code: 500, errorCode: 'AI_DRAFT_FAILED', msg: 'AI 服务暂未就绪' });
  assert.equal(page.data.aiDraftPending, false);
  page.data.aiGenerating = false;
  page.generateAiPlan();
  sent.shift().success({ code: 200, data: DRAFT });
  assert.equal(page.data.aiPlan.title, '第一份');
  assert.equal(page.data.aiRemaining, 1, '首次生成的响应额度照常采用');
  assert.equal(page.data.aiDraftPending, false);
});

function pendingRead(page) {
  page.generateAiPlan();
  const post = sent.shift();
  post.fail({});
  post.complete();
  page.checkAiDraftRequest();
  return sent.shift();
}
function readResult(read, state, result = DRAFT) {
  return { code: 200, data: { requestId: read.data.requestId, state, result } };
}

test('过期 NOT_FOUND 保留原 id/pending，不称未送达；同灵感只回读，改灵感复用 UNKNOWN 出口', () => {
  const page = loaded(3);
  const read = pendingRead(page);
  const rid = read.data.requestId;
  read.success(readResult(read, 'NOT_FOUND'));
  read.complete();
  assert.equal(page._aiPending.requestId, rid);
  assert.equal(storage['ai_topic_draft_request:m7'].requestId, rid);
  assert.equal(storage['ai_topic_draft_request:m7'].unknown, true);
  assert.equal(page.data.aiDraftPending, true);
  assert.match(page.data.aiError, /结果.*无法确认/);
  assert.match(page.data.aiError, /可能再用一次额度/);
  assert.doesNotMatch(page.data.aiError, /没有送达|重新生成/);
  page.generateAiPlan();
  const again = sent.shift();
  assert.deepEqual(again.data, { requestId: rid });
  again.success(readResult(again, 'NOT_FOUND'));
  again.complete();
  page.setData({ aiIdea: '新的灵感' });
  page.generateAiPlan();
  const fresh = sent.shift();
  assert.equal(fresh.url, '/api/ai/theme/draft');
  assert.notEqual(JSON.parse(fresh.data).requestId, rid);
});

for (const kind of ['提交', '回读']) {
  function start(page) {
    if (kind === '回读') return pendingRead(page);
    page.generateAiPlan();
    return sent.shift();
  }
  function success(request) {
    request.success(kind === '回读' ? readResult(request, 'SUCCEEDED') : { code: 200, data: DRAFT });
  }

  test(`${kind}旧回包和 complete 不能覆盖新请求或清新 busy/pending`, () => {
    const page = loaded(3);
    const old = start(page);
    // 明确 UNKNOWN 后用户改灵感：新的一次已经开始，旧传输的重复回执仍可能迟到。
    if (kind === '回读') old.success(readResult(old, 'UNKNOWN'));
    else old.success({ code: 409, errorCode: 'AI_DRAFT_OUTCOME_UNKNOWN' });
    old.complete();
    page.setData({ aiIdea: '新的灵感' });
    page.generateAiPlan();
    const fresh = sent.shift();
    const rid = JSON.parse(fresh.data).requestId;
    success(old);
    old.fail({});
    old.complete();
    assert.equal(page.data.aiGenerating, true);
    assert.equal(page.data.aiPlan, null);
    assert.equal(page._aiPending.requestId, rid);
    assert.equal(storage['ai_topic_draft_request:m7'].requestId, rid);
    fresh.success({ code: 200, data: DRAFT });
    fresh.complete();
    assert.equal(page.data.aiGenerating, false);
  });

  test(`${kind}同一请求重复 success/fail 只接受第一次回执`, () => {
    const page = loaded(3);
    const request = start(page);
    success(request);
    const snapshot = JSON.stringify(page.data);
    const saved = JSON.stringify(storage);
    request.fail({});
    request.success({ code: 500, msg: '重复失败' });
    assert.equal(JSON.stringify(page.data), snapshot);
    assert.equal(JSON.stringify(storage), saved);
    request.complete();
    assert.equal(page.data.aiGenerating, false);
  });

  test(`${kind}换号不触碰新账号存储，A→B→A 后仍拒旧回包`, () => {
    const page = loaded(3);
    const old = start(page);
    const aPending = JSON.stringify(storage['ai_topic_draft_request:m7']);
    storage['ai_topic_draft_request:m8'] = { requestId: 'b-pending' };
    owner = 8;
    page.onShow();
    success(old);
    old.fail({});
    old.complete();
    assert.equal(JSON.stringify(storage['ai_topic_draft_request:m7']), aPending);
    assert.equal(storage['ai_topic_draft_request:m8'].requestId, 'b-pending');
    owner = 7;
    page.onShow();
    page.checkAiDraftRequest();
    const current = sent.shift();
    assert.equal(page.data.aiGenerating, true);
    success(old);
    old.complete();
    assert.equal(page.data.aiGenerating, true);
    assert.equal(page.data.aiPlan, null);
    current.success(readResult(current, 'FAILED'));
    current.complete();
    assert.equal(page.data.aiGenerating, false);
  });

  test(`${kind}卸载后所有回调不写页面/存储；重开仅恢复原请求`, () => {
    const page = loaded(3);
    const request = start(page);
    const rid = page._aiPending.requestId;
    page.onUnload();
    const snapshot = JSON.stringify(page.data);
    const saved = JSON.stringify(storage);
    success(request);
    request.fail({});
    request.complete();
    assert.equal(JSON.stringify(page.data), snapshot);
    assert.equal(JSON.stringify(storage), saved);
    const reopened = makePage();
    reopened.onLoad({ mode: '2' });
    assert.equal(sent[1].data.requestId, rid);
  });
}

test('配额仅消费最新请求一次；旧配额不覆盖新起草响应', () => {
  const page = loaded(3);
  page.loadAiQuota();
  const old = sent.shift();
  page.loadAiQuota();
  const fresh = sent.shift();
  fresh.success({ code: 200, data: { limited: true, remaining: 2 } });
  fresh.success({ code: 200, data: { limited: true, remaining: 99 } });
  old.success({ code: 200, data: { limited: true, remaining: 98 } });
  assert.equal(page.data.aiRemaining, 2);
  page.loadAiQuota();
  const beforeDraft = sent.shift();
  page.generateAiPlan();
  sent.shift().success({ code: 200, data: DRAFT });
  beforeDraft.success({ code: 200, data: { limited: true, remaining: 3 } });
  beforeDraft.complete();
  assert.equal(page.data.aiRemaining, 1);
});

test('配额换号往返及卸载后拒绝迟到，complete 不清起草 busy', () => {
  const page = loaded(3);
  page.loadAiQuota();
  const old = sent.shift();
  owner = 8;
  page.onShow();
  owner = 7;
  page.onShow();
  old.success({ code: 200, data: { limited: true, remaining: 99 } });
  assert.equal(page.data.aiRemaining, 3);
  page.loadAiQuota();
  const quota = sent.shift();
  page.generateAiPlan();
  quota.complete();
  assert.equal(page.data.aiGenerating, true);
  page.onUnload();
  const snapshot = JSON.stringify(page.data);
  quota.success({ code: 200, data: { limited: true, remaining: 98 } });
  assert.equal(JSON.stringify(page.data), snapshot);
});

test('在途换号且尚未 onShow，回调也不能写当前账号的 pending key', () => {
  const page = loaded(3);
  page.generateAiPlan();
  const post = sent.shift();
  const saved = JSON.stringify(storage);
  owner = 8;
  post.success({ code: 200, data: DRAFT });
  post.complete();
  assert.equal(JSON.stringify(storage), saved);
  assert.equal(page.data.aiPlan, null);
});

// 用真实 transport 证明自有续登与外部会话变化的区别，不在页面再造认证状态机。
function realTransport() {
  const raw = [];
  const { createRequestClient } = require('../../utils/transport/request-client.js');
  const client = createRequestClient({
    wxRequest: request => { raw.push(request); return { abort() {} }; },
    getBaseUrl: () => 'https://example.invalid',
    getUserID: () => owner,
    getAuthorization: () => authorization,
    reLogin: callback => {
      authorization = 'renewed-token';
      callback(true, { isCurrentSession: () => true });
    },
  });
  app.sendRequest = request => client.send(request);
  return raw;
}
function reply(request, data) {
  request.success({ statusCode: 200, data });
  request.complete({});
}

for (const kind of ['提交', '回读', '配额']) {
  test(`${kind}真实 transport 同账号合法续登：新认证头重发，原请求完成且不卡 busy`, () => {
    const page = loaded(3);
    if (kind === '回读') {
      page.generateAiPlan();
      const post = sent.shift();
      post.fail({}); post.complete();
    }
    const raw = realTransport();
    if (kind === '配额') page.loadAiQuota();
    else if (kind === '回读') page.checkAiDraftRequest();
    else page.generateAiPlan();
    const pending = page._aiPending && page._aiPending.requestId;
    raw[0].success({ statusCode: 401, data: { code: 401 } });
    raw[0].complete({});
    assert.equal(raw.length, 2);
    assert.equal(raw[0].header.Authorization, 'token-a');
    assert.equal(raw[1].header.Authorization, 'renewed-token');
    assert.deepEqual(raw[1].data, raw[0].data, '原请求含原 requestId 不变');
    if (kind !== '配额') assert.equal(page.data.aiGenerating, true, '中间401不提前收尾');
    const result = kind === '配额' ? { code: 200, data: { limited: true, remaining: 2 } }
      : kind === '回读' ? { code: 200, data: { requestId: pending, state: 'SUCCEEDED', result: DRAFT } }
        : { code: 200, data: DRAFT };
    reply(raw[1], result);
    assert.equal(page.data.aiGenerating, false);
    if (kind !== '配额') {
      assert.equal(page.data.aiPlan.title, '第一份');
      assert.equal(page.data.aiDraftPending, false);
      assert.equal(storage['ai_topic_draft_request:m7'], undefined);
    } else assert.equal(page.data.aiRemaining, 2);
  });

  test(`${kind}真实 transport 外部会话变化拒旧；保 pending 并允许重新回读，不锁死`, () => {
    const page = loaded(3);
    if (kind === '回读') {
      page.generateAiPlan();
      const post = sent.shift();
      post.fail({}); post.complete();
    }
    const raw = realTransport();
    if (kind === '配额') page.loadAiQuota();
    else if (kind === '回读') page.checkAiDraftRequest();
    else page.generateAiPlan();
    const saved = JSON.stringify(storage);
    // 外部 A→B→A（新凭据），不是本请求发起的续登。
    owner = 8; authorization = 'token-b';
    owner = 7; authorization = 'external-token-a';
    reply(raw[0], { code: 200, data: DRAFT });
    assert.equal(page.data.aiPlan, null);
    assert.equal(page.data.aiGenerating, false);
    assert.equal(JSON.stringify(storage), saved);
    if (kind === '配额') page.loadAiQuota();
    else page.checkAiDraftRequest();
    assert.equal(raw.length, 2, '外部变化不自动重放，用户仍可查询');
    if (kind !== '配额') assert.equal(raw[1].data.requestId, page._aiPending.requestId);
    reply(raw[1], kind === '配额' ? { code: 200, data: { limited: true, remaining: 2 } }
      : { code: 200, data: { requestId: page._aiPending.requestId, state: 'UNKNOWN' } });
    assert.equal(page.data.aiGenerating, false);
  });
}
