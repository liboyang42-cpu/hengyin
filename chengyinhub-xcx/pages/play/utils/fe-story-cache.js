'use strict';
/**
 * 自由探索(mode 2)已解锁章节的离线缓存(FE-STORY 6.1 / 7.3)。
 *
 * 只存服务端已经下发给本人的东西:已解锁章节的正文与块、已到访地点的完成碎片,以及锁卡要显示的公开字段
 * (名字、地址、钩子)。锁定章节的正文服务端本来就不下发,这里也不会有。
 * 按「账号 + 场次/主题」一把 key,记录上盖着局号;换了一局就整份覆盖。读不到/存不上都当没有。
 * 到访必须联网:缓存只用于读,离线时锁卡只提示「联网后才能解锁」,不排队、不补发。
 */
const { pausedRunStorageKey } = require('./play-run-session.js');

const PREFIX = 'cy_fe_story:';
const NODE_PUBLIC = ['nodeId', 'name', 'chapterId', 'address', 'hookText', 'imgUrl', 'longitude', 'latitude', 'businessTime',
  'merchantId', 'visited', 'visitTime', 'visitCheck', 'storyLocked', 'visitedByTeam'];

function keyOf(scope) {
  const base = pausedRunStorageKey(scope);
  return base ? PREFIX + base : '';
}

function pick(src, fields) {
  const out = {};
  fields.forEach((f) => { if (src[f] !== undefined) out[f] = src[f]; });
  return out;
}

/** 从 /api/play/nodes 的回包(d)挑出可缓存的部分。不是自由探索到访闸的回包返回 null。 */
function snapshot(d) {
  if (!d || Number(d.mode) !== 2 || d.storyTotal == null || !Array.isArray(d.nodes)) return null;
  const nodes = d.nodes.map((n) => {
    const row = pick(n || {}, NODE_PUBLIC);
    if (n && n.storyLocked !== true && n.fragmentText) row.fragmentText = n.fragmentText;
    return row;
  });
  const chapters = (Array.isArray(d.chapters) ? d.chapters : []).map((c) => {
    const locked = c && c.chapterLocked === true;
    return Object.assign({ chapterId: c.chapterId != null ? c.chapterId : c.id, name: c.name || '', chapterLocked: locked },
      locked ? {} : { description: c.description || '', blocks: Array.isArray(c.blocks) ? c.blocks : null });
  });
  return { mode: 2, topicId: d.topicId || '', topicName: d.topicName || '', topicCover: d.topicCover || '',
    storyUnlocked: Number(d.storyUnlocked) || 0, storyTotal: Number(d.storyTotal) || 0, nodes, chapters,
    readOnly: d.readOnly === true };
}

/** 票过期后的只读集章册(/api/play/history,不带局号)也存一份:沿用上一局的局号;从没存过就记成 history。 */
const READ_ONLY_RUN = 'history';

function save(scope, d) {
  const key = keyOf(scope);
  const snap = snapshot(d);
  let runId = String((scope && scope.routeSessionId) == null ? '' : scope.routeSessionId).trim();
  if (!runId && snap && snap.readOnly && scope && scope.memberId != null && scope.memberId !== '') {
    const prev = read(scope);
    runId = (prev && prev.runId) || READ_ONLY_RUN;
  }
  if (!key || !snap || !runId) return false;
  try { wx.setStorageSync(key, Object.assign(snap, { runId, owner: String(scope.memberId) })); return true; } catch (e) { return false; }
}

/** 离线读:只认同一账号的记录。 */
function read(scope) {
  const key = keyOf(scope);
  if (!key) return null;
  try {
    const v = wx.getStorageSync(key);
    if (!v || typeof v !== 'object' || v.owner !== String(scope.memberId) || !Array.isArray(v.nodes)) return null;
    return v;
  } catch (e) { return null; }
}

module.exports = { save, read, snapshot, keyOf };
