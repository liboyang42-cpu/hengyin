'use strict';
// 第十轮 f1 item 8(j6 F6):后端 payExpireTime 改为带时分秒上线(RegistrationPayExpireTimeJsonTest 钉线上格式),
// 订单详情按原样显示截止时刻;只给日期时会被读成当天 00:00 —— 那正是旧的错话。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { formatRefundDeadline } = require('../../utils/order-status.js');

test('待支付截止:线上「yyyy-MM-dd HH:mm:ss」显示到分钟,不落成 00:00', () => {
  assert.equal(formatRefundDeadline('2026-11-04 13:15:29'), '2026-11-04 13:15');
  // R11 g3(j3 F12,业主):date-only 值没有钟点,不再补一个假的 00:00
  assert.equal(formatRefundDeadline('2026-11-04'), '2026-11-04', 'date-only 不显示 00:00');
  const detail = fs.readFileSync(path.join(__dirname, '../../subpackageMember/components/cy/scene-member-order-detail/index.js'), 'utf8');
  assert.match(detail, /const expire = toDisplayTime\(info\.payExpireTime\);\s*info\.orderHint = expire \? \('请在 ' \+ expire \+ ' 前完成支付'\)/);
});
