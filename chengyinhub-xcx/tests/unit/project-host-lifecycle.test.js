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

test('主办封面使用阶段显示，不再直接把上架状态印成招募', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/topic/components/project-host/index.wxml'), 'utf8');
  assert.match(wxml, /\{\{ hostStatusText \}\}/);
  assert.doesNotMatch(wxml, /hostTopic\.status === 1 \?/);
});
