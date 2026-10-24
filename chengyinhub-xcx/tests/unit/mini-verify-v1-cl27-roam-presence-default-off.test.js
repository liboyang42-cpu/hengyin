'use strict';
// CL-27(F-L5C-01):漫游「附近的人」默认关闭(客户端一侧)。
// 服务端 ApiRoamController.changePresenceVisibility 没有单独的 roam-presence-v1 同意就拒绝开启;
// 这里钉客户端:页面初始不分享、读不到服务端偏好按关闭处理、不点「同意并开启」不发开启请求,
// 关闭态下不拉附近的人列表;只有单独同意后服务端回读为 true 才显示为开启。
// 负控:把 pages/roam/index.js 的 presenceVisible 默认值改成 true,或让 togglePresenceSharing 跳过确认弹窗,本文件必须真红。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const settle = () => new Promise((resolve) => setImmediate(resolve));

const ROAM = '../../pages/roam/index.js';

function harness(respond) {
  const h = { requests: [], modals: [], toasts: [] };
  const app = {
    globalData: { user_id: 9, features: {} }, getUserID: () => 9, isDevEnv: () => false,
    sendRequest(options) { h.requests.push(options); if (respond) respond(options); },
  };
  global.getApp = () => app;
  global.wx = {
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast: (v) => h.toasts.push(v.title), showModal: (o) => h.modals.push(o),
    showLoading() {}, hideLoading() {},
  };
  delete require.cache[require.resolve(ROAM)];
  let definition;
  global.Page = (value) => { definition = value; };
  require(ROAM);
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); },
  });
  page._syncMarkers = () => {};
  h.page = page;
  return h;
}

const presenceCalls = (h) => h.requests.filter((r) => r.url === '/api/roam/presence-visibility');

test('CL-27 附近的人:页面初始即不分享;没有会话或读失败都按关闭处理', async () => {
  const fresh = harness();
  assert.equal(fresh.page.data.presenceVisible, false, '默认必须关闭');
  assert.equal(await fresh.page._syncPresencePrivacy(), false, '还没开始漫游(无会话)按关闭');
  assert.equal(presenceCalls(fresh).length, 0);

  const failing = harness((o) => o.fail && o.fail({ errMsg: 'request:fail' }));
  failing.page._roamSid = 31;
  failing.page.data.presenceVisible = true;
  assert.equal(await failing.page._syncPresencePrivacy(), false);
  assert.equal(failing.page.data.presenceVisible, false, '读不到服务端偏好不得保持/假定开启');
});

test('CL-27 附近的人:不点「同意并开启」不发开启请求;关闭态不拉附近的人', async () => {
  const h = harness();
  h.page._roamSid = 31;
  h.page.togglePresenceSharing();
  assert.equal(h.modals.length, 1, '开启前必须单独弹出位置分享同意');
  assert.match(h.modals[0].content, /默认关闭/);
  assert.equal(presenceCalls(h).length, 0, '弹窗未确认前不得发任何开启请求');
  h.modals[0].success({ confirm: false, cancel: true });
  await settle();
  assert.equal(presenceCalls(h).length, 0);
  assert.equal(h.page.data.presenceVisible, false);

  h.page._fetchRunners({ lat: 31.2, lng: 121.4 });
  assert.equal(h.requests.filter((r) => r.url === '/api/roam/nearby-runners').length, 0, '关闭态不得拉附近的人');
});

test('CL-27 附近的人:单独同意后带 roam-presence-v1 开启,服务端回读为 true 才显示开启', async () => {
  const h = harness((o) => {
    if (o.url !== '/api/roam/presence-visibility') return;
    o.success({ code: 200, data: true });
  });
  h.page._roamSid = 31;
  h.page.togglePresenceSharing();
  h.modals[0].success({ confirm: true });
  await settle(); await settle();
  const calls = presenceCalls(h);
  assert.equal(calls[0].method, 'POST');
  assert.equal(calls[0].data.visible, true);
  assert.equal(calls[0].data.consentVersion, 'roam-presence-v1');
  assert.equal(calls[1].method, 'GET', '开启后必须刷新服务端事实');
  assert.equal(h.page.data.presenceVisible, true);
});
