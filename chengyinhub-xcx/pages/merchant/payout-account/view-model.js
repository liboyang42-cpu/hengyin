// l-payout 收款账户:把 /api/merchant/payout-account 的服务端事实转成页面状态。
// 只展示服务端给的事实:未开通给不可用原因,签约链接只在需要本人操作时出现,商户号只给掩码。
const STATE_TEXT = {
  NONE: '未开通',
  DRAFT: '待提交资料',
  CHECKING: '资料校验中',
  ACCOUNT_NEED_VERIFY: '待验证账户',
  AUDITING: '审核中',
  REJECTED: '已驳回',
  NEED_SIGN: '待签约',
  FINISH: '已开通',
  FROZEN: '已冻结',
  CANCELED: '已作废',
};

const STATE_TONE = {
  FINISH: 'success',
  REJECTED: 'danger',
  FROZEN: 'danger',
  CANCELED: 'neutral',
  NEED_SIGN: 'warning',
  ACCOUNT_NEED_VERIFY: 'warning',
};

function pad(n) { return n < 10 ? '0' + n : '' + n; }

function timeText(value) {
  if (value === undefined || value === null || value === '') return '';
  const d = new Date(typeof value === 'string' ? value.replace(' ', 'T') : value);
  if (isNaN(d.getTime())) return '';
  return `${d.getMonth() + 1}月${d.getDate()}日 ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function shapeAccount(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  if (data.available !== true) return { pageState: 'unavailable' };
  const state = typeof data.state === 'string' && STATE_TEXT[data.state] ? data.state : null;
  if (!state) return null;
  if (state === 'NONE') return { pageState: 'none' };
  const signUrl = typeof data.signUrl === 'string' && data.signUrl.indexOf('https://') === 0
    && (state === 'NEED_SIGN' || state === 'ACCOUNT_NEED_VERIFY') ? data.signUrl : '';
  return {
    pageState: 'ready',
    state,
    stateText: STATE_TEXT[state],
    stateTone: STATE_TONE[state] || 'neutral',
    // 已开通时微信的状态描述只是「完成」之类,与状态重复,不再显示。
    stateDesc: state !== 'FINISH' && typeof data.stateDesc === 'string' ? data.stateDesc : '',
    signUrl,
    subMchid: (state === 'FINISH' || state === 'FROZEN') && typeof data.subMchidMasked === 'string' ? data.subMchidMasked : '',
    checkedText: timeText(data.checkedAt),
  };
}

module.exports = { STATE_TEXT, shapeAccount, timeText };
