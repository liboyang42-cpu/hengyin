'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');

// 实走 A14 复核:排对之后重进 / 静默回来,台面要按服务端记的上一次提交顺序摆,不能回到题面打乱序
test('排序 kit 透传服务端 lastOrder', () => {
  const view = { sessionId: 1, version: 3, status: 'RUNNING', playKit: { sort: {
    prompt: '按时间先后排序', items: [{ id: 'b' }, { id: 'c' }, { id: 'a' }],
    finished: true, passed: true, lastCorrect: true, lastOrder: ['a', 'b', 'c'] } } };
  const kit = pickPlayKit(view, new Date());
  assert.equal(kit.type, 'sort');
  assert.deepEqual(kit.lastOrder, ['a', 'b', 'c']);
  const fresh = pickPlayKit({ sessionId: 1, version: 1, status: 'RUNNING', playKit: { sort: { items: [{ id: 'a' }] } } }, new Date());
  assert.deepEqual(fresh.lastOrder, []);
});
