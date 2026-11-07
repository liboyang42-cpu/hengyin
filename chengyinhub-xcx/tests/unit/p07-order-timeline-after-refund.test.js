'use strict';
// P-07:退款后订单进度不能回到「等待支付」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const src = fs.readFileSync(path.join(__dirname,
  '../../subpackageMember/components/cy/scene-member-order-detail/index.js'), 'utf8');
const start = src.indexOf('function buildOrderTimeline(');
const body = src.slice(start, src.indexOf('\nfunction ', start + 10));
const build = new Function('toDisplayTime', 'isFreeOrder', body + '\nreturn buildOrderTimeline;')(
  (v) => (v ? String(v) : ''), () => false);

test('已退款:支付已完成 → 已退款,没有「等待支付」', () => {
  const rows = build({ orderStateKey: 'refunded', paymentStatus: 3, createTime: 't0', paymentTime: 't1' });
  assert.deepEqual(rows.map((r) => r.label), ['订单已创建', '支付已完成', '已退款']);
});

test('退款处理中同样不回到等待支付', () => {
  const rows = build({ orderStateKey: 'refunding', paymentStatus: 4, createTime: 't0' });
  assert.ok(!rows.some((r) => r.label === '等待支付'));
  assert.equal(rows[rows.length - 1].label, '退款处理中');
});

test('真正未支付的单仍是等待支付', () => {
  const rows = build({ orderStateKey: 'pending_payment', paymentStatus: 0, createTime: 't0' });
  assert.equal(rows[1].label, '等待支付');
});
