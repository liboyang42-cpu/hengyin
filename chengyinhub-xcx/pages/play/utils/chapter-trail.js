// P-29(2026-11-02 走查):一章的收尾文字要在下一章的章节卡之前读到。
//
// 故事流在「第一个未完成节点」处截断(openChapterFull);本章最后一站一完成,下一站就落到下一章,
// 渲染那一步立刻把 chapter 换成下一章 —— 本章最后一个节点块之后的文字从来没有机会铺出来。
// 这里只判「要不要先停在本章」:本章最后一个节点块之后还有能读的块,本章各站都完成了,且这段收尾还没读过。
// 注意服务端只把块流下发到第一个未完成节点,所以要分清「真没有收尾」和「收尾还没到手」。

const READABLE = ['text', 'image', 'audio', 'reveal', 'voice', 'dream'];

function readable(block) {
  if (!block || READABLE.indexOf(block.type) < 0) return false;
  if (block.type === 'image' || block.type === 'audio') return !!block.url;
  if (block.type === 'dream') return Array.isArray(block.images) && block.images.some((im) => im && im.url);
  return String(block.content || '').trim().length > 0;
}

/** 本章最后一个节点块之后有能读的块 */
function hasTrailingContent(blocks) {
  if (!Array.isArray(blocks)) return false;
  let last = -1;
  blocks.forEach((b, i) => { if (b && b.type === 'node') last = i; });
  if (last < 0) return false;
  return blocks.slice(last + 1).some(readable);
}

/**
 * 刚走完的那一章换章前怎么办:'hold' 先停下读收尾;'unknown' 收尾还没到手(块流是这一章最后一站
 * 完成前拉的,服务端只下发到第一个未完成节点),先安静停着等重拉;'none' 直接换章。
 * @param {object} card 刚走完的那一章(章节卡,含 blocks)
 * @param {Array} nodes 本页节点(含 done)
 * @param {object} read 已读过收尾的章 id 表
 * @param {Array} doneAtLoad 拉章节卡那一刻已完成的节点 id
 */
function trailState(card, nodes, read, doneAtLoad) {
  if (!card || card.id == null || card.opening || card.ending) return 'none';
  if (read && read[String(card.id)]) return 'none';
  const blocks = Array.isArray(card.blocks) ? card.blocks : [];
  const ids = blocks.filter((b) => b && b.type === 'node').map((b) => String(b.nodeId));
  if (!ids.length) return 'none';
  const list = Array.isArray(nodes) ? nodes : [];
  if (!ids.every((id) => list.some((n) => n && String(n.nodeId) === id && n.done))) return 'none';
  const loaded = Array.isArray(doneAtLoad) ? doneAtLoad.map(String) : [];
  if (!ids.every((id) => loaded.indexOf(id) >= 0)) return 'unknown';
  return hasTrailingContent(blocks) ? 'hold' : 'none';
}

/** 选章只认本次服务端节点/历史投影；缺节点或未知章节不能推断为已完成。 */
function chapterReadState(card, nodes, history) {
  if (!card || card.id == null || card.locked === true) return 'locked';
  if (card.opening) return card.complete === true ? 'completed' : (card.ready === true ? 'available' : 'locked');
  const byId = new Map();
  (history || []).forEach(n => { if (n && n.done === true) byId.set(String(n.nodeId), n); });
  (nodes || []).forEach(n => { if (n) byId.set(String(n.nodeId), n); });
  const chapterNodes = Array.from(byId.values()).filter(n => String(n.chapterId) === String(card.id));
  const ids = (card.blocks || []).filter(b => b && b.type === 'node').map(b => String(b.nodeId));
  if (chapterNodes.length && chapterNodes.every(n => n.done === true)
      && ids.every(id => byId.has(id) && byId.get(id).done === true)) return 'completed';
  return chapterNodes.some(n => n.done !== true && !n.paused && !n.locked && n.playable !== false
    && (!n.routeNodeState || n.routeNodeState === 'PLAYABLE')) ? 'available' : 'locked';
}

module.exports = { hasTrailingContent, trailState, chapterReadState };
