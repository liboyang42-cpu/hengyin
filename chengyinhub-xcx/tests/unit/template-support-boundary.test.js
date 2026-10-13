const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
test('支持情况和反馈工单分名，缺支持数据时显示未确认', () => {
  const support = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/fabu/topic-detail-sheet.wxml'), 'utf8');
  assert.match(support, /玩法支持情况/);
  assert.match(support, /用户反馈处理暂未开放/);
  assert.match(support, /支持情况暂未确认/);
});
