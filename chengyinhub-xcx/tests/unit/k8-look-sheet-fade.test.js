'use strict';

// 第十二轮 k8(j3 F18):画面风格常驻预览下沿加同底色渐隐,滚到它底下的「主题配色」等标题不再被切成半截字。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const wxss = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/look-sheet.wxss'), 'utf8');

test('j3 F18:常驻预览仍钉在顶部,下沿有同底色渐隐', () => {
  assert.match(wxss, /\.look-pin \{ display: block; position: sticky; top: 0;/);
  assert.match(wxss, /\.look-pin \{ box-shadow: 0 28rpx 24rpx -4rpx var\(--cy-comp-sheet-bg\); \}/);
});
