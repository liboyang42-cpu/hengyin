'use strict';
// 第十二轮 k8 F13:票夹卡面不再拿通用路线示意图(五点假路线 + 灰色骨架条)充当封面,
// 没在取码时也不在背面留一句永远不会填上的「生成中…」。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('../helpers/ui-sandbox-vm.js');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

function setPath(target, dotted, value) {
  const parts = dotted.replace(/\[(\d+)\]/g, '.$1').split('.');
  let cursor = target;
  for (let i = 0; i < parts.length - 1; i += 1) cursor = cursor[parts[i]] || (cursor[parts[i]] = {});
  cursor[parts.at(-1)] = value;
}

function loadPage() {
  const sandbox = { requests: [] };
  const app = {
    getImgUrl: (n) => '/images/' + n, getPageSize: () => 10,
    getTotalPage: (t, s) => Math.ceil(Number(t || 0) / s),
    getRequestErrorMessage: (r, f) => (r && r.msg) || f, getUserID: () => '1001',
    sendRequest(o) { sandbox.requests.push(o); },
  };
  const context = {
    getApp: () => app, Page(c) { sandbox.config = c; }, setTimeout() { return 1; }, clearTimeout() {},
    wx: { navigateTo() {}, showToast() {} }, console,
    require(request) {
      if (request === '../../utils/play-engine.js') return require('../../utils/play-engine.js');
      if (request.endsWith('/utils/session/login-reload.js')) return require('../../utils/session/login-reload.js');
      if (request.endsWith('/utils/ticket-expiry.js')) return require('../../utils/ticket-expiry.js');
      throw new Error('unexpected require: ' + request);
    },
  };
  vm.createContext(context);
  vm.runInContext(read('subpackageMember/signup/index.js'), context, { filename: 'subpackageMember/signup/index.js' });
  const page = Object.assign({}, sandbox.config);
  page.data = JSON.parse(JSON.stringify(sandbox.config.data));
  page.setData = (patch, cb) => { Object.entries(patch).forEach(([k, v]) => setPath(page.data, k, v)); if (cb) cb(); };
  page._walletOwner = app.getUserID();
  return page;
}

test('F13:没封面的票不出通用路线示意图', () => {
  const page = loadPage();
  page._topic = { list: [{ id: 80, ownerType: 1, registrationStatus: 2, paymentStatus: 2, verificationStatus: 0,
    expiresAt: '2099-12-06 00:00:00', cmsTopic: { id: 990050, name: 'd20', productType: 1 } }], state: 'ready' };
  page._activity = { list: [], state: 'empty' };
  page.rebuildTicketList();
  assert.equal(page.data.walletState, 'ready');
  assert.equal(page.data.ticketList[0].wImg, '');
  assert.doesNotMatch(read('subpackageMember/signup/index.js'), /route_city_cover/);
});

test('F13:卡面封面块只在有图时挂;「生成中…」只在取码中出现', () => {
  const wxml = read('subpackageMember/signup/index.wxml').replace(/<!--[\s\S]*?-->/g, '');
  assert.match(wxml, /<view class="wc__im" wx:if="\{\{item\.wImg\}\}">/);
  assert.match(wxml, /<text wx:elif="\{\{qrState === 'loading'\}\}" class="wc__qr-tip">生成中…<\/text>/);
  assert.doesNotMatch(wxml, /<text wx:else class="wc__qr-tip">生成中…/);
});
