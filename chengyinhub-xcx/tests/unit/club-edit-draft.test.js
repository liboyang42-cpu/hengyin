const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const drafts = require('../../utils/club-edit-draft.js');
function setup() {
  const values = {}, requests = []; let member = 1, spec;
  const wx = { getStorageSync: k => values[k], setStorageSync: (k, v) => { values[k] = v; }, removeStorageSync: k => { delete values[k]; } };
  const app = { getUserID: () => member, getAuthorization: () => '', sendRequest: r => requests.push(r) };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../components/cy/scene-club-edit/index.js'), 'utf8'), {
    Component: c => { spec = c; }, getApp: () => app, wx, setTimeout() {},
    require: name => name.includes('club-edit-draft') ? drafts : Object.assign(() => {}, { success() {} }),
  });
  const c = { data: { ...JSON.parse(JSON.stringify(spec.data)), clubId: '9' }, ...spec.methods,
    setData(p, cb) { Object.assign(this.data, p); if (cb) cb(); }, triggerEvent() {} };
  const club = { id: 9, name: '俱乐部', city: '杭州', isOwner: true, updateTime: 'v1' };
  function load() { c.fetchDetail(); requests.pop().success({ code: 200, data: club }); }
  return { c, club, load, requests, wx, values, setMember: v => { member = v; } };
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
  write.success({ code: 200 });
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
