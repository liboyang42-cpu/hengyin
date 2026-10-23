'use strict';

// 两个个人玩法入口共用 my-list 的个人审核与广场投影字段，不以个人 publishStatus 推断广场状态。
function marketView(t) {
  t = t || {};
  const is = (value, expected) => value === expected || value === String(expected);
  const draft = is(t.draftStatus, 0);
  const submitted = is(t.draftStatus, 1);
  const approved = submitted && is(t.status, 1) && is(t.checkStatus, 1);
  const rejected = submitted && (is(t.status, 2) || is(t.checkStatus, 2));
  const reviewing = submitted && (is(t.status, 0) || is(t.checkStatus, 0));
  const market = /^[1-9]\d*$/.test(String(t.marketTemplateId || ''));
  const noMarket = t.marketTemplateId == null || is(t.marketTemplateId, 0);
  const pending = market && is(t.marketStatus, 0);
  const online = market && is(t.marketStatus, 1) && is(t.marketPublishStatus, 1);
  const delisted = market && is(t.marketStatus, 1) && is(t.marketPublishStatus, 0);
  return {
    _statusText: draft ? '草稿' : approved ? '个人审核通过' : rejected ? '个人审核未通过'
      : reviewing ? '个人审核中' : '状态待确认',
    _stateClass: draft ? 'st-draft' : approved ? 'st-online' : rejected ? 'st-rejected' : 'st-pending',
    _marketText: noMarket ? '未提交广场' : pending ? '广场审核中' : online ? '广场已上架'
      : delisted ? '广场已下架' : market && is(t.marketStatus, 2) ? '广场需重新提交' : '—',
    _canSubmitMarket: approved && (noMarket || (market && is(t.marketStatus, 2))),
    _canToggleMarket: pending || online || (approved && delisted),
    _marketActionText: pending ? '撤回广场审核' : online ? '从广场下架' : '上架广场',
    _marketPublishTarget: pending || online ? 0 : 1
  };
}

module.exports = { marketView };
