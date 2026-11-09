const { id, integer, precise } = require('../../../utils/orient-score-view.js');

function scope(owner, activityId, topicId, routeSessionId) {
  const activity = integer(activityId == null || activityId === '' ? '0' : activityId);
  const value = { owner: id(owner), activityId: activity, topicId: id(topicId), routeSessionId: id(routeSessionId) };
  return value.owner && (activity === '0' || id(activity)) && value.topicId && value.routeSessionId ? value : null;
}
function key(value) { return value ? JSON.stringify([value.owner, value.activityId, value.topicId, value.routeSessionId]) : ''; }
function freeze(value) {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
}
function text(out, source, keys) {
  keys.forEach(name => { if (typeof source[name] === 'string') out[name] = source[name]; });
}
function scalars(out, source, keys) {
  keys.forEach(name => {
    if (typeof source[name] === 'boolean' || typeof source[name] === 'number' && Number.isFinite(source[name])) out[name] = source[name];
  });
}
function result(row) {
  if (!row || !['routeReceipt', 'completedGame'].includes(row.kind)) return null;
  const out = { kind: row.kind };
  if (row.kind === 'routeReceipt') {
    text(out, row, ['receiptId', 'outcomeCode', 'at']);
    if (typeof row.at === 'number') out.at = row.at;
    scalars(out, row, ['passed', 'outcome', 'success', 'total', 'kept', 'settled', 'used', 'actions']);
  } else {
    if (!id(row.gameSessionId)) return null;
    out.gameSessionId = id(row.gameSessionId);
    scalars(out, row, ['score', 'completedUnits']);
    text(out, row, ['completedAt']);
    if (typeof row.completedAt === 'number') out.completedAt = row.completedAt;
    const source = row.outcomes || {}, outcomes = {};
    if (['PASS', 'FAIL'].includes(source.outcome)) outcomes.outcome = source.outcome;
    if (['HEADS', 'TAILS'].includes(source.coinSide)) outcomes.coinSide = source.coinSide;
    if (source.diceRoll) {
      outcomes.diceRoll = {};
      scalars(outcomes.diceRoll, source.diceRoll, ['sum', 'kept', 'total', 'success']);
      const pips = source.diceRoll.pips;
      if (Array.isArray(pips) && pips.length <= 2 && pips.every(pip => Number.isInteger(pip) && pip >= 1 && pip <= 20)) outcomes.diceRoll.pips = pips.slice();
    }
    if (Array.isArray(source.draws)) outcomes.draws = source.draws.map(draw => { const item = {}; text(item, draw || {}, ['id', 'label', 'content']); return item; });
    out.outcomes = outcomes;
  }
  return out;
}
function blocks(rows, nodes) {
  const out = [];
  for (const row of rows) {
    if (!row || row.beat != null || row.when != null) continue;
    if (row.type === 'node') {
      if (!id(row.nodeId) || !nodes.some(node => node.nodeId === id(row.nodeId))) return null;
      out.push({ type: 'node', nodeId: id(row.nodeId) });
    } else if (row.type === 'divider') {
      const block = { type: 'divider' }; text(block, row, ['key']); out.push(block);
    } else if (['text', 'image', 'audio', 'voice', 'dream'].includes(row.type)) {
      const block = { type: row.type };
      text(block, row, ['key', 'content', 'url', 'altText', 'who', 'title']);
      if (row.type === 'dream' && Array.isArray(row.images)) block.images = row.images.map(image => {
        const item = {}; text(item, image || {}, ['url', 'line']); return item;
      });
      out.push(block);
    }
    // Thought keys have no original thought presentation in this contract; never claim or fill from current config.
  }
  return out;
}
function project(data, expected, configVersion) {
  if (!data || !expected || data.readOnly !== true || !precise(data)
      || id(data.routeSessionId) !== expected.routeSessionId || integer(data.activityId) !== expected.activityId
      || id(data.topicId) !== expected.topicId || !id(data.configVersion)
      || configVersion && id(data.configVersion) !== configVersion
      || !Array.isArray(data.nodes) || !Array.isArray(data.chapters)) return null;
  const nodes = [];
  for (const row of data.nodes) {
    if (!row || !id(row.nodeId) || row.done !== true || row.history !== true || !Array.isArray(row.results)) return null;
    const node = { nodeId: id(row.nodeId), done: true, history: true, readOnly: true, results: row.results.map(result).filter(Boolean) };
    if (row.chapterId != null) { if (!id(row.chapterId)) return null; node.chapterId = id(row.chapterId); }
    text(node, row, ['name', 'description', 'imgUrl', 'fragmentText', 'storyText', 'storyImg']);
    node.story = node.storyText || '';
    nodes.push(node);
  }
  if (new Set(nodes.map(node => node.nodeId)).size !== nodes.length) return null;
  const chapters = [];
  if (data.narrativeAvailable === true) for (const row of data.chapters) {
    if (!row || !id(row.chapterId) || row.complete !== true || !Array.isArray(row.blocks)) return null;
    const earned = blocks(row.blocks, nodes);
    if (!earned) return null;
    chapters.push({ id: id(row.chapterId), key: 'history-chapter-' + id(row.chapterId), history: true, readOnly: true,
      complete: true, title: '已完成章节', meta: '', description: '', cover: '', audioUrl: '', videoUrl: '', blocks: earned });
  }
  if (new Set(chapters.map(chapter => chapter.id)).size !== chapters.length) return null;
  return freeze({ ...expected, configVersion: id(data.configVersion), narrativeAvailable: data.narrativeAvailable === true, nodes, chapters });
}

/** Already-earned preceding chapters, in frozen server order, using the existing paragraph shape. */
function preceding(history, currentId, liveCards) {
  if (!history) return [];
  const end = history.chapters.findIndex(chapter => chapter.id === String(currentId));
  let chapters = history.chapters.slice(0, end);
  if (end < 0) {
    const liveIndex = liveCards.findIndex(card => String(card.id) === String(currentId));
    if (liveIndex < 0) return [];
    chapters = history.chapters.filter(chapter => {
      const index = liveCards.findIndex(card => String(card.id) === chapter.id);
      return index >= 0 && index < liveIndex;
    });
  }
  const items = [];
  const push = value => String(value || '').split(/\n+/).map(line => line.trim()).filter(Boolean)
    .forEach(line => items.push({ kind: 'text', text: line, history: true, readOnly: true }));
  chapters.forEach(chapter => chapter.blocks.forEach(block => {
    if (block.type === 'node') {
      const node = history.nodes.find(value => value.nodeId === block.nodeId);
      if (node) push(node.story);
    } else if (block.type === 'text' || block.type === 'voice') push(block.who ? block.who + '：' + (block.content || '') : block.content);
    else if ((block.type === 'image' || block.type === 'audio') && block.url) items.push({ kind: block.type, text: '', url: block.url,
      altText: block.altText || '', history: true, readOnly: true });
    else if (block.type === 'dream') {
      const images = (block.images || []).filter(image => image.url);
      if (images.length) items.push({ kind: 'dream', title: block.title || '', images,
        strip: images.map((image, k) => ({ url: image.url, k })), history: true, readOnly: true });
    }
  }));
  return items;
}

module.exports = { scope, key, project, preceding };
