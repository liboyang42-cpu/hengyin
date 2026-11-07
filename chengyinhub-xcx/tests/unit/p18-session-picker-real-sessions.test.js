'use strict';
// P-18 / P-30:选场次只列真场次(按开始时间),发布时自动建的两个月档期不当一场;只剩档期时写「随时可玩」。
const test = require('node:test');
const assert = require('node:assert/strict');

global.getApp = () => ({ sendRequest() {} });
global.Component = () => {};
const picker = require('../../pages/topic/components/cy/session-picker/index.js');

const windowShell = { id: 1, name: '外滩钟声', startDate: '2026-11-02 00:00:00', endDate: '2026-12-31 23:59:59' };
const nov3 = { id: 3, name: '外滩钟声', startDate: '2026-11-03 14:05:00', endDate: '2026-11-03 18:00:00' };
const nov2 = { id: 2, name: '外滩钟声', startDate: '2026-11-02 13:35:00', endDate: '2026-11-02 17:35:00' };

test('有真场次时去掉档期,并按开始时间排', () => {
  const rows = picker.sessionRows([windowShell, nov3, nov2]);
  assert.deepEqual(rows.map((r) => r.id), [2, 3]);
});

test('只有一个真场次时只剩它(不再让人在两个「周一 11.2」里选)', () => {
  const rows = picker.sessionRows([windowShell, nov2]);
  assert.deepEqual(rows.map((r) => r.id), [2]);
  assert.match(picker.singleDayText(rows[0]), /13:35 集合/);
});

test('只有档期时保留它,写「随时可玩 · 至 12.31」而不是 0 点', () => {
  const rows = picker.sessionRows([windowShell]);
  assert.deepEqual(rows.map((r) => r.id), [1]);
  assert.equal(picker.singleDayText(rows[0]), '随时可玩 · 至 12.31');
  assert.equal(picker.isWindow(nov2), false);
});
