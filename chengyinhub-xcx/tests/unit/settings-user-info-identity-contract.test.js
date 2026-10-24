const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../pages/shezhi/shezhi.js'), 'utf8');
function load(candidate = source) {
  let def;
  const requests = [], writes = [], storage = { role: 'club', user_type: 1, debug_user_view: '' };
  const app = { globalData: { user_id: 5, role: 'club', user_type: 1, authorization: 'session-a' },
    getUserID() { return this.globalData.user_id; },
    setUserRole(role) { writes.push(role); this.globalData.role = role; storage.role = role; },
    sendRequest(o) { requests.push(o); } };
  vm.runInNewContext(candidate, { getApp: () => app, Page: p => { def = p; }, wx: {
    getStorageSync: k => storage[k], reLaunch() {},
  }, require(id) {
    if (id.includes('im-send-recovery')) return require('../../utils/im-send-recovery.js');
    if (id.includes('identity-policy')) return require('../../utils/identity/identity-policy.js');
    if (id.includes('roleGuard')) return { isClubLeader: () => app.globalData.role === 'club', clear() {} };
    if (id.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} };
    if (id.includes('roam-player-memory')) return { clearStoragePreservingPlayerRoamMemory: () => true };
    return {};
  }});
  const page = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)), setData(p) { Object.assign(this.data, p); } });
  return { page, app, requests, writes, storage };
}
const reply = (req, role = 'merchant', name = 'current') => req.success({ code: 200, data: { role, name } });
for (const change of ['logout', 'different-user', 'same-user-new-session', 'role-change', 'hide', 'unload']) {
  test('设置旧资料响应不得回写：' + change, () => {
    const h = load(); h.page.getUserData(); const req = h.requests[0];
    if (change === 'logout') { h.page.onDangerConfirm({ detail: { key: 'account.logout' } }); h.requests[1].success({ code: 200 }); }
    if (change === 'different-user') h.app.globalData.user_id = 6;
    if (change === 'same-user-new-session') h.app.globalData.authorization = 'session-b';
    if (change === 'role-change') h.app.globalData.role = 'player';
    if (change === 'hide') h.page.onHide();
    if (change === 'unload') h.page.onUnload();
    const before = JSON.stringify(h.page.data); reply(req);
    assert.deepEqual(h.writes, []);
    if (change === 'logout') { assert.equal(h.page.data.settingsAuthRequired, true); assert.equal(Object.keys(h.page.data.userInfo).length, 0); }
    else assert.equal(JSON.stringify(h.page.data), before);
    assert.equal(h.storage.role, 'club');
  });
}
test('设置同账号乱序只接纳最新响应，保留debug用户展示', () => {
  const h = load(); h.storage.debug_user_view = 'user'; h.page.getUserData(); h.page.getUserData();
  reply(h.requests[1], 'merchant', 'new'); reply(h.requests[0], 'club', 'old');
  assert.deepEqual(h.writes, ['merchant']); assert.equal(h.page.data.userInfo.name, 'new');
  assert.equal(h.page.data.isMerchantView, false); assert.equal(h.storage.debug_user_view, 'user');
});
test('设置重返页面接受正常当前请求，畸形响应不写角色', () => {
  const h = load(); h.page.onHide(); h.page.onShow(); reply(h.requests[0], 'club');
  assert.deepEqual(h.writes, ['club']); h.page.getUserData(); h.requests[1].success({ code: 200, data: [] });
  assert.deepEqual(h.writes, ['club']);
});
test('设置正常load/show不重复请求，隐藏重开请求有效，失败退出不丢当前资料请求', () => {
  const h = load(); h.page.onLoad(); h.page.onShow(); assert.equal(h.requests.length, 1);
  h.page.onHide(); h.page.onShow(); assert.equal(h.requests.length, 2);
  reply(h.requests[0], 'merchant', 'hidden'); assert.deepEqual(h.writes, []);
  h.page.onDangerConfirm({ detail: { key: 'account.logout' } }); h.requests[2].fail();
  reply(h.requests[1], 'club', 'visible'); assert.deepEqual(h.writes, ['club']);
  assert.equal(h.page.data.userInfo.name, 'visible');
});

test('负控：移除回包身份/序号守卫会恢复旧身份污染', () => {
  const bad = source.replace(/      if \(epoch !== that\._userInfoEpoch[^\n]+\n      if \(requestedIdentity !== identity\(\)[\s\S]*?        return true;\n      }\n/, '');
  assert.notEqual(bad, source);
  const h = load(bad); h.page.getUserData(); h.app.globalData.user_id = 6; reply(h.requests[0]);
  assert.throws(() => assert.deepEqual(h.writes, []), assert.AssertionError);
});

test('游客设置显示登录入口且协议仍能打开，不请求本人资料', () => {
  const h = load(); h.app.globalData.user_id = ''; h.page.getUserData();
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.settingsAuthRequired, true);
  h.page.goUserAgreement(); assert.equal(h.page.data.showAgreementSheet, true); assert.deepEqual(h.writes, []);
});
test('设置真实401清私人资料并引导登录，403明确无权，不将500误判', () => {
  const h = load(); h.page.data.userInfo = { name: 'fixture-private' }; h.page.getUserData();
  h.requests[0].successStatusAbnormal({code:500},401);
  assert.equal(h.page.data.settingsAuthRequired, true); assert.equal(Object.keys(h.page.data.userInfo).length,0);
  h.page.getUserData(); h.requests[1].fail({statusCode:403}); assert.equal(h.page.data.settingsAccessDenied,true);
  const healthy=load(); healthy.page.getUserData(); reply(healthy.requests[0],'club','current'); healthy.page.getUserData();
  healthy.requests[1].successStatusAbnormal({code:500},500);
  assert.equal(healthy.page.data.settingsAuthRequired,false); assert.equal(healthy.page.data.settingsAccessDenied,false);
  assert.equal(healthy.page.data.userInfo.name,'current');
});
test('新身份读取开始即清旧资料，不用旧人姓名填新账号', () => {
  const h = load(); h.page.getUserData(); reply(h.requests[0],'club','old'); h.app.globalData.user_id=6;
  h.page.getUserData(); assert.equal(Object.keys(h.page.data.userInfo).length,0);
});
test('设置登录单飞，成功后回读，离页后的迟回不请求', async () => {
  const h=load(); h.app.globalData.user_id='';h.page.getUserData();let resolve,calls=0;
  h.app.firstLogin=()=>{calls++;return new Promise(r=>resolve=r);};h.page.onLoginSettings();h.page.onLoginSettings();
  assert.equal(calls,1);h.app.globalData.user_id=5;resolve({ok:true});await new Promise(r=>setImmediate(r));assert.equal(h.requests.length,1);
  const gone=load();gone.app.globalData.user_id='';gone.page.getUserData();let done;
  gone.app.firstLogin=()=>new Promise(r=>done=r);gone.page.onLoginSettings();gone.page.onUnload();gone.app.globalData.user_id=7;done({ok:true});
  await new Promise(r=>setImmediate(r));assert.equal(gone.requests.length,0);
});

test('设置登录初始化异常解除锁，仍可再次主动登录', async () => {
  const h=load();h.app.globalData.user_id='';h.app.firstLogin=()=>{throw new Error('fixture SDK unavailable');};
  assert.doesNotThrow(()=>h.page.onLoginSettings());assert.equal(h.page._settingsLoginPending,false);assert.equal(h.requests.length,0);
  h.app.firstLogin=()=>{h.app.globalData.user_id=7;return Promise.resolve({ok:true});};h.page.onLoginSettings();
  await new Promise(r=>setImmediate(r));assert.equal(h.requests.length,1);
});
