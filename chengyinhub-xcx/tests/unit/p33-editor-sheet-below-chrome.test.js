'use strict';
// P-33:编辑器满屏态从胶囊下方开始,滚动内容不再钻到状态栏与返回钮底下。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const wxss = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.wxss'), 'utf8');

test('满屏 sheet 用 top 让出胶囊区,不用会随内容滚走的 padding-top', () => {
  const at = wxss.indexOf('.slopes-sheet--full {');
  const rule = wxss.slice(at, wxss.indexOf('}', at));
  assert.match(rule, /top:\s*var\(--cy-sheet-chrome-top, 0px\)/);
  assert.match(rule, /padding-top:\s*0/);
  assert.doesNotMatch(rule, /padding-top:\s*var\(--cy-sheet-chrome-top/);
});
