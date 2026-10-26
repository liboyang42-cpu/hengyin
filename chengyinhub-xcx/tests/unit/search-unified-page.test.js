const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const search = require('../../utils/discover-search');
const session = require('../../utils/session/login-reload');
const source = fs.readFileSync(path.join(__dirname, '../../pages/search2/result/index.js'), 'utf8');
function harness(owner = '7', community = true) {
  const requests = [], navigations = [];
  const app = { globalData: {}, getUserID: () => owner, canUseCommunity: () => community,
    getImgUrl: v => v || '', sendRequest: r => requests.push(r) };
  let definition;
  vm.runInNewContext(source, { getApp: () => app, Page: p => { definition = p; },
    wx: { navigateTo: v => navigations.push(v.url) }, setTimeout: () => 1, clearTimeout() {},
    require: p => p.includes('discover-search') ? search : p.includes('login-reload') ? session
      : p.includes('page-motion') ? { enter() {} } : p.includes('scene-entry') ? { openScene() {} } : () => {} });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(v, done) { Object.assign(this.data, v); if (done) done(); } });
  const unified = () => requests.filter(r => r.url === '/api/search/unified');
  return { page, requests, unified, navigations, owner: v => { owner = v; },
    settleOthers() { requests.filter(r => r.url !== '/api/search/unified').forEach(r => r.success({ code: 200, data: { rows: [], total: 0 } })); } };
}
function ok(request, rows, total) { request.success({ code: 200, data: { rows, total } }); }
function row(id, type = 'topic', cityCode = '310100') { return { id: String(id), type, title: type + id, cityCode }; }

test('actual Page uses unified page contract, preserves other endpoints, renders three types and opens canonical post', () => {
  const h = harness(); h.page.onLoad({ keyword: 'city', city_code: '310100' });
  const req = h.unified()[0]; assert.equal(req.data.pageNum, 1); assert.equal(req.data.city_code, '310100');
  assert.equal(req.data.types, 'topic,activity,post');
  assert.ok(h.requests.some(r => r.url === '/api/merchant/list'));
  assert.ok(!h.requests.some(r => r.url === '/api/topic/list'));
  ok(req, [row('9007199254740993'), row(1, 'activity'), row(1, 'post')], 3); h.settleOthers();
  assert.equal(h.page.data.visibleResults.length, 3); assert.equal(h.page.data.contentHasMore, false);
  const post = h.page.data.visibleResults.find(r => r.type === 'post');
  h.page.goResult({ currentTarget: { dataset: { path: post.path } } });
  assert.equal(h.navigations[0], '/pages/square/detail/index?id=1');
});

test('append failure retries original page, duplicate callbacks settle once and overlaps update facts', () => {
  const h = harness(); h.page.onLoad({ keyword: 'city' });
  ok(h.unified()[0], Array.from({ length: 12 }, (_, i) => row(i + 1)), 25); h.settleOthers();
  h.page.onReachBottom(); h.page.onReachBottom(); assert.equal(h.unified().length, 2);
  const second = h.unified()[1]; assert.equal(second.data.pageNum, 2); second.fail({ code: 500 });
  ok(second, [row(99)], 25); assert.equal(h.page.data.visibleResults.length, 12);
  h.page.loadMoreContent(); const retry = h.unified()[2]; assert.equal(retry.data.pageNum, 2);
  ok(retry, [row(12, 'topic', null), row(13, 'post')], 25);
  assert.equal(h.page.data.visibleResults.length, 13);
  assert.equal(h.page.data.visibleResults.find(r => r.key === 'topic:12').cityCode, null);
  h.page.loadMoreContent(); assert.equal(h.unified()[3].data.pageNum, 3);
  ok(h.unified()[3], [], 13); assert.equal(h.page.data.contentHasMore, false);
});

test('refresh replaces deleted results and current city rather than retaining old content on errors', () => {
  const h = harness(); h.page.onLoad({ keyword: 'city' }); ok(h.unified()[0], [row(1), row(2)], 2); h.settleOthers();
  h.page.runSearch(); assert.ok(!h.page.data.visibleResults.some(r => r.type === 'topic'));
  ok(h.unified()[1], [row(2, 'topic', '440100')], 1);
  assert.equal(h.page.data.visibleResults.length, 1); assert.equal(h.page.data.visibleResults[0].cityCode, '440100');
  h.page.onHide(); h.page.onShow(); h.unified()[2].fail({ code: 503 });
  assert.equal(h.page.data.visibleResults.length, 0); assert.match(h.page.data.contentError, /暂未开放/);
});

test('input changes, account changes, hidden and unloaded pages reject late responses', () => {
  const h = harness(); h.page.onLoad({ keyword: 'old' }); const old = h.unified()[0];
  h.page.onKeywordInput({ detail: { value: 'new' } }); ok(old, [row(1)], 1);
  assert.equal(h.page.data.visibleResults.length, 0);
  h.page.onSearchConfirm(); const next = h.unified()[1]; assert.equal(next.data.keyword, 'new');
  h.owner('8'); ok(next, [row(2)], 1); assert.equal(h.page.data.visibleResults.length, 0);
  const own = h.unified()[2]; h.page.onHide(); ok(own, [row(3)], 1);
  assert.equal(h.page.data.visibleResults.length, 0);
  h.page.onShow(); const shown = h.unified()[3]; h.page.onUnload(); ok(shown, [row(4)], 1);
  assert.equal(h.page.data.visibleResults.length, 0);
});

test('guest and unsupported filters retain existing searches; community gate excludes post without changing eligibility', () => {
  const guest = harness(''); guest.page.onLoad({ keyword: 'city' }); assert.equal(guest.unified().length, 0);
  assert.ok(guest.requests.some(r => r.url === '/api/topic/list'));
  const filtered = harness(); filtered.page.onLoad({ keyword: 'city', categoryId: '8' });
  assert.equal(filtered.unified().length, 0); assert.equal(filtered.requests[0].data.category_id, '8');
  const gated = harness('7', false); gated.page.onLoad({ keyword: 'city' });
  assert.equal(gated.unified()[0].data.types, 'topic,activity');
});

test('malformed totals and imprecise IDs fail the page without advancing, forbidden clears previous content', () => {
  const h = harness(); h.page.onLoad({ keyword: 'city' });
  ok(h.unified()[0], [row(1)], null); assert.ok(h.page.data.contentError);
  h.page.loadMoreContent(); assert.equal(h.unified()[1].data.pageNum, 1);
  ok(h.unified()[1], [{ id: 9007199254740992, type: 'post' }], 1); assert.ok(h.page.data.contentError);
  h.page.loadMoreContent(); ok(h.unified()[2], [row(1)], 20); h.settleOthers();
  h.page.loadMoreContent(); h.unified()[3].fail({ code: 403 });
  assert.equal(h.page.data.visibleResults.length, 0); assert.ok(h.page.data.contentError);
  h.page.loadMoreContent(); assert.equal(h.unified()[4].data.pageNum, 1);
});
