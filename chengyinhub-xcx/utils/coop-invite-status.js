/**
 * 协作邀约 coop_invite.status 显式状态机（与后端 CoopInvite.status 同值域）。
 * 迁移由服务端执行：待确认 → 已接受 / 已拒绝 / 已取消 / 已顶替 / 已过期；其余为终态。
 */
'use strict';
const { createStatusMachine } = require('./state-machine.js');

const COOP_INVITE_STATUS = createStatusMachine({
  name: 'coopInvite',
  states: [
    { code: 0, key: 'pending', label: '待确认', badge: 'pending:clock' },
    { code: 1, key: 'accepted', label: '已接受', badge: 'accepted:check' },
    { code: 2, key: 'rejected', label: '已拒绝', badge: 'rejected:warning' },
    { code: 3, key: 'cancelled', label: '已取消', badge: 'muted:close-sm' },
    { code: 4, key: 'replaced', label: '已顶替', badge: 'muted:close-sm' },
    { code: 5, key: 'expired', label: '已过期', badge: 'muted:close-sm' },
  ],
  transitions: {
    pending: ['accepted', 'rejected', 'cancelled', 'replaced', 'expired'],
  },
});

module.exports = { COOP_INVITE_STATUS };
