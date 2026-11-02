// P-29(2026-11-02 走查):一章的收尾文字要在下一章的章节卡之前读到。
//
// 故事流在「第一个未完成节点」处截断(openChapterFull);本章最后一站一完成,下一站就落到下一章,
// 渲染那一步立刻把 chapter 换成下一章 —— 本章最后一个节点块之后的文字从来没有机会铺出来。
// 这里只判「要不要先停在本章」:本章最后一个节点块之后还有能读的块,本章各站都完成了,且这段收尾还没读过。

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
 * 换章前要不要先停在本章读收尾。
 * @param {object} chapter 当前(旧)章:{ id, blocks, opening, ending }
 * @param {Array} nodes 本页节点(含 done)
 * @param {object} read 已读过收尾的章 id 表
 */
function holdForTrail(chapter, nodes, read) {
  if (!chapter || chapter.id == null || chapter.opening || chapter.ending) return false;
  if (read && read[String(chapter.id)]) return false;
  const blocks = chapter.blocks;
  if (!hasTrailingContent(blocks)) return false;
  const list = Array.isArray(nodes) ? nodes : [];
  return blocks.filter((b) => b && b.type === 'node').every((b) => {
    const n = list.find((x) => x && String(x.nodeId) === String(b.nodeId));
    return !!(n && n.done);
  });
}

module.exports = { hasTrailingContent, holdForTrail };
