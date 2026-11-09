const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

let component;
global.Component = (config) => { component = config; };
require('../../pages/topic/components/project-host/index.js');

function badge(topic, recruit = { nodeTotal: 4, nodeFilled: 2 }) {
  const instance = { data: {}, setData(patch) { Object.assign(this.data, patch); } };
  const update = component.observers['hostTopic, hostRecruit'];
  assert.equal(typeof update, 'function');
  update.call(instance, topic, recruit);
  return instance.data.hostStatusText;
}

test('自由探索主办显示真实业务阶段，审核通过不能把待审或售票改为招募', () => {
  const states = ['草稿', '招募中', '待定价', '售票中', '进行中', '结算中', '已关闭', '立项待审'];
  states.forEach((text, lifecycle) => {
    assert.equal(badge({ productType: 2, status: 1, lifecycle }), text);
    assert.equal(badge({ productType: '2', status: 1, lifecycle: String(lifecycle) }), text);
  });
});

test('自由探索缺失或未知阶段不以已上架猜测为招募中', () => {
  [undefined, null, '', false, 8, -1, 2.5, 'SELLING', '7x'].forEach(lifecycle => {
    assert.equal(badge({ productType: 2, status: 1, lifecycle }), '状态待确认');
  });
});

test('经典路线的审核与招募显示保留，包括招满后的显示', () => {
  assert.equal(badge({ productType: 1, status: 0 }), '待审核');
  assert.equal(badge({ productType: 1, status: 2 }), '审核未通过');
  assert.equal(badge({ productType: 1, status: 1 }), '招募中');
  assert.equal(badge({ productType: 1, status: 1 }, { nodeTotal: 2, nodeFilled: 2 }), '已招满');
  assert.equal(badge({ productType: 1, status: null }), '状态待确认');
});

test('R15 已发布普通 CITY 保留 lifecycle0，胶囊、主按钮与进度不再表示草稿', () => {
  const topic = { id: 990037, productType: 1, status: 1, userStatus: 1, lifecycle: 0 };
  assert.equal(badge(topic), '已发布');
  const instance = { data: { hostOwnerType: 'club' }, setData(patch) { Object.assign(this.data, patch); } };
  component.observers['hostRecruit, clubSheet, hostTopic, hostPlayers'].call(
    instance, { nodeTotal: 1, nodeFilled: 0 }, { items: [] }, topic, { paidCount: 0 });
  assert.deepEqual(instance.data.hostPrimary,
    { text: '查看客户', act: 'onQuickAction', key: 'customer', disabled: false });
  assert.deepEqual(instance.data.hostProgress.map(s => [s.key, s.state]), [['config', 'done'], ['publish', 'done']]);
  assert.equal(instance.data.showVerify, false, '已发布不推断商业售票或核销资格');
  assert.equal(topic.lifecycle, 0);
  assert.equal(badge({ ...topic, status: 0 }), '草稿');
  assert.equal(badge({ ...topic, status: 2 }), '草稿');
  for (let lifecycle = 1; lifecycle <= 7; lifecycle++) {
    const expected = ['草稿', '招募中', '待定价', '售票中', '进行中', '结算中', '已关闭', '立项待审'][lifecycle];
    assert.equal(badge({ ...topic, lifecycle }), expected);
  }
});

test('主办封面使用阶段显示，不再直接把上架状态印成招募', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/topic/components/project-host/index.wxml'), 'utf8');
  assert.match(wxml, /\{\{ hostStatusText \}\}/);
  assert.doesNotMatch(wxml, /hostTopic\.status === 1 \?/);
});
