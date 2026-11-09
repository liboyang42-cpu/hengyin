// qx-b5 §19 / §27.10 个人分佣只读本人分项。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { revenueView, allocationRow } = require('../../subpackageA/utils/revenue-view.js');

const ROOT = path.join(__dirname, '..', '..');

test('读取失败 ⇒ error(不显示 0);空列表 ⇒ empty', () => {
  assert.deepEqual(revenueView(null), { state: 'error', rows: [] });
  assert.equal(revenueView([]).state, 'empty');
});

test('作者与推荐人两项不合并;金额按 amountFen;冲回单列负数并按退款事实分组', () => {
  const row = allocationRow({
    allocationId: 7702, orderType: 1, statusText: 'partially_reversed', currency: 'CNY', reversedAmount: 20,
    lines: [
      { role: 'author', entryType: 1, amountFen: 690, amount: 6.9 },
      { role: 'referrer', entryType: 1, amountFen: 345, amount: 3.45 },
      { role: 'author', entryType: 2, amountFen: -200, sourceFact: 'refund:31' },
      { role: 'referrer', entryType: 2, amountFen: -100, sourceFact: 'refund:31' }
    ]
  });
  assert.equal(row.status, '部分退款已冲回');
  assert.deepEqual(row.items.map((i) => [i.label, i.value]), [
    ['作者分佣', '¥6.90'], ['推荐分佣', '¥3.45'],
    ['退款冲回 · 作者分佣', '−¥2.00'], ['退款冲回 · 推荐分佣', '−¥1.00']
  ]);
  assert.ok(!row.items.some((i) => /20/.test(i.value)), 'reversedAmount 是父单上下文,不当个人金额');
});

test('状态按码:accrued 写待可结算与起始日;未知码写待确认;无提现文案', () => {
  const r = allocationRow({ allocationId: 1, statusText: 'accrued', settleableAt: '2026-11-16T21:04:40.000+08:00', lines: [] });
  assert.equal(r.status, '已入账 · 待可结算');
  assert.equal(r.when, '2026.11.16 起可结算');
  assert.equal(allocationRow({ allocationId: 2, statusText: 'weird', lines: [] }).status, '状态待确认');
  const { STATUS_TEXT } = require('../../subpackageA/utils/revenue-view.js');
  assert.ok(!Object.values(STATUS_TEXT).some((t) => /提现|到账/.test(t)));
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageA/pages/assetcenter/income-detail/income-detail.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /提现|ruleVersion|\.rate\b/);
});

test('金额缺失写「金额待确认」,不补 0', () => {
  const r = allocationRow({ allocationId: 3, statusText: 'settleable', lines: [{ role: 'author', entryType: 1, amountFen: null, amount: null }] });
  assert.equal(r.items[0].value, '金额待确认');
});
