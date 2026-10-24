'use strict';
// BL-204:商家核销记录/触达记录支持下拉刷新;已有列表时下拉不切骨架、失败只轻提示;过期回包丢弃;任何路径都收转圈。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const ROOT = path.resolve(__dirname, '..', '..');

function loadPage(name) {
  const requests = [];
  const stops = [];
  const toasts = [];
  let def;
  const prev = { getApp: global.getApp, Page: global.Page, wx: global.wx, getCurrentPages: global.getCurrentPages };
  global.getApp = () => ({ globalData: {}, sendRequest: (o) => requests.push(o), getRequestErrorMessage: (r, f) => f });
  global.Page = (d) => { def = d; };
  global.getCurrentPages = () => [{}];
  global.wx = {
    stopPullDownRefresh: () => stops.push(1),
    showToast: (o) => toasts.push(o && o.title),
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    setNavigationBarColor() {}, setBackgroundColor() {}, setBackgroundTextStyle() {},
  };
  const file = path.join(ROOT, 'pages/merchant', name, 'index.js');
  delete require.cache[require.resolve(file)];
  require(file);
  const p = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) });
  p.setData = (patch, cb) => { Object.assign(p.data, patch); if (cb) cb(); };
  const restore = () => Object.assign(global, prev);
  return { p, requests, stops, toasts, restore };
}

const PAGES = [
  { name: 'verify-records', perm: 'merchant:verify:record:read', listUrl: '/api/merchant/verification-records',
    row: (id, status) => ({ id, status: status == null ? 1 : status, verifyTime: '2026-10-18 10:00:00' }),
    count: (p) => p.data.groups.reduce((n, g) => n + ((g.items || g.rows || []).length), 0) },
  { name: 'crm-campaigns', perm: 'merchant:marketing:read', listUrl: '/api/merchant/crm/campaigns',
    row: (id) => ({ id, name: '任务' + id, status: 'COMPLETED', createTime: '2026-10-18 10:00:00' }),
    count: (p) => p.data.items.length },
];

function grantAccess(h, perm) {
  const r = h.requests.shift();
  assert.equal(r.url, '/api/merchant/access/me');
  r.success({ code: 200, data: { active: true, merchant: { id: 1, name: '店' }, roleCode: 'MERCHANT_OWNER', permissions: [perm] } });
}

for (const pg of PAGES) {
  test(`BL-204 ${pg.name}: index.json 开启下拉刷新`, () => {
    const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/merchant', pg.name, 'index.json'), 'utf8'));
    assert.equal(json.enablePullDownRefresh, true);
  });

  test(`BL-204 ${pg.name}: 已有列表时下拉静默重读,失败保留列表只轻提示`, () => {
    const h = loadPage(pg.name);
    try {
      h.p.onLoad();
      grantAccess(h, pg.perm);
      const first = h.requests.shift();
      assert.equal(first.url, pg.listUrl);
      first.success({ code: 200, data: [pg.row(1)] });
      assert.equal(h.p.data.pageState, 'ready');
      const before = pg.count(h.p);
      assert.ok(before >= 1);

      h.p.onPullDownRefresh();
      assert.equal(h.p.data.pageState, 'ready', '下拉不切回骨架');
      const pull = h.requests.shift();
      assert.equal(pull.url, pg.listUrl);
      pull.fail();
      assert.equal(h.stops.length, 1);
      assert.equal(h.p.data.pageState, 'ready');
      assert.equal(pg.count(h.p), before, '失败保留列表');
      assert.ok(h.toasts.includes('刷新失败，稍后再试'));

      h.p.onPullDownRefresh();
      h.requests.shift().success({ code: 200, data: [pg.row(1), pg.row(2)] });
      assert.equal(h.stops.length, 2);
      assert.equal(pg.count(h.p), before + 1, '下拉读到新记录');
    } finally { h.restore(); }
  });

  test(`BL-204 ${pg.name}: 过期回包丢弃,被取代的下拉仍收转圈`, () => {
    const h = loadPage(pg.name);
    try {
      h.p.onLoad();
      grantAccess(h, pg.perm);
      h.requests.shift().success({ code: 200, data: [pg.row(1)] });
      h.p.onPullDownRefresh();
      const pull = h.requests.shift();
      h.p.retry();
      const latest = h.requests.shift();
      latest.success({ code: 200, data: [pg.row(1), pg.row(2), pg.row(3)] });
      const fresh = pg.count(h.p);
      pull.success({ code: 200, data: [] });
      assert.equal(h.stops.length, 1, '过期的下拉回包也收转圈');
      assert.equal(pg.count(h.p), fresh, '过期回包不覆盖');
      assert.equal(h.p.data.pageState, 'ready');
    } finally { h.restore(); }
  });

  test(`BL-204 ${pg.name}: 失败态/无权限下拉走 retry 并收转圈`, () => {
    const h = loadPage(pg.name);
    try {
      h.p.onLoad();
      h.requests.shift().fail();
      assert.equal(h.p.data.pageState, 'error');
      h.p.onPullDownRefresh();
      assert.equal(h.stops.length, 1);
      assert.equal(h.requests.shift().url, '/api/merchant/access/me', '失败态走 retry');
      const h2 = loadPage(pg.name);
      try {
        h2.p.onLoad();
        h2.requests.shift().success({ code: 200, data: { active: true, merchant: { id: 1, name: '店' }, roleCode: 'MERCHANT_OWNER', permissions: [] } });
        assert.equal(h2.p.data.pageState, 'no-permission');
        h2.p.onPullDownRefresh();
        assert.equal(h2.stops.length, 1);
        assert.equal(h2.requests.shift().url, '/api/merchant/access/me', '无权限重新判权');
      } finally { h2.restore(); }
    } finally { h.restore(); }
  });
}
