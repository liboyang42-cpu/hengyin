'use strict';

const AUDIT_STATES = ['DRAFT', 'APPROVED', 'REJECTED', 'REVIEWING', 'UNKNOWN'];
const MARKET_STATES = ['NONE', 'PENDING', 'ONLINE', 'DELISTED', 'RESUBMIT', 'UNKNOWN'];

// U-046:my-list 直接下发 auditState / marketState(服务端 TemplateMarketState 唯一口径)。
// 下面的推导只给尚未下发枚举的旧后端兜底,规则与服务端逐条一致(tests/fixtures/template-market-state-cases.json 共测)。
function is(value, expected) { return value === expected || value === String(expected); }

function deriveAuditState(t) {
  if (is(t.draftStatus, 0)) return 'DRAFT';
  if (!is(t.draftStatus, 1)) return 'UNKNOWN';
  if (is(t.status, 1) && is(t.checkStatus, 1)) return 'APPROVED';
  if (is(t.status, 2) || is(t.checkStatus, 2)) return 'REJECTED';
  if (is(t.status, 0) || is(t.checkStatus, 0)) return 'REVIEWING';
  return 'UNKNOWN';
}

function deriveMarketState(t) {
  if (t.marketTemplateId == null || is(t.marketTemplateId, 0)) return 'NONE';
  if (!/^[1-9]\d*$/.test(String(t.marketTemplateId))) return 'UNKNOWN';
  if (is(t.marketStatus, 0)) return 'PENDING';
  if (is(t.marketStatus, 1) && is(t.marketPublishStatus, 1)) return 'ONLINE';
  if (is(t.marketStatus, 1) && is(t.marketPublishStatus, 0)) return 'DELISTED';
  if (is(t.marketStatus, 2)) return 'RESUBMIT';
  return 'UNKNOWN';
}

function auditStateOf(t) {
  return AUDIT_STATES.indexOf(t.auditState) >= 0 ? t.auditState : deriveAuditState(t);
}

function marketStateOf(t) {
  return MARKET_STATES.indexOf(t.marketState) >= 0 ? t.marketState : deriveMarketState(t);
}

// 两个个人玩法入口共用 my-list 的个人审核与广场投影字段，不以个人 publishStatus 推断广场状态。
function marketView(t) {
  t = t || {};
  const audit = auditStateOf(t);
  const marketState = marketStateOf(t);
  const draft = audit === 'DRAFT';
  const approved = audit === 'APPROVED';
  const rejected = audit === 'REJECTED';
  const reviewing = audit === 'REVIEWING';
  const noMarket = marketState === 'NONE';
  const pending = marketState === 'PENDING';
  const online = marketState === 'ONLINE';
  const delisted = marketState === 'DELISTED';
  const resubmit = marketState === 'RESUBMIT';
  return {
    _statusText: draft ? '草稿' : approved ? '个人审核通过' : rejected ? '个人审核未通过'
      : reviewing ? '个人审核中' : '状态待确认',
    _stateClass: draft ? 'st-draft' : approved ? 'st-online' : rejected ? 'st-rejected' : 'st-pending',
    _marketText: noMarket ? '未提交广场' : pending ? '广场审核中' : online ? '广场已上架'
      : delisted ? '广场已下架' : resubmit ? '广场需重新提交' : '—',
    _canSubmitMarket: approved && (noMarket || resubmit),
    _canToggleMarket: pending || online || (approved && delisted),
    _marketActionText: pending ? '撤回广场审核' : online ? '从广场下架' : '上架广场',
    _marketPublishTarget: pending || online ? 0 : 1
  };
}

module.exports = { marketView, deriveAuditState, deriveMarketState };
