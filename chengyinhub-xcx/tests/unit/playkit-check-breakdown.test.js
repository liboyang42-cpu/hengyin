const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { checkReceiptView } = require('../../pages/play/utils/playkit-view.js');

const breakdown = {
  die: 20,
  skill: { statKey: 'counter.focus', label: '专注', value: 0 },
  situational: { raw: 8, cap: 3, applied: 3 },
  total: 23, dc: 25, natAuto: false,
};

test('server breakdown preserves zero, caps and explicit natAuto false without deciding success', () => {
  const receipt = { dice: [4, 20], kept: 20, total: 23, dc: 25, success: false, nat: null, breakdown };
  const original = JSON.stringify(receipt);
  const view = checkReceiptView(receipt);
  assert.deepEqual(view.breakdownRows, [
    { label: '采用骰点', value: 20 },
    { label: '专注', value: 0 },
    { label: '情境修正合计', value: 8 },
    { label: '情境修正上限', value: 3 },
    { label: '实际情境修正', value: 3 },
    { label: '达成值', value: 23 },
    { label: '难度 DC', value: 25 },
    { label: '自然骰自动判定', value: '关闭' },
  ]);
  assert.equal(view.success, false);
  assert.equal(view.nat, '');
  assert.deepEqual(view.dice, [4, 20]);
  assert.equal(JSON.stringify(receipt), original);
  const automatic = checkReceiptView({ ...receipt, success: true, nat: 'CRIT', breakdown: { ...breakdown, natAuto: true } });
  assert.equal(automatic.breakdownRows.at(-1).value, '开启');
  assert.equal(automatic.success, true);
  assert.equal(automatic.nat, 'CRIT');
});

test('hidden and redacted projections never reveal attribute identity or reverse-engineer values', () => {
  for (const flag of ['hidden', 'redacted']) {
    const view = checkReceiptView({ breakdown: { ...breakdown,
      skill: { [flag]: true, label: 'SECRET', statKey: 'counter.secret', value: 99 },
      situational: { [flag]: true, raw: 97, cap: 98, applied: 96 },
    } });
    assert.deepEqual(view.breakdownRows, [
      { label: '采用骰点', value: 20 },
      { label: '属性加值', value: '已隐藏' },
      { label: '达成值', value: 23 },
      { label: '难度 DC', value: 25 },
      { label: '自然骰自动判定', value: '关闭' },
    ]);
    assert.deepEqual(checkReceiptView({ breakdown: { ...breakdown, [flag]: true } }).breakdownRows, []);
  }
});

test('legacy, partial and malformed breakdowns do not fabricate facts or default natAuto', () => {
  for (const b of [undefined, null, {}, 'UNKNOWN', { die: null, total: '23', dc: NaN, natAuto: 'false', skill: { value: Infinity } }]) {
    const view = checkReceiptView({ total: 7, success: false, breakdown: b });
    assert.deepEqual(view.breakdownRows, []);
    assert.equal(view.total, 7);
    assert.equal(view.success, false);
  }
  assert.deepEqual(checkReceiptView({ breakdown: { situational: { applied: -2 }, bargain: 2, momentum: 1 } }).breakdownRows, [
    { label: '实际情境修正', value: -2 },
    { label: '交易加值', value: 2 },
    { label: '动量加值', value: 1 },
  ]);
});

test('settled receipts retain breakdown and the real component template renders only projected rows after landing', () => {
  const view = checkReceiptView({ settled: true, text: '服务端结算文案', breakdown });
  assert.equal(view.text, '服务端结算文案');
  assert.equal(view.breakdownRows.length, 8);
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/components/playkit-journey-check/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{receipt && landed && receipt.breakdownRows.length\}\}" aria-label="检定明细"/);
  assert.match(wxml, /wx:for="\{\{receipt.breakdownRows\}\}" wx:key="index"/);
  assert.match(wxml, /\{\{item.label\}\}/);
  assert.match(wxml, /\{\{item.value\}\}/);
  assert.doesNotMatch(wxml, /receipt\.breakdown\./);
});
