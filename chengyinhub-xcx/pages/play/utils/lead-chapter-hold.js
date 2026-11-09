/**
 * 带领场的章节放行(P-08):队长还没放行到这一章时,玩家停在章节门前看到「等队长放行」,
 * 而不是先玩一遍再被服务端静默拒绝。放行规则本身不变 —— 真正的闸仍在服务端
 * (PlayNodeAccessService.leadGateError:本章排序在队长当前章之后 → 拒绝);这里只是提前讲清楚。
 *
 * 纯函数,可单测。章节先后按本页章节卡顺序(开场 / 结局章不参与),与服务端按 sortId 比较同序。
 */
function leadHoldsChapter(lead, chapterId, cards) {
  if (!lead || !lead.exists || Number(lead.status) !== 2) return false;
  const current = lead.currentChapterId;
  if (current == null || current === '' || chapterId == null || chapterId === '') return false;
  if (String(current) === String(chapterId)) return false;
  const order = (cards || []).filter((c) => c && !c.opening && !c.ending).map((c) => String(c.id));
  const mine = order.indexOf(String(chapterId));
  const released = order.indexOf(String(current));
  if (mine < 0 || released < 0) return false;
  return mine > released;
}

/** P-35:队长放行了新的一章 → 给玩家一句。首次读到(之前没有)不算放行。 */
function releaseCue(prevLead, nextLead) {
  if (!prevLead || !nextLead || !prevLead.exists || !nextLead.exists) return '';
  const before = prevLead.currentChapterId;
  const after = nextLead.currentChapterId;
  if (before == null || after == null || String(before) === String(after)) return '';
  const name = String(nextLead.chapterName || '').trim();
  return name ? '队长放行了「' + name + '」' : '队长放行了下一章';
}

module.exports = { leadHoldsChapter, releaseCue };
