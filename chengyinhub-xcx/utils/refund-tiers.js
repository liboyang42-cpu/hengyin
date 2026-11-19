// 2026-11-03 业主:玩家看到的退款说明一律是平台三档,与后端 RefundPolicy 同口径。
// 票上存的 refund_rule 是发布者当初写的自由文本(如「开场前24小时可退」),会与三档相抵,
// 玩家视图不再显示它;存量数据不动。免费票没有「退多少」可言,不写。
// OR-022:三档文案只此一份 —— 主题详情、场次选择、报名结算页、取消确认都从这里取。
function anchorText(mode) {
  return Number(mode) === 2 ? '开始前' : '集合前';
}

/** 三档明细行(结算页逐行列出);免费票返回空数组。 */
function refundRuleTiers(ticket) {
  if (!ticket || !(Number(ticket.price) > 0)) return [];
  const anchor = anchorText(ticket.mode);
  return [
    { key: 'full', when: anchor + ' 24 小时以上', result: '全额退' },
    { key: 'half', when: anchor + ' 24 小时内', result: '退 50%' },
    { key: 'none', when: '开始后', result: '不退' },
  ];
}

/** 一句话版本(主题详情、场次选择、取消确认兜底)。 */
function refundRuleText(ticket) {
  if (!ticket || !(Number(ticket.price) > 0)) return '';
  const anchor = anchorText(ticket.mode);
  return anchor + ' 24 小时以上全额退；24 小时内退 50%；开始后不退';
}

module.exports = { refundRuleText, refundRuleTiers };
