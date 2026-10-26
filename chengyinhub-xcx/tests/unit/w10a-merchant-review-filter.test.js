'use strict';
// W10a #20:商家口碑管理态的筛选与计数走服务端(/api/merchant/reviews/manage?filter=)。
// 真源:docs/delivery/release-closeout-20261022/private-groups.md(Lane G2)MISSING #20 ——
// 原来「全部/待回复/低分/有图」只在已加载那一页里过滤,计数也只覆盖已加载页。
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const ROOT = path.resolve(__dirname, '../..');
const model = require('../../pages/merchant/reviews/view-model.js');

function harness(mode = 'manage') {
  let definition;
  const requests = [];
  const app = { globalData: {}, getUserID: () => '7',
    getSessionManager: () => ({ ensureSession: () => new Promise(() => {}) }),
    sendRequest(options) { requests.push(options); },
    getRequestErrorMessage: (response, fallback) => response && response.msg || fallback };
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'pages/merchant/reviews/index.js'), 'utf8'), {
    Page(value) { definition = value; }, getApp: () => app,
    require(name) {
      if (name === './reply-recovery') return require('../../pages/merchant/reviews/reply-recovery.js');
      if (name.includes('view-model')) return model;
      if (name.includes('ensure-session')) return require('../../pages/merchant/utils/session/ensure-session.js');
      if (name.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} };
      if (name.includes('toast')) return {};
      if (name.includes('media-preview')) return { open() {} };
      throw new Error(name);
    }, wx: {}, getCurrentPages: () => [],
  });
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); } };
  page.data.mode = mode; page._pageNum = 1; page._merchantRowId = 12;
  return { page, requests };
}

const COUNTS = { all: 40, pending: 6, low: 3, photo: 9, platform: 2 };

function manageResponse(filter, items, extra) {
  return { code: 200, data: Object.assign({
    mode: 'manage', filter, pageNum: 1, pageSize: 20, total: items.length, hasMore: false,
    pendingReplyCount: 6, monthNewCount: 1, replyRatePct: 50, filterCounts: COUNTS,
    items: items.map((item) => Object.assign({
      version: 0, rating: 5, status: 'VISIBLE', imageUrls: [],
      verifiedRedemption: true, canReply: true, canReport: true,
    }, item)),
  }, extra || {}) };
}

function tap(page, filter) {
  page.setReviewFilter({ currentTarget: { dataset: { filter } } });
}

test('shapePage:服务端回显筛选必须等于本次请求;全量计数缺字段 → null,畸形 → 整页拒收', () => {
  const ok = model.shapePage(manageResponse('low', [{ id: 1, rating: 2 }]).data, 'manage', 1, 20, 'low');
  assert.deepEqual(ok.filterCounts, COUNTS);
  assert.equal(model.shapePage(manageResponse('low', []).data, 'manage', 1, 20, 'photo'), null,
    '切筛选后旧响应不能写进新 tab');
  const noCounts = manageResponse('all', []).data;
  delete noCounts.filterCounts;
  assert.equal(model.shapePage(noCounts, 'manage', 1, 20, 'all').filterCounts, null);
  for (const bad of [{ filterCounts: [] }, { filterCounts: Object.assign({}, COUNTS, { photo: '9' }) },
    { filterCounts: { all: 1, pending: 0, low: 0, photo: 0 } }]) {
    assert.equal(model.shapePage(manageResponse('all', [], bad).data, 'manage', 1, 20, 'all'), null, JSON.stringify(bad));
  }
  const reviewed = model.shapePage(manageResponse('platform', [{ id: 3, underPlatformReview: true }]).data,
    'manage', 1, 20, 'platform');
  assert.equal(reviewed.items[0].underPlatformReview, true);
  assert.equal(model.normalizeManageFilter('hidden'), 'all');
});

test('管理态切筛选:带 filter 重读第一页,列表与计数都用服务端结果,不再本地过滤', () => {
  const h = harness();
  h.page.loadReviews(false);
  assert.match(h.requests[0].url, /^\/api\/merchant\/reviews\/manage\?filter=all&pageNum=1&pageSize=20$/);
  h.requests[0].success(manageResponse('all', [{ id: 1, rating: 5 }, { id: 2, rating: 1 }], { total: 40, hasMore: true }));
  assert.equal(h.page.data.total, 40);
  assert.deepEqual(h.page.data.filterCounts, COUNTS);

  tap(h.page, 'platform');
  assert.equal(h.requests.length, 2);
  assert.match(h.requests[1].url, /filter=platform&pageNum=1/);
  assert.equal(h.page.data.pageState, 'ready', '切筛选保留页头，不整页骨架');
  assert.equal(h.page.data.filterLoading, true);
  assert.equal(h.page.data.filteredItems.length, 0, '旧筛选的条目不能留在新 tab 下');

  // 服务端说「平台复审中」有这两条,即便它们评分 5、无图,也照单显示 —— 筛选口径归服务端。
  h.requests[1].success(manageResponse('platform', [{ id: 8, underPlatformReview: true }, { id: 9, status: 'PENDING_REVIEW' }]));
  assert.deepEqual(h.page.data.filteredItems.map((item) => item.id), ['8', '9']);
  assert.equal(h.page.data.filterLoading, false);
  assert.equal(h.page.data.total, 40, '页头总数是全店口径，不是筛选后条数');
});

test('管理态筛选为空 → 列表内筛选空态,不是整页「还没有真实到店评价」', () => {
  const h = harness();
  h.page.loadReviews(false);
  h.requests[0].success(manageResponse('all', [{ id: 1 }]));
  tap(h.page, 'low');
  h.requests[1].success(manageResponse('low', []));
  assert.equal(h.page.data.pageState, 'ready');
  assert.equal(h.page.data.filteredItems.length, 0);

  const all = harness();
  all.page.loadReviews(false);
  all.requests[0].success(manageResponse('all', []));
  assert.equal(all.page.data.pageState, 'empty', '全部为空才是整页空态');
});

test('筛选快速切换:先发后到的旧筛选响应被丢弃', () => {
  const h = harness();
  h.page.loadReviews(false);
  h.requests[0].success(manageResponse('all', [{ id: 1 }]));
  tap(h.page, 'low');
  tap(h.page, 'photo');
  h.requests[2].success(manageResponse('photo', [{ id: 5, imageUrls: ['https://img/a.jpg'] }]));
  h.requests[1].success(manageResponse('low', [{ id: 4, rating: 1 }]));
  assert.equal(h.page.data.activeFilter, 'photo');
  assert.deepEqual(h.page.data.filteredItems.map((item) => item.id), ['5']);
});

test('公开态仍只筛已加载评价,不发管理接口,也不接受管理专属筛选', () => {
  const h = harness('public');
  h.page.loadReviews(false);
  h.requests[0].success({ code: 200, data: { mode: 'public', pageNum: 1, pageSize: 20, total: 2, hasMore: false,
    items: [
      { id: 1, version: 0, rating: 2, status: 'VISIBLE', imageUrls: [], verifiedRedemption: true, canReply: false, canReport: true },
      { id: 2, version: 0, rating: 5, status: 'VISIBLE', imageUrls: [], verifiedRedemption: true, canReply: false, canReport: true },
    ] } });
  tap(h.page, 'low');
  assert.equal(h.requests.length, 1);
  assert.deepEqual(h.page.data.filteredItems.map((item) => item.id), ['1']);
  tap(h.page, 'platform');
  assert.equal(h.page.data.activeFilter, 'low');
});

test('WXML:管理态五个筛选挂服务端计数,公开态不挂数字;复审中标签只在管理态', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/reviews/index.wxml'), 'utf8');
  assert.match(wxml, /data-filter="platform"[\s\S]*?平台复审中<block wx:if="\{\{filterCounts\}\}"> \{\{filterCounts\.platform\}\}<\/block>/);
  assert.match(wxml, /全部<block wx:if="\{\{mode === 'manage' && filterCounts\}\}"> \{\{filterCounts\.all\}\}<\/block>/);
  assert.match(wxml, /低分<block wx:if="\{\{mode === 'manage' && filterCounts\}\}"> \{\{filterCounts\.low\}\}<\/block>/);
  assert.match(wxml, /有图<block wx:if="\{\{mode === 'manage' && filterCounts\}\}"> \{\{filterCounts\.photo\}\}<\/block>/);
  assert.match(wxml, /wx:if="\{\{mode === 'manage' && item\.underPlatformReview && item\.status !== 'PENDING_REVIEW'\}\}"/);
});
