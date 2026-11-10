'use strict';
// qx-sm 特征测试：协作邀约状态（0 待确认 1 已接受 2 已拒绝 3 已取消 4 已顶替 5 已过期）在重构前后的展示输出必须逐字不变。
const test = require('node:test');
const assert = require('node:assert/strict');
const view = require('../../utils/coop-invite-view.js');

const CODES = [0, 1, 2, 3, 4, 5, '0', '1', '5', null, undefined, 6, -1, 'x'];
const EXPECTED_TEXT = ['待确认', '已接受', '已拒绝', '已取消', '已顶替', '已过期'];
const EXPECTED_BADGE = [
  { badgeTone: 'pending', badgeIcon: 'clock' },
  { badgeTone: 'accepted', badgeIcon: 'check' },
  { badgeTone: 'rejected', badgeIcon: 'warning' },
  { badgeTone: 'muted', badgeIcon: 'close-sm' },
  { badgeTone: 'muted', badgeIcon: 'close-sm' },
  { badgeTone: 'muted', badgeIcon: 'close-sm' },
];

test('STATUS_LABEL / INVITE_BADGE 导出值逐项不变', () => {
  assert.deepEqual(Array.from(view.STATUS_LABEL), EXPECTED_TEXT);
  assert.deepEqual(Array.from(view.INVITE_BADGE),
    ['pending:clock', 'accepted:check', 'rejected:warning', 'muted:close-sm', 'muted:close-sm', 'muted:close-sm']);
});

test('decorate 的 statusText 与徽章对每个状态码（含字符串/null/未知）不变', () => {
  for (const code of CODES) {
    const [row] = view.decorate([{ id: 7, status: code }]);
    const idx = typeof code === 'number' || typeof code === 'string' ? code : undefined;
    const known = idx !== undefined && Object.prototype.hasOwnProperty.call(EXPECTED_TEXT, idx);
    assert.equal(row.statusText, known ? EXPECTED_TEXT[idx] : '', 'status=' + String(code));
    const badge = known ? EXPECTED_BADGE[idx] : { badgeTone: 'muted', badgeIcon: 'close-sm' };
    assert.equal(row.badgeTone, badge.badgeTone, 'tone status=' + String(code));
    assert.equal(row.badgeIcon, badge.badgeIcon, 'icon status=' + String(code));
  }
});
