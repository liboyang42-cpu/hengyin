'use strict';
// C-01/P-01:招募中或定价/开售审核中的主题在俱乐部工作台不再显示「已确认」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const src = fs.readFileSync(path.join(__dirname, '../../pages/club/topic-detail/index.js'), 'utf8');

test('lifecycle 1/2 的状态文案说清没开售', () => {
  const table = src.match(/const NOT_ON_SALE_TEXT = (\{[^}]*\});/);
  assert.ok(table);
  const map = new Function('return ' + table[1])();
  assert.equal(map[1], '招募中 · 未开售');
  // 第十轮 f2(j2 F-05):与「更多」里的阶段同一口径(定价中),不再头部「待开售审核」、更多「定价中」互相打架。
  assert.equal(map[2], '定价中 · 未开售');
  assert.equal(map[7], '立项待审 · 未开售');
  assert.equal(map[3], undefined, '开售后照旧');
  assert.match(src, /statusKey === 'confirmed' && NOT_ON_SALE_TEXT\[Number\(raw\.lifecycle\)\]/);
});
