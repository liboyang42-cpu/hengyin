// 用户要求：小标题紧跟返回，滚动内容只为导航留空。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = rel => fs.readFileSync(path.resolve(__dirname, '../..', rel), 'utf8');
const wxml = read('pages/publish/temp/index.wxml');
test('玩法编辑页只有返回后的导航标题，内容避开实际导航高度', () => {
  assert.match(wxml, /<cy-nav-bar[^>]*title="[^\"]*编辑玩法模板[^\"]*"[^>]*title-align="left"/);
  assert.doesNotMatch(wxml, /<cy-page-title\b/);
  assert.match(wxml, /padding-top:\s*\{\{statusBarHeight \+ navBarHeight\}\}px/);
  assert.match(read('components/cy/nav-bar/index.wxss'), /\.nav__bar--title-left \.nav__title\s*\{[^}]*font-size:\s*var\(--cy-font-body\)/);
});
