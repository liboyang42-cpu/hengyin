// BL-153（核销记录部分）：pages/merchant/verify-records/view-model.js 的 shapeRecord 单条映射用例
// 依赖 #119（BL-76），须在其后合并；不改被测代码。
// 字段口径：CmsRegistrationScan（status Long 0 未验证/1 已验证；validationMethod 1/2/3；
// createTime 走 BaseEntity @JsonFormat "yyyy-MM-dd HH:mm:ss"）。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { shapeRecord } = require('../../pages/merchant/verify-records/view-model.js');

test('shapeRecord: 完整行映射', () => {
  assert.deepEqual(shapeRecord({
    id: 11, registrationId: 42, name: ' 一号节点 ', status: 1, validationMethod: 2,
    createTime: '2026-10-15 09:30:12',
  }), {
    id: '11',
    nameText: '一号节点',
    registrationText: '报名 #42',
    methodText: '图片验证',
    statusKey: 'VERIFIED',
    statusText: '已核销',
    statusTone: 'success',
    dateKey: '2026-10-15',
    timeText: '09:30',
  });
});

test('shapeRecord: 空行 / 非对象 / 缺 id 返回 null', () => {
  for (const v of [null, undefined, 'x', 3, {}, { id: '' }, { id: '  ' }, { id: null, name: 'a' }]) {
    assert.equal(shapeRecord(v), null, JSON.stringify(v));
  }
});

test('shapeRecord: 未知 status 兜底为「未核销」（只有 1 / "1" 算已核销）', () => {
  for (const status of [0, '0', 2, -1, null, undefined, 'VERIFIED', true]) {
    const row = shapeRecord({ id: 1, status });
    if (status === true) {
      // 现状记录：Number(true) === 1；后端 status 为 Long，不会返回布尔
      assert.equal(row.statusKey, 'VERIFIED');
      continue;
    }
    assert.equal(row.statusKey, 'UNVERIFIED', String(status));
    assert.equal(row.statusText, '未核销');
    assert.equal(row.statusTone, 'neutral');
  }
  assert.equal(shapeRecord({ id: 1, status: '1' }).statusKey, 'VERIFIED');
});

test('shapeRecord: 缺字段兜底（名称、报名号、验证方式、时间）', () => {
  const row = shapeRecord({ id: 'r1' });
  assert.equal(row.id, 'r1');
  assert.equal(row.nameText, '未命名节点');
  assert.equal(row.registrationText, '');
  assert.equal(row.methodText, '');
  assert.equal(row.dateKey, '');
  assert.equal(row.timeText, '');
});

test('shapeRecord: 非法报名号与未知验证方式不展示', () => {
  for (const registrationId of [0, -1, 'abc', 1.5, Number.MAX_SAFE_INTEGER + 2]) {
    assert.equal(shapeRecord({ id: 1, registrationId }).registrationText, '', String(registrationId));
  }
  assert.equal(shapeRecord({ id: 1, registrationId: '7' }).registrationText, '报名 #7');
  assert.equal(shapeRecord({ id: 1, validationMethod: 4 }).methodText, '');
  assert.equal(shapeRecord({ id: 1, validationMethod: '3' }).methodText, '选项验证');
  assert.equal(shapeRecord({ id: 1, validationMethod: 1 }).methodText, '文字验证');
});

test('shapeRecord: createTime 只取日期与时分；ISO 形式同样能切出', () => {
  const row = shapeRecord({ id: 1, createTime: '2026-10-15T23:59:59' });
  assert.equal(row.dateKey, '2026-10-15');
  assert.equal(row.timeText, '23:59');
});
