'use strict';
// T6 mini-program:城市定向剧情与 App(chengyin-app cloud/r-city-orienteering)对齐 ——
// 分支选择前说明「选定后不能更改」,但不预告各选项通向哪个结局(跳转表不下发)。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const dir = path.resolve(__dirname, '../../pages/play/components/playkit-branch');
const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
const wxss = fs.readFileSync(path.join(dir, 'index.wxss'), 'utf8');
const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');

test('选之前有一行「选定后不能更改」,终点 / 只读 / 已选时不出', () => {
  const hint = wxml.match(/<view class="br__hint" wx:if="\{\{([^}]+)\}\}">([^<]+)<\/view>/);
  assert.ok(hint, '分支选项前必须有说明行');
  assert.equal(hint[1], '!isEnd && writable && picked < 0 && options.length');
  assert.match(hint[2], /选定后不能更改/);
  assert.ok(wxml.indexOf('br__hint') < wxml.indexOf('class="br__opts"'), '说明在选项之前');
  assert.match(wxss, /\.br__hint \{[^}]*color: var\(--sub\)/);
});

test('仍不在客户端预告结局:没有 nextStepId / 跳转表', () => {
  assert.doesNotMatch(js, /nextStepId\s*:/);
  assert.doesNotMatch(wxml, /nextStep|outcome|通向/);
});
