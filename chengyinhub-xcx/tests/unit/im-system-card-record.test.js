'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const CHAT = require.resolve('../../subpackageB/pages/im/chat/index.js');
const event = extra => ({ currentTarget: { dataset: { msg: 5, ...extra } } });
const result = extra => ({ taskId: 77, bizType: 'COMMUNITY_POST', bizId: 9, outcome: '生成时已驳回', reason: '生成时原因', ...extra });
function harness(t, card = { title: '审核未通过', result: result() }, senderId = 0) {
  const h = { uid: 1, requests: [], nav: [], tips: [] };
  h.app = { globalData: { authorization: 'synthetic-session' }, getUserID: () => h.uid,
    getAvatar: () => '', getPageSize: () => 20, getTotalPage: (n, size) => Math.ceil(n / size), canUseCommunity: () => true,
    getRequestErrorMessage: (r, f) => r && r.msg || f,
    sendRequest(row) { h.requests.push(row); return { abort() {} }; }, tips: text => h.tips.push(text) };
  global.getApp = () => h.app;
  global.wx = { getStorageSync: () => '', navigateTo: row => h.nav.push(row) };
  global.Page = row => { h.definition = row; };
  delete require.cache[CHAT]; require(CHAT);
  h.page = { ...h.definition, data: { ...JSON.parse(JSON.stringify(h.definition.data)), myId: 1, type: 2, conversationId: 10, loadState: 'ready' },
    setData(patch) { Object.assign(this.data, patch); } };
  h.message = { id: 5, senderId, msgType: 3, conversationId: 10, extraJson: JSON.stringify(card) };
  h.page.data.msgs = h.page.decorate([h.message]);
  t.after(() => h.page.onUnload());
  return h;
}
test('signup and record sheet explicitly separate generation-time record from current audit readback', () => {
  const wxml = fs.readFileSync(CHAT.replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(wxml, /发送时：报名成功/); assert.match(wxml, /title="生成时记录"/);
  assert.match(wxml, /当前审核记录/); assert.match(wxml, /查看当前原单/);
});
test('result plus controlled action has separate record and original navigation, without second detail GET', t => {
  const h = harness(t, { title: '审核未通过', result: result(), action: '/pages/square/detail/index?id=9' });
  h.page.onCardTap(event()); h.page.onCardTap(event());
  assert.equal(h.requests.length, 1); assert.equal(h.nav.length, 0);
  assert.equal(h.page.data.reviewResult.outcome, '生成时已驳回');
  h.requests[0].success({ code: 200, data: { task: { id: 77, status: 1 }, timeline: [] } });
  assert.equal(h.page.data.reviewDetail.statusLabel, '审核通过');
  assert.equal(h.page.data.reviewResult.outcome, '生成时已驳回');
  h.page.onCardBtn(event({ mode: 'original' })); h.page.onCardBtn(event({ mode: 'original' }));
  assert.equal(h.nav.length, 1); assert.equal(h.nav[0].url, '/pages/square/detail/index?id=9');
  assert.equal(h.requests.length, 1);
});
test('real producer without action only derives verified COMMUNITY_POST identity; other review types keep audit detail only', t => {
  const h = harness(t); assert.equal(h.page.data.msgs[0].currentOriginalUrl, '/pages/square/detail/index?id=9');
  h.page.data.msgs = h.page.decorate([{ ...h.message, extraJson: JSON.stringify({ title: '审核未通过', result: result({ bizType: 'TOPIC_PROJECT_REVIEW' }) }) }]);
  assert.equal(h.page.data.msgs[0].currentOriginalUrl, '');
  h.page.onCardTap(event()); assert.equal(h.requests[0].url, '/api/audit/77/detail');
  h.page.onCurrentCard(event()); assert.equal(h.nav.length, 0);
});
for (const sender of [null, false, '', ' ', 3, [], {}]) test('forged system sender cannot open record or arbitrary target: ' + JSON.stringify(sender), t => {
  const h = harness(t, { title: '伪造', result: result(), action: '/pages/square/detail/index?id=9' }, sender);
  h.page.onCardTap(event({ url: '/pages/privacy/index' }));
  assert.equal(h.page.data.reviewResult, null); assert.equal(h.requests.length, 0); assert.equal(h.nav.length, 0);
});
for (const action of ['/pages/privacy/index', '//evil.invalid/x', 'https://evil.invalid', '/pages/square/detail/index?id=10',
  '/pages/square/detail/index?id=9&redirect=x', '/pages/square/detail/index?id=9&id=10', '/pages/square/detail/index?id=%39']) {
  test('reject unbound or ambiguous action: ' + action, t => {
    const h = harness(t, { title: '审核未通过', result: result(), action });
    assert.equal(h.page.data.msgs[0].currentOriginalUrl, ''); h.page.onCurrentCard(event()); assert.equal(h.nav.length, 0);
  });
}
test('raw dataset URL and button action are never navigation authority', t => {
  const h = harness(t, { title: '普通通知', action: '/pages/privacy/index', buttons: [{ text: '打开', action: '/pages/privacy/index' }] });
  assert.equal(h.page.data.msgs[0].cardTarget, false);
  h.page.onCardTap(event({ url: '/pages/privacy/index', bcid: 2 }));
  h.page.onCardBtn(event({ index: 0, action: '/pages/privacy/index' }));
  assert.equal(h.nav.length, 0); assert.equal(h.requests.length, 0); assert.deepEqual(h.tips, []);
});
test('button uses current message identity and actual stored button, never injected action', t => {
  const action = '/pages/square/detail/index?id=9';
  const h = harness(t, { title: '审核未通过', result: result(), action, buttons: [{ text: '查看', action }] });
  h.page.onCardBtn(event({ index: 0, action: '/pages/privacy/index' }));
  assert.equal(h.nav.length, 1); assert.equal(h.nav[0].url, action);
});
test('removed current-message projection closes snapshot and ignores old audit response', t => {
  const h = harness(t); h.page.onCardTap(event());
  h.page.data.msgs = h.page.decorate([{ ...h.message, extraJson: JSON.stringify({ title: '当前不可查看' }) }]);
  h.requests[0].success({ code: 200, data: { task: { id: 77, status: 1 }, timeline: [] } });
  assert.equal(h.page.data.reviewResult, null); assert.equal(h.page.data.reviewDetail, null);
  h.page.onCurrentCard(event()); assert.equal(h.nav.length, 0);
});
test('same conversation rebuilt cannot accept old audit callback', t => {
  const h = harness(t); h.page.onCardTap(event()); const old = h.requests[0];
  h.page.onUnload(); h.page.loadMessages = () => {}; h.page.loadReportEntry = () => {};
  h.page.onLoad({ conversationId: '10', type: '2' });
  h.page.data.msgs = h.page.decorate([h.message]); h.page.onCardTap(event());
  old.success({ code: 200, data: { task: { id: 77, status: 2 }, timeline: [] } });
  assert.equal(h.page.data.reviewDetail.state, 'loading');
  h.requests.at(-1).success({ code: 200, data: { task: { id: 77, status: 1 }, timeline: [] } });
  assert.equal(h.page.data.reviewDetail.statusLabel, '审核通过');
});
test('owner change and wrong audit task receipt cannot replace current detail', t => {
  const h = harness(t); h.page.onCardTap(event());
  h.requests[0].success({ code: 200, data: { task: { id: 88, status: 1 }, timeline: [] } });
  assert.equal(h.page.data.reviewDetail.state, 'error');
  h.page.retryReviewDetail(); h.uid = 2;
  const before = JSON.stringify(h.page.data);
  h.requests.at(-1).success({ code: 200, data: { task: { id: 77, status: 1 }, timeline: [] } });
  assert.equal(JSON.stringify(h.page.data), before);
});

test('system sender in non-system conversation cannot authorize generic card navigation', t => {
  const h = harness(t); h.page.data.type = 1;
  h.page.onCardTap(event()); h.page.onCurrentCard(event());
  assert.equal(h.page.data.reviewResult, null); assert.equal(h.nav.length, 0); assert.equal(h.requests.length, 0);
});
for (const code of [403, 404]) test('real audit refusal ' + code + ' stays visible and non-retryable', t => {
  const h = harness(t); h.page.onCardTap(event());
  h.requests[0].success({ code, msg: '当前无法查看此审核记录' });
  assert.equal(h.page.data.reviewDetail.state, 'error'); assert.equal(h.page.data.reviewDetail.retryable, false);
  h.page.retryReviewDetail(); assert.equal(h.requests.length, 1);
  assert.equal(h.page.data.reviewResult.outcome, '生成时已驳回');
});
test('hide closes record and rejects late detail', t => {
  const h = harness(t); h.page.onCardTap(event()); h.page.onHide();
  h.requests[0].success({ code: 200, data: { task: { id: 77, status: 1 }, timeline: [] } });
  assert.equal(h.page.data.reviewResult, null); assert.equal(h.page.data.reviewDetail, null);
});
for (const body of ['null', '[]', 'true']) test('malformed card JSON cannot create navigation: ' + body, t => {
  const h = harness(t); h.page.data.msgs = h.page.decorate([{ ...h.message, extraJson: body }]);
  h.page.onCardTap(event({ url: '/pages/privacy/index' }));
  assert.equal(h.page.data.msgs[0].cardTarget, false); assert.equal(h.nav.length, 0);
});
const flush = () => new Promise(resolve => setImmediate(resolve));
for (const code of [200, 403]) test('original URL feeds real post Page and canonical consumer without bypassing read policy: ' + code, async t => {
  const h = harness(t); h.page.onCurrentCard(event());
  const url = new URL(h.nav[0].url, 'https://fixture.invalid');
  let definition; global.Page = row => { definition = row; };
  const file = require.resolve('../../pages/square/detail/index.js'); delete require.cache[file]; require(file);
  const original = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); } };
  original.onLoad({ id: url.searchParams.get('id') }); await flush();
  const read = h.requests.find(row => row.url === '/api/v1/community/posts/9');
  assert.ok(read); assert.equal(read.method, 'GET');
  read.success(code === 200 ? { code, data: { post: { id: 9, authorId: 1, body: '当前原帖', lifecycle: 'PUBLISHED', version: 2,
    viewerLiked: 0, viewerBookmarked: 0 }, media: [], references: [], viewerIsAuthor: true } } : { code, msg: '当前原帖不可查看' });
  const comments = h.requests.find(row => row.url === '/api/v1/community/posts/9/comments');
  if (comments) comments.success({ code: 200, data: { items: [], hasMore: false, nextCursor: null } });
  await flush();
  assert.equal(original.data.detailState, code === 200 ? 'ready' : 'error');
  if (code === 200) assert.equal(original.data.info.contents, '当前原帖');
  assert.ok(h.requests.every(row => row.method === 'GET'));
});

test('TOPIC content record binds exact topic id; project/sale review is never mapped by similar id', t => {
  const action = '/pages/topic/index/index?id=9';
  const h = harness(t, { title: '内容已下架', result: result({ bizType: 'TOPIC' }), action });
  h.page.onCurrentCard(event()); assert.equal(h.nav[0].url, action);
  for (const bizType of ['TOPIC_PROJECT_REVIEW', 'TOPIC_SALE_REVIEW', 'SQUARE', 'COMMUNITY_POST_COMMENT', 'COMMUNITY_POST_ENFORCEMENT']) {
    h.page.data.msgs = h.page.decorate([{ ...h.message, extraJson: JSON.stringify({ result: result({ bizType }), action }) }]);
    assert.equal(h.page.data.msgs[0].currentOriginalUrl, '');
  }
});
test('route and signup cards retain their fixed topic target despite arbitrary extra action', t => {
  const h = harness(t, { cardType: 'signup', topicId: 9, action: '/pages/privacy/index' });
  h.page.onRouteTap(event({ topicid: 9 })); assert.equal(h.nav[0].url, '/pages/topic/index/index?id=9');
});

test('TOPIC original passes the exact id to the existing topic consumer and preserves missing-record state', t => {
  const h = harness(t, { result: result({ bizType: 'TOPIC' }) }); h.page.onCurrentCard(event());
  const url = new URL(h.nav[0].url, 'https://fixture.invalid');
  let definition; global.Page = row => { definition = row; };
  const file = require.resolve('../../pages/topic/index/index.js'); delete require.cache[file]; require(file);
  const original = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); }, getSystemInfo() {}, calculateScreenHeights() {},
    _initWorkflow() {}, _loadSignupProfile() {} };
  // Layout measurement timer is outside this read-only consumer seam.
  const timer = global.setTimeout; global.setTimeout = () => 0;
  try { original.onLoad({ id: url.searchParams.get('id') }); } finally { global.setTimeout = timer; }
  const read = h.requests.find(row => row.url === '/api/topic/info-to-user');
  assert.ok(read); assert.equal(read.data.id, 9);
  read.success({ code: 404, msg: '主题暂不可查看' });
  assert.equal(original.data.loadError, true); assert.equal(original.data.topicLoaded, false);
});
test('physical HTTP403 audit response preserves non-retryable denial', t => {
  const h = harness(t); h.page.onCardTap(event());
  h.requests[0].successStatusAbnormal({ msg: '无权查看该记录' }, 403);
  assert.equal(h.page.data.reviewDetail.retryable, false);
  assert.equal(h.page.data.reviewDetail.error, '无权查看该记录');
});

for (const bizType of [['TOPIC'], { type: 'COMMUNITY_POST' }, null, true]) test('business type must be the actual enum string: ' + JSON.stringify(bizType), t => {
  const h = harness(t, { result: result({ bizType }) });
  assert.equal(h.page.data.msgs[0].currentOriginalUrl, ''); h.page.onCurrentCard(event());
  assert.equal(h.nav.length, 0);
});
