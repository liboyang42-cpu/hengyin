// W10b #22:简易 AI 起草带 requestId;结果未知时锁住重试、只回读那一次,不拿额度涨落当证据。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/simple/index.js';
let sent = [];
let storage = {};
let pageConfig = null;

global.getApp = () => ({
  getUserID: () => 7,
  sendRequest: (request) => { sent.push(request); },
  getAuthorization: () => '',
});
global.wx = {
  navigateBack: () => {},
  choosePoi: () => {},
  navigateTo: () => {},
  setStorageSync: (k, v) => { storage[k] = JSON.parse(JSON.stringify(v)); },
  getStorageSync: (k) => storage[k] || '',
  removeStorageSync: (k) => { delete storage[k]; },
};
global.Page = (config) => { pageConfig = config; };

beforeEach(() => {
  sent = [];
  storage = {};
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

test('明确失败 / 没送达才解锁;关页重开会恢复锁并自动回读', () => {
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
