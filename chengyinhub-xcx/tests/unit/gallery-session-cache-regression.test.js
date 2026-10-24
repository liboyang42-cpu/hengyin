'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const filename = path.resolve(__dirname, '../../pages/merchant/decor/gallery/index.js');
function mount(owner = '7', authorization = 'token7') {
  let definition, pick;
  const requests = [], logins = [], timers = [], navigations = [];
  const app = { globalData: { authorization }, getUserID: () => owner,
    getAuthorization: () => app.globalData.authorization,
    sendRequest: request => requests.push(request), chooseImage: callback => { pick = callback; },
    firstLogin: () => new Promise(resolve => logins.push(resolve)),
    reLogin: () => new Promise(resolve => logins.push(resolve)) };
  const toast = Object.assign(() => {}, { success() {} });
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    Page: value => { definition = value; }, getApp: () => app,
    wx: { getSystemInfoSync: () => ({ statusBarHeight: 20 }),
      navigateBack: () => navigations.push('back'), redirectTo: value => navigations.push(value.url) },
    getCurrentPages: () => [{}, {}], setTimeout: callback => { timers.push(callback); return timers.length; }, clearTimeout() {},
    require: name => name.endsWith('toast.js') ? toast : name.endsWith('merchant-theme.js')
      ? { merchantPageShow() {}, merchantPageRestore() {} } : require(path.resolve(path.dirname(filename), name)),
  });
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); },
    selectComponent() { return { check() {} }; } };
  return { page, app, requests, logins, timers, navigations,
    identity(id, token = id ? 'token' + id : '') { owner = id; app.globalData.authorization = token; },
    picked(images) { pick(images); } };
}
function ready(h, pictures = ['A.jpg']) {
  h.page.onLoad(); h.page.onShow();
  assert.equal(h.requests.length, 1);
  h.requests[0].success({ code: 200, data: { memberId: 7, gallery: JSON.stringify(pictures) } });
}
const pics = h => Array.from(h.page.data.gallery);
test('游客相册不读私有资料，页面有正常登录恢复入口', () => {
  const h = mount('', ''); h.page.onLoad(); h.page.onShow();
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.loadState, 'login');
  const wxml = fs.readFileSync(filename.replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(wxml, /loadState === 'login'[\s\S]*?cta="去登录"[^>]*bind:cta="onLogin"/);
});
test('相册已加载后换号返回，清旧图片和编辑结果再读当前账号', () => {
  const h = mount(); ready(h); h.page.data.dirty = true;
  h.page.onHide(); h.identity('8'); h.page.onShow();
  assert.deepEqual(pics(h), []); assert.equal(h.page.data.dirty, false);
  assert.equal(h.requests.length, 2); assert.equal(h.page.data.loadState, 'loading');
});
test('退出和同账号token更换也清旧图片，退出不继续读', () => {
  for (const [owner, token] of [['', ''], ['7', 'replacement']]) {
    const h = mount(); ready(h); h.page.data.dirty = true;
    h.identity(owner, token); h.page.onShow();
    assert.deepEqual(pics(h), []); assert.equal(h.page.data.dirty, false);
    assert.equal(h.requests.length, owner ? 2 : 1);
  }
});
test('显示期间身份改变也不能用旧相册保存或编辑', () => {
  for (const action of ['save', 'addGallery', 'removeGallery']) {
    const h = mount(); ready(h); h.page.data.dirty = true; h.identity('8');
    h.page[action]({ currentTarget: { dataset: { index: 0 } } });
    assert.equal(h.requests.filter(r => r.url === '/api/merchant/decor/save').length, 0);
    assert.deepEqual(pics(h), []); assert.equal(h.page.data.dirty, false);
  }
});
test('旧账号成功回包和传输层sessionChanged都不能保留旧资料', () => {
  for (const success of [true, false]) {
    const h = mount(); h.page.onLoad(); h.page.onShow(); h.identity('8');
    if (success) h.requests[0].success({ code: 200, data: { memberId: 7, gallery: '["A.jpg"]' } });
    else h.requests[0].fail({ reason: 'sessionChanged' });
    assert.deepEqual(pics(h), []); assert.equal(h.requests.length, 2);
  }
});
test('同一会话普通刷新500或网络失败保留合法图片和未保存编辑', () => {
  for (const error of [{ code: 500 }, { errMsg: 'request:fail timeout' }]) {
    const h = mount(); ready(h); h.page.data.gallery.push('edit.jpg'); h.page.data.dirty = true;
    h.page.load(); h.requests[1].fail(error);
    assert.deepEqual(pics(h), ['A.jpg', 'edit.jpg']); assert.equal(h.page.data.dirty, true);
    assert.equal(h.page.data.refreshing, false); assert.equal(h.page.data.loadState, 'ready');
  }
});
test('相册成功码但null内容结束初读加载，不抛异常或伪造可编辑状态', () => {
  const h = mount(); h.page.onLoad(); h.page.onShow();
  assert.doesNotThrow(() => h.requests[0].success({ code: 200, data: null }));
  assert.equal(h.page.data.loadState, 'error'); assert.equal(h.page.data.refreshing, false);
  assert.deepEqual(pics(h), []);
});
test('相册null刷新结束refreshing并保留同会话合法图片和dirty编辑', () => {
  const h = mount(); ready(h); h.page.data.gallery.push('edit.jpg'); h.page.data.dirty = true;
  h.page.load(); assert.doesNotThrow(() => h.requests[1].success({ code: 200, data: null }));
  assert.deepEqual(pics(h), ['A.jpg', 'edit.jpg']); assert.equal(h.page.data.dirty, true);
  assert.equal(h.page.data.loadState, 'ready'); assert.equal(h.page.data.refreshing, false);
});
test('本人读取成功前不允许直接提交拼接的相册状态', () => {
  const h = mount(); h.page.onLoad(); h.page.onShow();
  Object.assign(h.page.data, { loadState: 'ready', dirty: true, gallery: ['unverified.jpg'] });
  h.page.save(); assert.equal(h.requests.filter(r => r.url.endsWith('/save')).length, 0);
});
test('隐藏后的旧读取不能回填，显示后重读且旧回包不能覆盖新请求', () => {
  const h = mount(); h.page.onLoad(); h.page.onShow(); h.page.onHide();
  h.requests[0].success({ code: 200, data: { memberId: 7, gallery: '["late.jpg"]' } });
  assert.deepEqual(pics(h), []); h.page.onShow(); assert.equal(h.requests.length, 2);
  h.requests[0].success({ code: 200, data: { memberId: 7, gallery: '["stale.jpg"]' } });
  assert.deepEqual(pics(h), []);
});
test('普通hide/show不抹掉同一会话未保存的图片', () => {
  const h = mount(); ready(h); h.page.data.gallery.push('edit.jpg'); h.page.data.dirty = true;
  h.page.onHide(); h.page.onShow();
  assert.deepEqual(pics(h), ['A.jpg', 'edit.jpg']); assert.equal(h.page.data.dirty, true);
  assert.equal(h.requests.length, 1);
});
test('旧保存成功/失败和旧成功导航计时器不能影响新账号', () => {
  for (const kind of ['success', 'fail', 'timer']) {
    const h = mount(); ready(h); h.page.data.dirty = true; h.page.save();
    if (kind === 'timer') h.requests[1].success({ code: 200 });
    h.page.onHide(); h.identity('8'); h.page.onShow();
    const before = JSON.stringify(h.page.data);
    if (kind === 'success') h.requests[1].success({ code: 200 });
    else if (kind === 'fail') h.requests[1].fail({ code: 500 });
    else h.timers.forEach(callback => callback());
    assert.equal(JSON.stringify(h.page.data), before); assert.equal(h.navigations.length, 0);
  }
});
test('同一账号隐藏时保存完成不改页面，返回后恢复真实结果而不永久saving', () => {
  const h = mount(); ready(h); h.page.data.dirty = true; h.page.save(); h.page.onHide();
  h.page.onShow(); h.page.load(); assert.equal(h.requests.length, 2, '保存尚未结束不得刷新覆盖编辑或发第二次写入');
  h.page.onHide();
  const before = JSON.stringify(h.page.data); h.requests[1].success({ code: 200 });
  assert.equal(JSON.stringify(h.page.data), before); h.page.onShow();
  assert.equal(h.page.data.saving, false); assert.equal(h.page.data.dirty, false);
  assert.match(h.page.data.saveReceipt, /已保存/);
});
test('图片选择迟回不能跨身份或隐藏写进相册', () => {
  for (const action of ['identity', 'hide']) {
    const h = mount(); ready(h); h.page.addGallery();
    if (action === 'identity') h.identity('8'); else h.page.onHide();
    h.picked(['late.jpg']); assert.ok(!pics(h).includes('late.jpg'));
  }
});
test('正常图片选择经过同账号hide/show仍可回填，跨会话代次不可回填', () => {
  const h = mount(); ready(h); h.page.addGallery(); h.page.onHide(); h.page.onShow();
  h.picked(['picked.jpg']); assert.deepEqual(pics(h), ['A.jpg', 'picked.jpg']);
  const p = mount(); ready(p); p.page.addGallery(); p.identity('8'); p.page.onShow();
  p.identity('7'); p.page.onShow(); p.requests.at(-1).success({ code: 200, data: { memberId: 7, gallery: '["current.jpg"]' } });
  p.picked(['stale.jpg']); assert.deepEqual(pics(p), ['current.jpg']);
});
test('保存401/code2/403撤权清旧编辑，网络失败仍保留本人编辑', () => {
  for (const code of [401, 2, 403]) {
    const h = mount(); ready(h); h.page.data.dirty = true; h.page.save();
    h.requests[1].successStatusAbnormal({}, code);
    assert.deepEqual(pics(h), []); assert.equal(h.page.data.dirty, false);
    assert.equal(h.page.data.loadState, code === 403 ? 'permission' : 'login');
  }
  const h = mount(); ready(h); h.page.data.dirty = true; h.page.save();
  h.requests[1].fail({ errMsg: 'request:fail timeout' });
  assert.deepEqual(pics(h), ['A.jpg']); assert.equal(h.page.data.dirty, true); assert.equal(h.page.data.saving, false);
});
test('正常登录单飞，失败保留入口，隐藏后迟到登录不能启动读取', async () => {
  const h = mount('', ''); h.page.onLoad(); h.page.onShow();
  h.page.onLogin(); h.page.onLogin(); assert.equal(h.logins.length, 1);
  h.page.onHide(); h.identity('7'); h.logins[0]({ ok: true });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(h.requests.length, 0);
  const p = mount('', ''); p.page.onLoad(); p.page.onShow(); p.page.onLogin(); p.logins[0]({ ok: false });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(p.page.data.loadState, 'login');
});
test('正常登录成功读取当前相册；401和code2清旧图后可恢复，403保持权限拒绝', async () => {
  const h = mount('', ''); h.page.onLoad(); h.page.onShow(); h.page.onLogin(); h.identity('7'); h.logins[0]({ ok: true });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(h.requests.length, 1);
  for (const code of [401, 2, 403]) {
    const p = mount(); ready(p); p.page.load(); p.requests[1].fail({ code });
    assert.deepEqual(pics(p), []); assert.equal(p.page.data.loadState, code === 403 ? 'permission' : 'login');
  }
});
