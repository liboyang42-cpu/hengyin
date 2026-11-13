'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const motion = require('../../pages/topic/utils/topic-motion.js');

// 实走 A2:未购前 chaptersList 只给可预览的章,总章数要看 totalChapterCount
test('章数取 totalChapterCount,不被未购前的预览章数缩小', () => {
  assert.equal(motion.storyMeta({ productType: 1, chaptersList: [{ id: 1 }], totalChapterCount: 2, totalTimeFormatted: '1小时' }), '2 章 · 约 1小时');
  assert.equal(motion.storyMeta({ productType: 1, chaptersList: [{ id: 1 }, { id: 2 }] }), '2 章');
});

test('章数不含结局:4 章 + 3 结局写成「4 章 · 3 个结局」', () => {
  const ch = (n) => ({ name: n });
  const list = [ch('夜雾'), ch('旧港'), ch('钟楼'), ch('尾灯'), ch('结局一 · 归途'), ch('结局二'), ch('结局三')];
  assert.equal(motion.storyMeta({ productType: 1, chaptersList: list, totalChapterCount: 7, totalTimeFormatted: '2小时' }), '4 章 · 3 个结局 · 约 2小时');
  assert.equal(motion.storyMeta({ productType: 1, chaptersList: [ch('一'), ch('二'), ch('三'), ch('结局A'), ch('结局B'), ch('结局C')], totalChapterCount: 6 }), '3 章 · 3 个结局');
});

test('购买半屏小牌里「N 章 · M 个结局」不被拆开', () => {
  const meta = '4 章 · 3 个结局 · 约 2小时';
  const chips = motion.buyChips({ productType: 1, endDate: '2027-01-01' }, meta);
  assert.equal(chips[1], '4 章 · 3 个结局 · 约 2小时');
});
