// BL-77 商家券领取/核销统计:只读、无券或读失败不展示。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const { shapeCouponSummary } = require('../../subpackageMember/coupon/coupon-summary.js');

test('有券时给出累计领取/核销两项,0 照实显示', () => {
  assert.deepEqual(
    shapeCouponSummary({ code: 200, data: { couponCount: 0, couponTotal: 2, received: 0, verified: 0 } }),
    [{ label: '累计领取', value: '0' }, { label: '累计核销', value: '0' }]);
  assert.deepEqual(
    shapeCouponSummary({ code: '200', data: { couponTotal: '3', received: 12, verified: '5' } }).map(i => i.value),
    ['12', '5']);
});

test('没有券、失败、字段缺失或非法时不展示', () => {
  assert.equal(shapeCouponSummary({ code: 200, data: { couponTotal: 0, received: 0, verified: 0 } }), null);
  assert.equal(shapeCouponSummary({ code: 500, msg: 'x' }), null);
  assert.equal(shapeCouponSummary({ code: 200, data: [] }), null);
  assert.equal(shapeCouponSummary({ code: 200, data: { couponTotal: 1, received: 3 } }), null);
  assert.equal(shapeCouponSummary({ code: 200, data: { couponTotal: 1, received: -1, verified: 0 } }), null);
  assert.equal(shapeCouponSummary(null), null);
});

test('页面只在商家口径读统计,用 cy-stat-card 且空数组不渲染;回调不用 this 方法简写', () => {
  const js = fs.readFileSync(path.join(root, 'subpackageMember/coupon/coupon.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(root, 'subpackageMember/coupon/coupon.wxml'), 'utf8');
  const loader = js.match(/loadSummary\(\)\s*\{[\s\S]*?\n {2}\},/);
  assert.ok(loader);
  assert.match(loader[0], /operationScope !== 'MERCHANT'\) return;/);
  assert.match(loader[0], /url: '\/api\/merchant\/coupon-summary'/);
  assert.match(loader[0], /success: \(res\) =>/);
  assert.doesNotMatch(loader[0], /success\s*\(res\)\s*\{/);
  assert.match(wxml, /<cy-stat-card wx:if="\{\{summaryItems\.length\}\}" class="coupon-summary" items="\{\{summaryItems\}\}"/);
  assert.match(wxml, /<cy-page-title title="优惠券" \/>/, '不改现有主标题');
});
