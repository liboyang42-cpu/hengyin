// FE-G7/G8/G9:自由探索主题里**每一家**都没有玩法(纯权益探店)时,游戏读数(已完成环、章节读数、
// 故事流、小瘾说、完成横幅、「通关卡」字样)都不出。服务端下发了主题级布尔就只认它;没下发时按节点推:
// 有商家站、且每个商家站都既没有答题模板(hasGame)也没有高级玩法(hasAdvanced)。
function perkOnlyOf(mode, nodes, serverFlag) {
  if (Number(mode) !== 2) return false;
  if (typeof serverFlag === 'boolean') return serverFlag;
  const shops = (nodes || []).filter((n) => n && Number(n.merchantId) > 0);
  return shops.length > 0 && shops.every((n) => !n.hasGame && !n.hasAdvanced);
}

module.exports = { perkOnlyOf };
