const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function card(name) {
  let def;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../components/cy', name, 'index.js'), 'utf8'), { Component: value => { def = value; } });
  const instance = { data: { ...def.data }, setData(patch) { Object.assign(this.data, patch); } };
  return { def, instance };
}
test('俱乐部展示区分受众，空值不编数量、保留零，去重且不截掉能力', () => {
  const { def, instance } = card('club-card');
  const update = (c, audience) => def.observers['club, audience'].call(instance, c, audience);
  const club = { name: '长名称', clubType: 'City Walk', keywords: '["城市考古","夜骑","City Walk"]', memberCount: 0, topicCount: null, prioritySignupEnabled: 1, canDesignRoute: 1, canNpc: 1, canMerchantCoop: 1, hasGuideCert: 1 };
  update(club, 'player');
  assert.equal(instance.data._data, '0 位成员');
  assert.deepEqual(Array.from(instance.data._tags), ['City Walk','城市考古','夜骑','免费加入','成员优先报名']);
  update(club, 'merchant');
  assert.deepEqual(Array.from(instance.data._tags), ['能设计路线','能出 NPC','能对接商家','导游资质']);
  update({ ...club, isJoined: true }, 'player');
  assert.equal(instance.data._joined, true);
  assert.ok(!instance.data._tags.includes('免费加入'));
  update(null, 'player');
  assert.equal(instance.data._data, '');
  assert.equal(instance.data._tags.length, 0);
});
test('商家缺省不编免费场地，完整标签保留，损坏图片有独立兜底', () => {
  const { def, instance } = card('merchant-card');
  const update = (...args) => def.observers['tags, variant, chargeType, capacity'].call(instance, ...args);
  update([], 'venue', null, null);
  assert.equal(instance.data._tags.length, 0);
  update([], 'venue', 0, 30);
  assert.deepEqual(Array.from(instance.data._tags), ['场地免费','可承接 30 人']);
  update('["桌游","精酿","宠物友好","夜间","桌游"]', 'list');
  assert.equal(instance.data._tags.length, 4);
  def.methods.onCoverError.call(instance);
  assert.equal(instance.data._coverFailed, true);
  assert.equal(instance.data._logoFailed, false);
  def.observers.cover.call(instance);
  assert.equal(instance.data._coverFailed, false);
  let taps = 0; instance.triggerEvent = () => taps++;
  instance.data.status = 'disabled'; def.methods.onTap.call(instance);
  assert.equal(taps, 0);
  instance.data.status = 'none'; def.methods.onTap.call(instance);
  assert.equal(taps, 1);
});
