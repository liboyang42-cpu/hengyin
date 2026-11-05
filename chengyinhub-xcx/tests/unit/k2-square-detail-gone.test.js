'use strict';
// 第十二轮 k2(p1 N-4):分享来的帖文不存在 / 已删 / 审核中,给「链接已失效 · 返回」,不是只剩一条返回栏的加载失败。
const assert = require('assert');
const test = require('node:test');
const fs = require('fs');
const path = require('path');
const read = p => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8');

test('k2 p1 N-4:帖文已不在 → gone 态,标题「链接已失效」+ 返回', () => {
  const js = read('pages/square/detail/index.js');
  const m = js.match(/const GONE_POST_MSG = (\/.*\/);/);
  assert.ok(m);
  const re = eval(m[1]);
  for (const msg of ['内容不存在', '已删除', '审核中', '数据不存在', '参数不合规1']) assert.ok(re.test(msg), msg);
  assert.ok(!re.test('服务繁忙'));
  assert.match(js, /if \(GONE_POST_MSG\.test\(String\(res\.msg \|\| ''\)\)\) \{ that\.setData\(\{ detailState: 'gone'/);
  const wxml = read('pages/square/detail/index.wxml');
  assert.match(wxml, /wx:elif="\{\{detailState === 'gone'\}\}">\s*<cy-empty[^>]*title="链接已失效" cta="返回" bind:cta="goBack" \/>/);
});

test('k2 p1 N-4:站点商家页链接直达(没有上一页的节点)给「链接已失效 · 返回」', () => {
  const wxml = read('pages/play/merchant/index.wxml');
  assert.match(wxml, /<cy-empty class="mp-empty" wx:if="\{\{notFound\}\}" kind="missing-param" title="链接已失效" cta="返回" bind:cta="goBackOrHome"/);
  assert.equal(JSON.parse(read('pages/play/merchant/index.json')).usingComponents['cy-empty'], '/components/cy/empty/index');
  assert.match(read('pages/play/merchant/index.js'), /goBackOrHome\(\) \{ wx\.navigateBack\(\{ fail: \(\) => wx\.switchTab\(\{ url: '\/pages\/index\/index' \}\) \}\) \}/);
});
