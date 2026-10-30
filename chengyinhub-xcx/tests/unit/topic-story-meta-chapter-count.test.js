'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const motion = require('../../pages/topic/utils/topic-motion.js');

// 实走 A2:未购前 chaptersList 只给可预览的章,总章数要看 totalChapterCount
test('章数取 totalChapterCount,不被未购前的预览章数缩小', () => {
  assert.equal(motion.storyMeta({ productType: 1, chaptersList: [{ id: 1 }], totalChapterCount: 2, totalTimeFormatted: '1小时' }), '2 章 · 约 1小时');
  assert.equal(motion.storyMeta({ productType: 1, chaptersList: [{ id: 1 }, { id: 2 }] }), '2 章');
});
