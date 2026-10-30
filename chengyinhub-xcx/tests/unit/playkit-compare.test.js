'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { pickPlayKit, serverAction, serverPayload } = require('../../pages/play/utils/playkit-view.js');

// 实走复核:海上方舟「生命签证」配了问答 + 时间轴对照,玩家端没有对照这一屏,问答答完就关掉、站点永远完成不了
const compare = {
  prompt: '标出右侧所有发生在1943年以前的事。', maxAttempts: 3,
  left: { label: '世界', items: [{ id: 'w1938', time: '1938', text: '奥地利被德国吞并' }] },
  right: { label: '上海', items: [{ id: 's1938', time: '1938', text: '难民抵沪' }, { id: 's1943', time: '1943', text: '限定居住区设立' }] },
  attempts: 1, finished: false, passed: false, lastCorrect: false, lastMarked: ['s1943'],
};

test('问答答完之后轮到对照题,不再因为没有这一屏而整局关掉', () => {
  const view = { sessionId: 9, version: 4, status: 'RUNNING', playKit: { qa: { mode: 'PICK', title: 'q', options: [], passed: true, finished: true, submitted: true }, compare } };
  const kit = pickPlayKit(view, new Date());
  assert.equal(kit.type, 'compare');
  assert.deepEqual(kit.left, compare.left);
  assert.deepEqual(kit.right, compare.right);
  assert.deepEqual(kit.lastMarked, ['s1943']);
  assert.equal(kit.attempts, 1);
});

test('对照题提交按服务端 SUBMIT_COMPARE 要的 marked 形状', () => {
  assert.equal(serverAction('compare', 'submit'), 'SUBMIT_COMPARE');
  assert.deepEqual(serverPayload('compare', 'submit', { marked: ['s1938'] }), { marked: ['s1938'] });
});

test('对照组件:两侧按题面顺序画行,交上去的 marked 按题面顺序', () => {
  let def;
  global.Component = (d) => { def = d; };
  global.wx = global.wx || {};
  global.Behavior = global.Behavior || ((b) => b);
  require('../../pages/play/components/playkit-compare/index.js');
  const sides = def.methods._paint(compare.left, compare.right, ['s1943', 'w1938']);
  assert.deepEqual(sides.map((s) => s.label), ['世界', '上海']);
  assert.deepEqual(def.methods._markedIds(sides), ['w1938', 's1943']);
});

// 实走复核:「给这杯咖啡起个名字」交 4 个字被拒「作品名须为 1 至 12 字」—— 组件报 value,服务端读 name
test('作品命名提交按服务端 SUBMIT_DIY_NAME 要的 name 字段', () => {
  assert.equal(serverAction('diyname', 'submit'), 'SUBMIT_DIY_NAME');
  assert.deepEqual(serverPayload('diyname', 'submit', { value: ' 方舟一号 ' }), { name: '方舟一号' });
});
