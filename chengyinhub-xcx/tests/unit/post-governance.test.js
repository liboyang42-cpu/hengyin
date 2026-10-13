const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function mount(storage = new Map()) {
  let definition, prompt, owner = '1';
  const requests = [];
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../pages/shezhi/components/post-governance/index.js'), 'utf8'), {
    wx: { getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => storage.delete(key) },
    Component: d => { definition = d; }, getApp: () => ({ getUserID: () => owner, sendRequest: r => requests.push(r) }),
    require: () => ({ show: options => { prompt = options; } }),
  });
  const page = { ...definition.methods, data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch); } };
  return { page, requests, prompt: () => prompt, switchOwner: () => { owner = '2'; } };
}
const event = { currentTarget: { dataset: { id: '7' } } };
test('处置记录只读本人原案，形状错误不冒充空，换身份迟到响应丢弃', () => {
  const h = mount(); h.page.load();
  assert.equal(h.requests[0].url, '/api/v1/community/me/enforcements');
  h.requests[0].success({ code: 200, data: {} });
  assert.equal(h.page.data.state, 'error');
  h.page.load(); h.switchOwner();
  h.requests[1].success({ code: 200, data: { items: [{ id: 7 }] } });
  assert.equal(h.page.data.rows.length, 0);
});
test('文字申诉保持原处置 ID，断网不解除处置或重复提交，读取原申诉后才恢复', () => {
  const h = mount(); h.page.load();
  h.requests[0].success({ code: 200, data: { items: [{ id: 7, status: 'ACTIVE', reason_code: '说明' }] } });
  h.page.appeal(event); h.prompt().success({ confirm: true, content: '我的理由' });
  const payload = JSON.parse(h.requests[1].data);
  assert.equal(payload.enforcementId, '7'); assert.equal(payload.evidenceAssetIds.length, 0);
  h.requests[1].fail();
  assert.equal(h.page.data.unknownId, '7'); assert.equal(h.page.data.rows[0].status, '处置未撤销');
  h.page.appeal(event); assert.equal(h.requests.length, 2);
  h.page.load();
  h.requests[2].success({ code: 200, data: { items: [{ id: 7, status: 'ACTIVE', appeal_id: 9, appeal_status: 'SUBMITTED' }] } });
  assert.equal(h.page.data.unknownId, ''); assert.equal(h.page.data.rows[0].appealStatus, '申诉已提交');
});
test('申诉成功后回读真实原案，空回读保留待确认', () => {
  const h = mount(); h.page.load();
  h.requests[0].success({ code: 200, data: { items: [{ id: 7 }] } });
  h.page.appeal(event); h.prompt().success({ confirm: true, content: '理由' });
  h.requests[1].success({ code: 200 });
  assert.equal(h.requests[2].method, 'GET');
  h.requests[2].success({ code: 200, data: { items: [] } });
  assert.equal(h.page.data.unknownId, '7');
});


test('申诉裁决沿用原案 UPHELD/REVERSED，不另造审核状态', () => {
  const h = mount(); h.page.load();
  h.requests[0].success({ code: 200, data: { items: [
    { id: 7, status: 'REVERSED', appeal_id: 9, appeal_status: 'REVERSED' },
    { id: 8, status: 'ACTIVE', appeal_id: 10, appeal_status: 'UPHELD' }
  ] } });
  assert.equal(h.page.data.rows[0].status, '原处置已撤销');
  assert.equal(h.page.data.rows[0].appealStatus, '原处置已撤销');
  assert.equal(h.page.data.rows[1].appealStatus, '维持原处置');
});


test('申诉刷新恢复原处置待确认，其他身份不继承且不保存理由', () => {
  const storage = new Map(); const h = mount(storage); h.page.load();
  h.requests[0].success({ code: 200, data: { items: [{ id: 7, status: 'ACTIVE' }] } });
  h.page.appeal(event); h.prompt().success({ confirm: true, content: 'private reason' });
  assert.equal(JSON.stringify([...storage.values()]).includes('private reason'), false);
  const restored = mount(storage); restored.page.load();
  assert.equal(restored.page.data.unknownId, '7');
  restored.requests[0].success({ code: 200, data: { items: [{ id: 7, status: 'ACTIVE' }] } });
  restored.page.appeal(event); assert.equal(restored.requests.length, 1);
  restored.page.load(); restored.requests[1].success({ code: 200, data: { items: [{ id: 7, appeal_id: 9 }] } });
  assert.equal(storage.size, 0);
  const other = mount(storage); other.switchOwner(); other.page.load(); assert.equal(other.page.data.unknownId, '');
});


test('未知处置和申诉枚举不继承对象原型标签', () => {
  const h = mount(); h.page.load();
  h.requests[0].success({ code: 200, data: { items: [{ id: 7, status: 'constructor', appeal_id: 9, appeal_status: 'toString' }] } });
  assert.equal(h.page.data.rows[0].status, '处置状态待确认');
  assert.equal(h.page.data.rows[0].appealStatus, '申诉状态待确认');
});
