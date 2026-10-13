const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const drafts = require('../../utils/club-edit-draft.js');
function setup() {
  const values = {}, requests = [], uploads = []; let member = 1, spec;
  const wx = { getStorageSync: k => values[k], setStorageSync: (k, v) => { values[k] = v; }, removeStorageSync: k => { delete values[k]; } };
  const app = { chooseImage: cb => uploads.push(cb), getUserID: () => member, getAuthorization: () => '', sendRequest: r => requests.push(r) };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../components/cy/scene-club-edit/index.js'), 'utf8'), {
    Component: c => { spec = c; }, getApp: () => app, wx, setTimeout() {},
    require: name => name.includes('club-edit-draft') ? drafts : Object.assign(() => {}, { success() {} }),
  });
  const c = { data: { ...JSON.parse(JSON.stringify(spec.data)), clubId: '9' }, ...spec.methods, ...spec.lifetimes,
    setData(p, cb) { Object.assign(this.data, p); if (cb) cb(); }, triggerEvent() {} };
  const club = { id: 9, name: '俱乐部', city: '杭州', isOwner: true, profileVersion: 1, updateTime: 'v1' };
  function load() { c.fetchDetail(); requests.pop().success({ code: 200, data: club }); }
  return { c, club, load, requests, uploads, wx, values, setMember: v => { member = v; } };
}
test('草稿按账号和clubId隔离，匿名不保存，失败不假报成功', () => {
  const { wx } = setup();
  assert.notEqual(drafts.key(1, 9), drafts.key(2, 9));
  assert.notEqual(drafts.key(1, 9), drafts.key(1, 10));
  assert.equal(drafts.key('', 9), '');
  assert.equal(drafts.write(wx, '', '', {}), false);
  assert.equal(drafts.write({ setStorageSync() { throw Error('full'); } }, 'key', '', {}), false);
});
test('重新打开先提示，再由本人恢复；成功只清本对象草稿', () => {
  const s = setup(); s.load();
  s.c.onInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '新名字' } });
  s.wx.setStorageSync(drafts.key(2, 9), { keep: true });
  s.load(); assert.equal(s.c.data.name, '俱乐部'); assert.equal(s.c.data.draftAvailable, true);
  s.c.restoreDraft(); assert.equal(s.c.data.name, '新名字');
  s.c.save(); const pre = s.requests.pop(); assert.equal(pre.url, '/api/club/detail');
  pre.success({ code: 200, data: s.club });
  const write = s.requests.pop(); assert.equal(write.url, '/api/club/update-mine');
  const payload = JSON.parse(write.data);
  assert.equal(payload.expectedVersion, 1);
  write.success({ code: 200, data: { editRequestId: payload.editRequestId, profileVersion: 2 } });
  assert.equal(s.wx.getStorageSync(drafts.key(1, 9)), undefined);
  assert.deepEqual(s.wx.getStorageSync(drafts.key(2, 9)), { keep: true });
});
test('保存前检测别人更新，不提交旧资料，展示草稿与当前值', () => {
  const s = setup(); s.load(); s.c.onInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '我的修改' } });
  s.c.save(); s.requests.pop().success({ code: 200, data: { ...s.club, name: '他人修改', updateTime: 'v2' } });
  assert.equal(s.requests.length, 0); assert.equal(s.c.data.draftBlocked, true);
  assert.match(s.c.data.draftComparison, /我的修改.*他人修改/);
  s.c.save(); assert.equal(s.requests.length, 0);
});
test('坏档不静默删除，账号变化拒绝保存和恢复', () => {
  const s = setup(); s.wx.setStorageSync(drafts.key(1, 9), { damaged: true }); s.load();
  assert.equal(s.c.data.draftBlocked, true);
  assert.deepEqual(s.wx.getStorageSync(drafts.key(1, 9)), { damaged: true });
  s.setMember(2); s.c.discardDraft(); assert.ok(s.wx.getStorageSync(drafts.key(1, 9)));
  s.c.save(); assert.equal(s.requests.length, 0);
});
test('新草稿在保存失败或核对失败后仍保留', () => {
  const s = setup(); s.load(); s.c.onInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '未存' } });
  s.c.save(); s.requests.pop().fail();
  assert.equal(s.c.data.saving, false); assert.ok(s.wx.getStorageSync(drafts.key(1, 9)));
});

function writeRequest(s) {
  s.c.onInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '我的草稿' } });
  s.c.save(); s.requests.pop().success({ code: 200, data: s.club });
  return s.requests.pop();
}
test('预读后并发更新由409拒绝，草稿保留，重读并主动核对才使用新版本', () => {
  const s = setup(); s.load(); const write = writeRequest(s);
  write.success({ code: 409, msg: '版本冲突' });
  assert.equal(s.c.data.draftBlocked, true);
  assert.equal(s.wx.getStorageSync(drafts.key(1, 9)).form.name, '我的草稿');
  const latest = { ...s.club, profileVersion: 2, name: '另一编辑者' };
  s.requests.pop().success({ code: 200, data: latest });
  assert.match(s.c.data.draftComparison, /我的草稿.*另一编辑者/);
  s.c.reloadLatest(); s.requests.pop().success({ code: 200, data: latest });
  assert.equal(s.c.data.name, '另一编辑者');
  s.c.reconcileDraft(); assert.equal(s.c.data.name, '我的草稿');
  s.c.save(); s.requests.pop().success({ code: 200, data: latest });
  assert.equal(JSON.parse(s.requests.pop().data).expectedVersion, 2);
});
test('丢回包保留原请求，重开后只能原样核对，不能盲写或放弃未知结果', () => {
  const s = setup(); s.load(); const first = writeRequest(s); first.fail();
  assert.equal(s.c.data.pendingSave, true);
  s.c.discardDraft(); assert.ok(s.wx.getStorageSync(drafts.key(1, 9)).pending);
  s.load(); assert.equal(s.c.data.pendingSave, true);
  s.c.save(); assert.equal(s.requests.length, 0);
  s.c.confirmPendingSave(); const retry = s.requests.pop();
  assert.equal(retry.data, first.data);
  const payload = JSON.parse(retry.data);
  retry.success({ code: 200, data: { profileVersion: 2, editRequestId: payload.editRequestId } });
  assert.equal(s.wx.getStorageSync(drafts.key(1, 9)), undefined);
});
test('普通500和无法验证的200都保留待核对原请求，明确未写入才解除待办', () => {
  const s = setup(); s.load(); const write = writeRequest(s);
  write.success({ code: 500 }); assert.equal(s.c.data.pendingSave, true);
  s.c.confirmPendingSave(); s.requests.pop().success({ code: 200 });
  assert.ok(s.wx.getStorageSync(drafts.key(1, 9)).pending);
  s.c.confirmPendingSave(); s.requests.pop().success({ code: 500, msg: '明确拒绝', data: { saveRejected: true } });
  assert.equal(s.c.data.pendingSave, false);
  assert.equal(s.wx.getStorageSync(drafts.key(1, 9)).pending, undefined);
  assert.equal(s.c.data.name, '我的草稿');
});
test('原请求迟到回包不能清理切换账号后的草稿，确认请求也按账号隔离', () => {
  const s = setup(); s.load(); const write = writeRequest(s); const payload = JSON.parse(write.data);
  s.setMember(2); s.wx.setStorageSync(drafts.key(2, 9), { keep: true });
  write.success({ code: 200, data: { profileVersion: 2, editRequestId: payload.editRequestId } });
  assert.ok(s.wx.getStorageSync(drafts.key(1, 9)).pending);
  assert.deepEqual(s.wx.getStorageSync(drafts.key(2, 9)), { keep: true });
  s.c.confirmPendingSave(); assert.equal(s.requests.length, 0);
});
test('待办写盘失败不提交；无版本后端不降级到盲写', () => {
  const s = setup(); s.load();
  s.c.onInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '草稿' } });
  s.c.save(); const pre = s.requests.pop();
  s.wx.setStorageSync = () => { throw Error('full'); };
  pre.success({ code: 200, data: s.club }); assert.equal(s.requests.length, 0);
  assert.match(s.c.data.saveErrorText, /本次未提交/);
  s.c._profileVersion = undefined; s.c.save(); assert.equal(s.requests.length, 0);
  assert.match(s.c.data.saveErrorText, /版本暂不可用/);
});
test('管理员只提交展示资料，不提交主理人经营配置', () => {
  const s = setup(); s.club.isOwner = false; s.club.viewerIsAdmin = true; s.load();
  const payload = JSON.parse(writeRequest(s).data);
  assert.equal(payload.operationConfigUpdated, undefined);
  assert.equal(payload.joinPolicy, undefined);
  assert.equal(payload.memberReservedQuota, undefined);
  assert.equal(payload.expectedVersion, 1);
});

test('保存前启动的上传迟到不替换待办，卸载后回包不操作组件或草稿', () => {
  const s = setup(); s.load(); s.c.pickLogo(); const upload = s.uploads.pop();
  const write = writeRequest(s), payload = JSON.parse(write.data);
  upload(['late-logo']); assert.equal(s.c.data.logo, '');
  assert.equal(s.wx.getStorageSync(drafts.key(1, 9)).pending.editRequestId, payload.editRequestId);
  s.c.detached(); write.success({ code: 200, data: { profileVersion: 2, editRequestId: payload.editRequestId } });
  assert.ok(s.wx.getStorageSync(drafts.key(1, 9)).pending);
});
test('保存后实际出现的新修改仍留在草稿，不随旧回执清理或关闭', () => {
  const s = setup(); s.load(); const write = writeRequest(s), payload = JSON.parse(write.data);
  s.c.setData({ description: '后续修改' }); s.c.markDirty();
  assert.equal(s.wx.getStorageSync(drafts.key(1, 9)).pending.editRequestId, payload.editRequestId);
  write.success({ code: 200, data: { profileVersion: 2, editRequestId: payload.editRequestId } });
  assert.equal(s.wx.getStorageSync(drafts.key(1, 9)).form.description, '后续修改');
  assert.equal(s.c.data.draftBlocked, true); assert.equal(s.c.data.pendingSave, false);
});
test('native输入在保存或待核对时禁用，未确认请求不能被markDirty替换', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../components/cy/scene-club-edit/index.wxml'), 'utf8');
  const inputs = source.match(/<(?:input|textarea)\b[^>]*>/g);
  assert.ok(inputs.length); inputs.forEach(input => assert.match(input, /disabled="{{saving \|\| pendingSave \|\| draftAvailable \|\| draftBlocked}}"/));
});
test('保存草稿失败后遇到预读冲突，内存修改仍可核对，写盘恢复前重读不覆盖表单', () => {
  const s = setup(); s.load();
  const persist = s.wx.setStorageSync;
  s.wx.setStorageSync = () => { throw Error('full'); };
  s.c.onInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '未能写盘的草稿' } });
  s.c.save(); s.requests.pop().success({ code: 200, data: { ...s.club, profileVersion: 2, name: '他人修改' } });
  assert.match(s.c.data.draftComparison, /未能写盘的草稿.*他人修改/);
  s.c.reloadLatest(); assert.equal(s.requests.length, 0); assert.equal(s.c.data.name, '未能写盘的草稿');
  s.c.reconcileDraft(); assert.equal(s.c.data.draftBlocked, true);
  s.wx.setStorageSync = persist;
  s.c.reconcileDraft(); assert.equal(s.c.data.name, '未能写盘的草稿');
  assert.equal(s.c.data.draftBlocked, false);
  assert.equal(s.wx.getStorageSync(drafts.key(1, 9)).form.name, '未能写盘的草稿');
});
