'use strict';
/* 完成页勋章堆(2026-10-22 业主:主题/玩法结束时多枚勋章叠放、可切换)。
   只放真拿到的:① 主题通关徽章 —— 后端 /api/play/nodes 的 reward.finishMedalName(全部完成才算);
   ② 本局真夺到的站点名次奖牌 —— 打卡回包 medalRank > 0 时记下的那些,且该站仍是已完成。
   没有就返回空数组,完成页整块不出。 */
function buildFinishMedals(opts) {
  const o = opts || {};
  const out = [];
  const reward = o.reward || {};
  if (o.allDone && reward.finishMedalName) {
    out.push({ key: 'theme', name: String(reward.finishMedalName), img: reward.finishMedalImg || '', sub: '通关 · 主题徽章', glyph: 'flag' });
  }
  const doneIds = {};
  (o.nodes || []).forEach((n) => { if (n && n.done) doneIds[String(n.nodeId)] = true; });
  const seen = {};
  (o.won || []).forEach((m) => {
    if (!m || !doneIds[String(m.nodeId)] || seen[String(m.nodeId)]) return;
    seen[String(m.nodeId)] = true;
    const rank = Number(m.rank) > 0 ? Number(m.rank) : 0;
    out.push({ key: 'node-' + m.nodeId, name: m.name || '专属徽章', img: m.img || '', sub: (rank ? '第 ' + rank + ' 名 · ' : '') + '站点奖牌', glyph: 'star' });
  });
  return out;
}

module.exports = { buildFinishMedals };
