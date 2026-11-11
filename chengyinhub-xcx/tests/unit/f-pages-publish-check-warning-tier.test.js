'use strict';
// f-pages(2026-11-11)发布前检查 warning 档:本地 advisory(软建议)在弹层里可见,不拦「完成」;
// 只有 advisory 时不再静默直发,先出弹层给作者看一眼。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

test('弹层渲染 publishCheck.advisory,warning 样式与 blocking 的红色分开,可定位项带「去修改」', () => {
  const wxml = read('pages/publish/fabu/index.wxml');
  assert.match(wxml, /class="sp-issues sp-issues--warn" wx:if="\{\{publishCheck\.advisory\.length\}\}"/);
  assert.match(wxml, /class="sp-issue sp-issue--warn" wx:for="\{\{publishCheck\.advisory\}\}" wx:key="index" wx:if="\{\{item\.field\}\}" data-field="\{\{item\.field\}\}" bindtap="locatePublishIssue"/);
  assert.match(wxml, /class="sp-issue sp-issue--warn" wx:for="\{\{publishCheck\.advisory\}\}" wx:key="index" wx:if="\{\{!item\.field\}\}"/);
  const wxss = read('pages/publish/fabu/index.wxss');
  assert.match(wxss, /\.sp-issue--warn \{ color: var\(--cy-color-status-warning\); \}/);
});

test('「完成」只看 blocking;advisory 不禁用按钮', () => {
  const wxml = read('pages/publish/fabu/index.wxml');
  assert.match(wxml, /class="sp-done"[^>]*disabled="\{\{!!publishCheck\.blocking\.length\}\}"/);
  assert.doesNotMatch(wxml, /disabled="\{\{[^}]*advisory/);
});

test('预检后:没有 blocking 但有本地 advisory 时出弹层,不直发;两者都空才直发', () => {
  const js = read('pages/publish/fabu/index.js');
  const body = js.slice(js.indexOf('_afterPrecheck(aiIssues, skipLabel) {'), js.indexOf('_showBackendPublishIssue(issue) {'));
  assert.match(body, /const local = this\.buildPublishCheck\(\);/);
  assert.match(body, /!local\.blocking\.length && !local\.advisory\.length/);
  assert.match(body, /this\._showPublishCheck\(aiIssues, skipLabel\);/);
});
