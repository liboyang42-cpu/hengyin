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

test('server breakdown lists dice, attribute and applied modifiers in player words without rule terms', () => {
  const receipt = { dice: [4, 20], kept: 20, total: 23, dc: 25, success: false, nat: null, breakdown };
  const original = JSON.stringify(receipt);
  const view = checkReceiptView(receipt);
  assert.deepEqual(view.breakdownRows, [
    { label: '骰子', value: 20 },
    { label: '专注', value: '0' },
    { label: '条件修正', value: '+3' },
  ]);
  assert.doesNotMatch(JSON.stringify(view.breakdownRows), /上限|DC|自然骰|达成值/);
  assert.equal(view.success, false);
  assert.equal(view.nat, '');
  assert.deepEqual(view.dice, [4, 20]);
  assert.equal(JSON.stringify(receipt), original);
  const automatic = checkReceiptView({ ...receipt, success: true, nat: 'CRIT', breakdown: { ...breakdown, natAuto: true } });
  assert.equal(automatic.success, true);
  assert.equal(automatic.nat, 'CRIT');
});

test('applied modifiers are named only when they add up to the server applied value', () => {
  const b = { die: 14, skill: { label: '观察', value: 3 }, situational: { raw: 2, cap: 4, applied: 2 }, bargain: 2 };
  const mods = [{ label: '带着旧手电', value: 2, applied: true }, { label: '夜里更看得清', value: 6, applied: false }];
  assert.deepEqual(checkReceiptView({ breakdown: b, mods }).breakdownRows, [
    { label: '骰子', value: 14 }, { label: '观察', value: '+3' }, { label: '带着旧手电', value: '+2' }, { label: '魔鬼交易', value: '+2' },
  ]);
  const capped = checkReceiptView({ breakdown: { ...b, situational: { raw: 8, cap: 2, applied: 2 } },
    mods: [{ label: '甲', value: 4, applied: true }, { label: '乙', value: 4, applied: true }] });
  assert.deepEqual(capped.breakdownRows[2], { label: '条件修正', value: '+2' });
});

test('hidden and redacted projections never reveal attribute identity or reverse-engineer values', () => {
  for (const flag of ['hidden', 'redacted']) {
    const view = checkReceiptView({ breakdown: { ...breakdown,
      skill: { [flag]: true, label: 'SECRET', statKey: 'counter.secret', value: 99 },
      situational: { [flag]: true, raw: 97, cap: 98, applied: 96 },
    } });
    assert.deepEqual(view.breakdownRows, []);
    assert.equal(view.detailsHidden, true);
    assert.equal(view.total, null);
    assert.equal(view.dc, null);
    assert.deepEqual(view.dice, []);
    assert.doesNotMatch(JSON.stringify(view), /SECRET|counter.secret|99|97|98|96/);
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
    { label: '条件修正', value: '-2' },
    { label: '魔鬼交易', value: '+2' },
    { label: '动量', value: '+1' },
  ]);
});

test('settled receipts retain breakdown and the real component template renders projected rows once after landing', () => {
  const view = checkReceiptView({ settled: true, text: '服务端结算文案', breakdown });
  assert.equal(view.text, '服务端结算文案');
  assert.equal(view.breakdownRows.length, 3);
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/components/playkit-journey-check/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{landed && receipt.breakdownRows.length\}\}" aria-label="检定明细"/);
  assert.equal(wxml.match(/receipt\.breakdownRows\}\}/g).length, 1);
  assert.match(wxml, /wx:for="\{\{receipt.breakdownRows\}\}" wx:key="index"/);
  assert.match(wxml, /\{\{item.label\}\}/);
  assert.match(wxml, /\{\{item.value\}\}/);
  assert.doesNotMatch(wxml, /receipt\.breakdown\./);
});
