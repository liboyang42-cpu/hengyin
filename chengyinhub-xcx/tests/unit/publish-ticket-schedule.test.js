const { test } = require('node:test');
const assert = require('node:assert/strict');
const { cityOrientationScheduleIssues, freeExplorationSaleScheduleIssues, freeExplorationValidityScheduleIssues } = require('../../pages/publish/utils/publish/publish-ticket-schedule.js');

test('自由探索有效期拒绝倒序、相等时刻和无效日期，旧日票按整日保留', () => {
  for (const endTime of ['2026-10-05 18:00', '2026-10-06 09:00']) {
    assert.deepEqual(freeExplorationValidityScheduleIssues({ mode: 2, startTime: '2026-10-06 09:00:00', endTime }),
      [{ field: 'endTime', message: '票单结束时间必须晚于开始时间' }]);
  }
  assert.deepEqual(freeExplorationValidityScheduleIssues({ mode: '2', startTime: '2026-10-06 09:00', endTime: '2026-13-07' }),
    [{ field: 'endTime', message: '票单结束日期无效' }]);
  assert.deepEqual(freeExplorationValidityScheduleIssues({ mode: 2, startTime: '2026-10-06', endTime: '2026-10-06' }), []);
  assert.deepEqual(freeExplorationValidityScheduleIssues({ mode: 2, startTime: '2026-10-06 09:00', endTime: '2026-10-06 18:00' }), []);
});

test('自由探索售票日期拒绝倒序和非法日历，允许同一天售票', () => {
  assert.deepEqual(freeExplorationSaleScheduleIssues({ mode: '2', saleStartTime: '2026-10-05', saleEndTime: '2026-10-04' }),
    [{ field: 'saleEndTime', message: '售票结束日期不能早于开始日期' }]);
  assert.deepEqual(freeExplorationSaleScheduleIssues({ mode: 2, saleStartTime: '2026-02-30', saleEndTime: '2026-10-06' }),
    [{ field: 'saleStartTime', message: '售票开始日期无效' }]);
  assert.deepEqual(freeExplorationSaleScheduleIssues({ mode: 2, saleStartTime: '2026-10-05', saleEndTime: '2026-13-06' }),
    [{ field: 'saleEndTime', message: '售票结束日期无效' }]);
  assert.deepEqual(freeExplorationSaleScheduleIssues({ mode: 2, saleStartTime: '2026-10-05', saleEndTime: '2026-10-05' }), []);
  assert.deepEqual(freeExplorationSaleScheduleIssues({ mode: 1, saleStartTime: '2026-10-05', saleEndTime: '2026-10-04' }), []);
});

test('城市定向缺少集合起止时间会分别提示（数字或字符串 mode）', () => {
  assert.deepEqual(cityOrientationScheduleIssues({ mode: 1, startTime: '', endTime: '' }), [
    { field: 'startTime', message: '请选择集合开始时间' },
    { field: 'endTime', message: '请选择集合结束时间' }
  ]);
  assert.deepEqual(cityOrientationScheduleIssues({ mode: '1', startTime: '', endTime: '' }), [
    { field: 'startTime', message: '请选择集合开始时间' },
    { field: 'endTime', message: '请选择集合结束时间' }
  ]);
});

test('城市定向集合结束不得早于或等于集合开始', () => {
  assert.deepEqual(cityOrientationScheduleIssues({
    mode: 1,
    startTime: '2026-07-22 10:00',
    endTime: '2026-07-22 10:00'
  }), [{ field: 'endTime', message: '集合结束时间必须晚于开始时间' }]);
});

test('城市定向有效集合时间与自由定向不产生错误', () => {
  assert.deepEqual(cityOrientationScheduleIssues({
    mode: 1,
    startTime: '2026-07-22 10:00',
    endTime: '2026-07-22 12:00'
  }), []);
  assert.deepEqual(cityOrientationScheduleIssues({ mode: 2, startTime: '', endTime: '' }), []);
});
