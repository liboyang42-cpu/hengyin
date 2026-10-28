const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const PAGE = path.resolve(__dirname, '../../pages/play/index.js');
const tick = () => new Promise(resolve => setImmediate(resolve));

function harness(patch = {}) {
  const dialogs = [], locations = [], requests = [], hints = [];
  let member = 7, token = 'real-test-session', definition;
  const app = { DEV_TOKEN: 'dev-token', getUserID: () => member, getAuthorization: () => token,
    sendRequest(options) { requests.push(options); } };
  global.getApp = () => app; global.getCurrentPages = () => [];
  global.Page = value => { definition = value; };
  global.wx = { showModal(options) { dialogs.push(options); }, getLocation(options) { locations.push(options); },
    nextTick(fn) { if (fn) fn(); }, showToast() {}, vibrateShort() {} };
  delete require.cache[PAGE]; require(PAGE);
  const page = { data: { ...definition.data, activityId: 100, lead: { exists: true }, ...patch }, setData() {} };
  // Unrelated lifecycle effects are outside this action's test; run the actual hide/unload fence.
  for (const [name, value] of Object.entries(definition)) if (typeof value === 'function') page[name] = () => {};
  // W8:账号守卫同时核对登录上下文(令牌+换代)，捕获函数是守卫的一部分，必须用真实现。
  for (const name of ['leadArrive', 'onHide', 'onUnload', '_playMember', '_boundPlayOwner', '_capturePlayLoginContext', '_isPlayAccountCurrent']) page[name] = definition[name];
  page._boundPlayOwner(); // 正常进页先绑定账号；不能把新增账号守卫替换成无返回值的noop。
  page.diegetic = text => hints.push(text); let refreshes = 0;
  page.loadTeamProgress = () => { refreshes++; };
  return { page, dialogs, locations, requests, hints, app, get refreshes() { return refreshes; },
    member(value) { member = value; }, token(value) { token = value; },
    confirm() { dialogs.at(-1).success({ confirm: true }); },
    position(value = { latitude: 31.23, longitude: 121.47 }) { locations.at(-1).success(value); } };
}

test('集合按钮单独说明用途，明确同意后才采本次GCJ02坐标并提交', async () => {
  const h = harness(); h.page._loc = { latitude: 1, longitude: 2 }; h.page.leadArrive();
  assert.equal(h.dialogs.length, 1); assert.equal(h.locations.length, 0); assert.equal(h.requests.length, 0);
  assert.match(h.dialogs[0].content, /本次签到/); assert.match(h.dialogs[0].content, /500米/); assert.match(h.dialogs[0].content, /60分钟/);
  h.confirm(); assert.equal(h.locations[0].type, 'gcj02'); assert.equal(h.locations[0].isHighAccuracy, true);
  h.position(); assert.equal(h.requests[0].url, '/api/club/lead/arrive');
  assert.deepEqual(h.requests[0].data, { activityId: 100, latitude: 31.23, longitude: 121.47 });
  h.requests[0].success({ code: 200, msg: '已到集合点' }); await tick();
  assert.equal(h.refreshes, 1); assert.deepEqual(h.hints, ['已到集合点']); assert.equal(h.page._leadArriving, false);
});

test('取消说明、拒定位和SDK缺失均不提交签到', () => {
  let h = harness(); h.page.leadArrive(); h.dialogs[0].success({ confirm: false });
  assert.equal(h.locations.length, 0); assert.equal(h.requests.length, 0); assert.equal(h.page._leadArriving, false);
  h = harness(); h.page.leadArrive(); h.confirm(); h.locations[0].fail({ errMsg: 'permission denied' });
  assert.equal(h.requests.length, 0); assert.match(h.hints[0], /未提交签到/);
  h = harness(); delete global.wx.getLocation; h.page.leadArrive(); h.confirm();
  assert.equal(h.requests.length, 0); assert.equal(h.page._leadArriving, false);
});

for (const value of [null, { latitude: NaN, longitude: 121 }, { latitude: 31, longitude: Infinity },
  { latitude: 91, longitude: 121 }, { latitude: 31, longitude: 181 }, { latitude: '31', longitude: '121' }]) {
  test(`非法SDK坐标不POST：${String(value && value.latitude)}/${String(value && value.longitude)}`, () => {
    const h = harness(); h.page.leadArrive(); h.confirm(); h.position(value); assert.equal(h.requests.length, 0);
  });
}

for (const stage of ['dialog', 'location', 'response']) for (const change of ['member', 'token', 'activity', 'hide', 'unload']) {
  test(`${stage}晚回包在${change}后不能提交或刷新旧场次`, async () => {
    const h = harness(); h.page.leadArrive();
    if (stage !== 'dialog') h.confirm();
    if (stage === 'response') h.position();
    if (change === 'member') h.member(8);
    if (change === 'token') h.token('renewed-or-switched-token');
    if (change === 'activity') h.page.data.activityId = 200;
    if (change === 'hide') { h.page.onHide(); h.page._themeHidden = false; }
    if (change === 'unload') h.page.onUnload();
    if (stage === 'dialog') h.confirm();
    if (stage === 'location') h.position();
    if (stage === 'response') { h.requests[0].success({ code: 200, msg: '旧场签到成功' }); await tick(); }
    assert.equal(h.requests.length, stage === 'response' ? 1 : 0);
    assert.equal(h.refreshes, 0); assert.equal(h.hints.length, 0);
  });
}

test('重复点击只产生一个在途用途确认，业务拒绝不可显示成功或刷新到场事实', async () => {
  const h = harness(); h.page.leadArrive(); h.page.leadArrive(); assert.equal(h.dialogs.length, 1);
  h.confirm(); h.position(); h.requests[0].success({ code: 500, msg: '请到集合点500米内签到' }); await tick();
  assert.equal(h.refreshes, 0); assert.deepEqual(h.hints, ['请到集合点500米内签到']); assert.equal(h.page._leadArriving, false);
});

test('网络失败明确结束在途状态，不伪造成功', async () => {
  const h = harness(); h.page.leadArrive(); h.confirm(); h.position(); h.requests[0].fail(); await tick();
  assert.equal(h.refreshes, 0); assert.match(h.hints[0], /网络异常/); assert.equal(h.page._leadArriving, false);
});

test('访客、演示身份、预览、mock和未指定场次不启动定位', () => {
  for (const patch of [{ isPreview: true }, { isMock: true }, { activityId: 0 }]) {
    const h = harness(patch); h.page.leadArrive(); assert.equal(h.locations.length, 0); assert.equal(h.requests.length, 0);
  }
  let h = harness(); h.member(0); h.page.leadArrive(); assert.equal(h.dialogs.length, 0);
  h = harness(); h.token('dev-token'); h.page.leadArrive(); assert.equal(h.dialogs.length, 0);
});

test('已绑定A的旧游玩页切为B，点击集合签到也不能启动用途说明或定位', () => {
  const h = harness(); h.member(8); h.page.leadArrive();
  assert.equal(h.dialogs.length, 0); assert.equal(h.locations.length, 0); assert.equal(h.requests.length, 0);
});
