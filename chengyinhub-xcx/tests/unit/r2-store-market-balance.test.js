'use strict';
// r2-store 通行证页积分余额卡:余额只读服务端,读不到不出(不写 0);不出充值包与会员卡。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const dir = path.join(__dirname, '../../subpackageMember/market/passes');
const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');

test('余额卡只在服务端给了数值时出现,点进原积分明细页', () => {
  assert.match(wxml, /class="mk-balance" wx:if="\{\{pointBalance !== null\}\}"/);
  assert.match(js, /pointBalance: null/);
  assert.match(js, /url: '\/api\/user\/info'/);
  assert.match(js, /\/subpackageMember\/points-history\/index/);
});

test('积分不卖:没有充值入口;平台会员没有服务端商品,不出会员卡', () => {
  const markup = wxml.replace(/<!--[\s\S]*?-->/g, '');
  assert.ok(!/充值|购买积分|会员卡|自玩票/.test(markup));
});
