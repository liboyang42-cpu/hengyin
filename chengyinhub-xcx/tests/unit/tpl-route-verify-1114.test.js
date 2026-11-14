'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const read = file => fs.readFileSync(path.join(__dirname, '../..', file), 'utf8');

test('站名末尾两字同行，显示处理不改业务站名或英文', () => {
  const context = { module: { exports: {} }, getRegExp: RegExp };
  vm.runInNewContext(read('utils/wxs/station-title.wxs'), context);
  const text = context.module.exports.text;
  for (const title of ['2 · 毕业那天先发生什么', '发生什么？', '1 · 五份称呼是否一只猫', '上海']) {
    const displayed = text(title);
    assert.ok(displayed.includes('\u2060'));
    assert.equal(displayed.replace(/\u2060/g, ''), title);
    assert.match(displayed, /[\u3400-\u9fff]\u2060[\u3400-\u9fff]/);
  }
  for (const title of ['A', '展', 'Shop 2', '🎓', '']) assert.equal(text(title), title);
  assert.match(read('pages/play/index.wxml'), /stationTitle\.text\(sheet\.node\.name\)/);
});

test('模板滚动区在导航下裁切，路线末行给悬浮购买条留安全区', () => {
  assert.match(read('pages/templatedetail/topic-detail/index.wxml'), /class="td-viewport"[^>]*top:\{\{statusBarHeight \+ navBarHeight\}\}px/);
  assert.match(read('pages/templatedetail/topic-detail/index.wxss'), /\.td-viewport\s*\{[^}]*overflow:\s*hidden/);
  assert.match(read('pages/topic/index/index.wxml'), /docked && heroCta\.text \? 'topic-list--docked'/);
  assert.match(read('pages/topic/index/index.wxss'), /\.topic-list--docked\s*\{[^}]*safe-area-inset-bottom/);
});
