'use strict';

// BL-153:为 #119(BL-76)verify-records/view-model.js 的 shapeRecord 补单条映射用例,不改被测代码。
// 依赖 #119,须在其后合并。

const test = require('node:test');
const assert = require('node:assert/strict');

const { shapeRecord } = require('../../pages/merchant/verify-records/view-model.js');

test('shapeRecord:完整行映射名称、报名号、验证方式、状态与日期/时间', () => {
  assert.deepEqual(shapeRecord({
    id: 7, registrationId: 1203, name: ' 老城门 ', status: 1, validationMethod: 2, createTime: '2026-10-15 14:32:08',
  }), {
    id: '7',
    nameText: '老城门',
    registrationText: '报名 #1203',
    methodText: '图片验证',
    statusKey: 'VERIFIED',
    statusText: '已核销',
    statusTone: 'success',
    dateKey: '2026-10-15',
    timeText: '14:32',
  });
});

test('shapeRecord:非对象或缺 id / 空白 id → null', () => {
  for (const row of [null, undefined, 0, 'x', {}, { id: '' }, { id: '   ' }, { id: null }]) {
    assert.equal(shapeRecord(row), null, JSON.stringify(row));
  }
});

test('shapeRecord:status 只有 1(数字或字符串)算已核销,其余与缺失都是未核销', () => {
  assert.equal(shapeRecord({ id: 1, status: '1' }).statusKey, 'VERIFIED');
  for (const status of [0, '0', 2, -1, null, undefined, 'VERIFIED']) {
    const r = shapeRecord({ id: 1, status });
    assert.equal(r.statusKey, 'UNVERIFIED', String(status));
  }
  const unverified = shapeRecord({ id: 1, status: 0 });
  assert.equal(unverified.statusText, '未核销');
  assert.equal(unverified.statusTone, 'neutral');
});

test('shapeRecord:缺字段兜底——无名称写「未命名据点」,报名号/方式/时间为空串而不是 undefined', () => {
  const r = shapeRecord({ id: 'r-9' });
  assert.equal(r.id, 'r-9');
  assert.equal(r.nameText, '未命名据点');
  assert.equal(r.registrationText, '');
  assert.equal(r.methodText, '');
  assert.equal(r.dateKey, '');
  assert.equal(r.timeText, '');
});

test('shapeRecord:报名号只认正安全整数;未知验证方式不编文案', () => {
  for (const registrationId of [0, -3, 1.5, 'abc', Number.MAX_SAFE_INTEGER + 2]) {
    assert.equal(shapeRecord({ id: 1, registrationId }).registrationText, '', String(registrationId));
  }
  assert.equal(shapeRecord({ id: 1, registrationId: '88' }).registrationText, '报名 #88');
  assert.equal(shapeRecord({ id: 1, validationMethod: '1' }).methodText, '文字验证');
  assert.equal(shapeRecord({ id: 1, validationMethod: 3 }).methodText, '选项验证');
  assert.equal(shapeRecord({ id: 1, validationMethod: 9 }).methodText, '');
});
