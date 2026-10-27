'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const wxss = fs.readFileSync(path.resolve(__dirname, '../../pages/template/index.wxss'), 'utf8');

test('模板首页搜索入口保留至少 88rpx 的整行触控高度', () => {
  const rule = /\.tpl-search\s*\{([\s\S]*?)\}/.exec(wxss);
  assert.ok(rule, '缺少 .tpl-search 规则');
  assert.match(rule[1], /min-height:\s*var\(--cy-btn-h\)/);
  assert.match(rule[1], /box-sizing:\s*border-box/);
});

// 2026-10-27 真机走查 M-02(m677):搜索占位文字要和左侧「主题 / 游戏」tab 文字对齐。
// 原「align-self:center + margin-bottom」把搜索框抬高 16rpx(与 tab 文字错开),按 owner 新说法改为
// 与 tab 同一个 88rpx 盒子底对齐、药丸在盒内居中;药丸 64rpx < 88rpx,下方仍留出与紫面板的间隙。
test('M-02 搜索药丸可见高度 64rpx,与 tab 同盒底对齐、占位文字与 tab 文字同一中线', () => {
  const pill = /\.tpl-search-pill\s*\{([\s\S]*?)\}/.exec(wxss);
  assert.ok(pill, '缺少 .tpl-search-pill 规则');
  assert.match(pill[1], /height:\s*64rpx/);
  assert.match(pill[1], /align-items:\s*center/);
  const rule = /\.tpl-search\s*\{([\s\S]*?)\}/.exec(wxss);
  assert.match(rule[1], /align-items:\s*center/);
  assert.match(rule[1], /align-self:\s*flex-end/);
  assert.doesNotMatch(rule[1].replace(/\/\*[\s\S]*?\*\//g, ''), /margin-bottom:/, '底部外边距会把搜索框抬离 tab 文字中线');
});
