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
// 2026-11-03 集成:pay-gate 同修了这一处(付款成功 → 正在退款 → 退款成功,业主定稿词),
// 时间线另调 isCityOrder(P-20),这里一并注入;断言意图不变:退款后不回「等待支付」。
const build = new Function('toDisplayTime', 'isFreeOrder', 'isCityOrder', body + '\nreturn buildOrderTimeline;')(
  (v) => (v ? String(v) : ''), () => false, () => false);

test('已退款:付款成功 → 退款成功,没有「等待支付」', () => {
  const rows = build({ orderStateKey: 'refunded', paymentStatus: 3, createTime: 't0', paymentTime: 't1' });
  assert.deepEqual(rows.map((r) => r.label), ['订单已创建', '付款成功', '退款成功']);
});

test('正在退款同样不回到等待支付', () => {
  const rows = build({ orderStateKey: 'refunding', paymentStatus: 4, createTime: 't0' });
  assert.ok(!rows.some((r) => r.label === '等待支付'));
  assert.equal(rows[rows.length - 1].label, '正在退款');
});

test('真正未支付的单仍是等待支付', () => {
  const rows = build({ orderStateKey: 'pending_payment', paymentStatus: 0, createTime: 't0' });
  assert.equal(rows[1].label, '等待支付');
});
