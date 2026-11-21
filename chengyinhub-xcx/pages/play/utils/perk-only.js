// FE-G7/G8/G9:自由探索主题里**每一家**都没有玩法(纯权益探店)时,游戏读数(已完成环、章节读数、
// 故事流、小瘾说、完成横幅、「通关卡」字样)都不出。服务端下发了主题级布尔就只认它;没下发时按节点推:
// 有商家站、且每个商家站都既没有答题模板(hasGame)也没有高级玩法(hasAdvanced)。
function perkOnlyOf(mode, nodes, serverFlag) {
  if (Number(mode) !== 2) return false;
  if (typeof serverFlag === 'boolean') return serverFlag;
  const shops = (nodes || []).filter((n) => n && Number(n.merchantId) > 0);
  return shops.length > 0 && shops.every((n) => !n.hasGame && !n.hasAdvanced);
}

// FE-G5:本店权益的展示条款,来自 /api/play/nodes 每个节点 perk 上的 included / extraSpendText / reserveRequired
// (PerkDisplayTerms.put)。与主题页参与商家卡同一说法;哪项没下发就不写哪项,全缺返回空串。
function perkTermsText(perk) {
  const p = perk || {};
  const extra = typeof p.extraSpendText === 'string' ? p.extraSpendText.trim() : '';
  const parts = [];
  if (p.included === true) parts.push('已含');
  else if (extra) parts.push('需消费 ' + extra);
  if (p.reserveRequired === true) parts.push('需预约');
  return parts.join(' · ');
}

module.exports = { perkOnlyOf, perkTermsText };
