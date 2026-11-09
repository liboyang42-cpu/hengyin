// BL-153 / M-03:pages/merchant/verify-records/view-model.js 的 shapeRecord 单条映射。
// 字段口径:MerchantRedemptionView(与「核销与退款」同源)。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { shapeRecord } = require('../../pages/merchant/verify-records/view-model.js');

test('shapeRecord: 完整行映射', () => {
  assert.deepEqual(shapeRecord({
    recordKey: 'redemption:11', recordId: '11', chapterName: ' 一号节点 ', topicName: '主题', fulfillmentState: 'ACTIVE',
    occurredAt: '2026-10-15 09:30:12',
  }), {
    id: 'redemption:11', nameText: '一号节点', subText: '主题',
    statusKey: 'VERIFIED', statusText: '已核销', statusTone: 'success', dateKey: '2026-10-15', timeText: '09:30',
  });
});

test('shapeRecord: 空行 / 非对象 / 缺 id 返回 null', () => {
  for (const v of [null, undefined, 'x', 3, {}, { recordId: '' }, { recordKey: '  ' }, { recordId: null, chapterName: 'a' }]) {
    assert.equal(shapeRecord(v), null, JSON.stringify(v));
  }
});

test('shapeRecord: 只有 REVERSED 算撤销,其余都是已核销', () => {
  for (const s of ['ACTIVE', null, undefined, 'x']) assert.equal(shapeRecord({ recordId: 1, fulfillmentState: s }).statusKey, 'VERIFIED');
  assert.equal(shapeRecord({ recordId: 1, fulfillmentState: 'REVERSED' }).statusText, '已撤销');
});

test('shapeRecord: 缺字段兜底(名称、主题、时间)', () => {
  const row = shapeRecord({ recordId: 'r1' });
  assert.equal(row.nameText, '本店站点');
  assert.equal(row.subText, '');
  assert.equal(row.dateKey, '');
  assert.equal(row.timeText, '');
  assert.notEqual(row.nameText, '未命名节点');
});
