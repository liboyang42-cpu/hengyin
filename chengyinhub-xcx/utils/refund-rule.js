// 2026-11-03 业主:玩家看到的退款说明一律是平台三档,与后端 RefundPolicy 同口径。
// 票上存的 refund_rule 是发布者当初写的自由文本(如「开场前24小时可退」),会与三档相抵,
// 玩家视图不再显示它;存量数据不动。免费票没有「退多少」可言,不写。
function refundRuleText(ticket) {
  if (!ticket || !(Number(ticket.price) > 0)) return '';
  const anchor = Number(ticket.mode) === 2 ? '开始前' : '集合前';
  return anchor + ' 24 小时以上全额退;24 小时内退 50%;开始后不退';
}

module.exports = { refundRuleText };
