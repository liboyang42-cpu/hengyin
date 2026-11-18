'use strict';
/**
 * 自由探索(mode 2)票过期后的只读集章册(FE-STORY 7.4)。纯函数,已单测。
 *
 * /api/play/nodes 回 402 或 playable:false 时,改读 /api/play/history(不带 routeSessionId)。
 * 服务端对自由探索返回 { readOnly:true, mode:2, storyUnlocked, storyTotal, chapters, nodes },
 * 里面**只有**已到访的章节和地点。这里把它拼成 /nodes 同形的回包:已到访的照常可读;
 * 本地缓存(fe-story-cache)里见过、但历史里没有的地点与章节一律按锁定显示(不带正文)。
 * 过期后不能新增到访:页面在这个状态下不出到访按钮,也不会调 /api/play/arrive。
 */

function isRecord(v) { return !!v && typeof v === 'object' && !Array.isArray(v); }
function list(v) { return Array.isArray(v) ? v.filter(isRecord) : []; }
function idOf(v) { return v == null ? '' : String(v); }

/** /api/play/history 的查询参数:不带 routeSessionId(过期后没有可继续的局)。没有主题号就不读。 */
function historyQuery(activityId, topicId) {
  const tid = idOf(topicId).trim();
  if (!tid) return null;
  const aid = idOf(activityId).trim();
  return { activityId: aid || 0, topicId: tid };
}

/** 回包是不是自由探索的只读历史。城市定向或其它形状一律 false,页面照旧走「获取通行证」。 */
function isReadOnlyFe(d) {
  return isRecord(d) && d.readOnly === true && Number(d.mode) === 2;
}

function unlockedNode(n) {
  return Object.assign({}, n, { visited: true, storyLocked: false, visitedByTeam: n.visitedByTeam === true,
    visitTime: n.visitTime || null });
}

function lockedNode(n) {
  const out = {};
  ['nodeId', 'name', 'chapterId', 'address', 'hookText', 'imgUrl', 'longitude', 'latitude', 'businessTime', 'merchantId',
    'visitCheck'].forEach((f) => { if (n[f] !== undefined) out[f] = n[f]; });
  return Object.assign(out, { visited: false, storyLocked: true, visitedByTeam: false, visitTime: null });
}

function chapterIdOf(c) { return idOf(c.chapterId != null ? c.chapterId : c.id); }

/**
 * 拼只读集章册。history 是服务端回包 data;cached 是 fe-story-cache.read() 的结果(可空)。
 * 顺序沿用缓存里服务端原来的顺序(卡片不跳位),历史里新出现的追加在后面。
 * 返回 null = 不是自由探索只读历史,或者一处可读的都没有(交给「获取通行证」空态)。
 */
function merge(history, cached) {
  if (!isReadOnlyFe(history)) return null;
  const seen = isRecord(cached) && Number(cached.mode) === 2 ? cached : null;
  const hNodes = list(history.nodes);
  const hChapters = list(history.chapters);
  const openChapters = new Set(hChapters.map(chapterIdOf));
  const nodes = [];
  const used = new Set();
  list(seen && seen.nodes).forEach((n) => {
    const id = idOf(n.nodeId);
    if (!id || used.has(id)) return;
    used.add(id);
    const h = hNodes.find((x) => idOf(x.nodeId) === id);
    nodes.push(h ? unlockedNode(h) : lockedNode(n));
  });
  hNodes.forEach((n) => {
    const id = idOf(n.nodeId);
    if (!id || used.has(id)) return;
    used.add(id);
    nodes.push(unlockedNode(n));
  });
  if (!nodes.length) return null;
  const chapters = [];
  const usedCh = new Set();
  const openChapter = (c) => ({ chapterId: c.chapterId != null ? c.chapterId : c.id, name: c.name || '', sortId: c.sortId,
    description: c.description || '', blocks: Array.isArray(c.blocks) ? c.blocks : null, chapterLocked: false });
  list(seen && seen.chapters).forEach((c) => {
    const id = chapterIdOf(c);
    if (!id || usedCh.has(id)) return;
    usedCh.add(id);
    const h = hChapters.find((x) => chapterIdOf(x) === id);
    chapters.push(h ? openChapter(h) : { chapterId: c.chapterId != null ? c.chapterId : c.id, name: c.name || '', chapterLocked: true });
  });
  hChapters.forEach((c) => {
    const id = chapterIdOf(c);
    if (!id || usedCh.has(id)) return;
    usedCh.add(id);
    chapters.push(openChapter(c));
  });
  const unlocked = Number(history.storyUnlocked);
  const total = Number(history.storyTotal);
  return {
    mode: 2, readOnly: true,
    topicId: history.topicId || (seen && seen.topicId) || '',
    topicName: history.topicName || (seen && seen.topicName) || '',
    topicCover: history.topicCover || (seen && seen.topicCover) || '',
    storyUnlocked: Number.isFinite(unlocked) ? unlocked : openChapters.size,
    storyTotal: Number.isFinite(total) && total > 0 ? total : chapters.length,
    nodes, chapters,
  };
}

module.exports = { historyQuery, isReadOnlyFe, merge };
