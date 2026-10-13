/**
 * pages/play/utils/story-resume.js —— 故事流「中途退出能回到原处」(2026-09-24 用户:前 6 条都修)
 *
 * 本地存三样,按账号 + 场次/主题隔离(与暂停计时同一把 key,预览/缺身份时 key 为空 = 不存不读):
 *   pos        读到哪一段 {chapterId, i, nodeId}
 *   gates      看过开场门的章节 id
 *   openNodeId 退出时正开着的那个玩法
 * 以及玩法里填了一半的草稿(draftKey,由玩法组件自己存取)。存不上 / 读不出都当没有,不打断游玩。
 */
const { pausedRunStorageKey } = require('./play-run-session.js');

const PREFIX = 'cy_story_resume:';

function keyOf(scope) {
  const base = pausedRunStorageKey(scope);
  return base ? PREFIX + base : '';
}

function read(scope) {
  const key = keyOf(scope);
  if (!key) return {};
  try {
    const value = wx.getStorageSync(key);
    return value && typeof value === 'object' ? value : {};
  } catch (e) { return {}; }
}

function patch(scope, fields) {
  const key = keyOf(scope);
  if (!key) return;
  try { wx.setStorageSync(key, Object.assign(read(scope), fields)); } catch (e) { /* 存不上就算了 */ }
}

/** 玩法草稿的 key:同一局同一站一份。没有身份时返回空串,组件据此不存。 */
function draftKey(scope, nodeId) {
  const key = keyOf(scope);
  return key && nodeId != null ? key + ':n' + nodeId : '';
}

module.exports = { read, patch, draftKey };
