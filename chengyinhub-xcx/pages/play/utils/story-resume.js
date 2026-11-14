/**
 * pages/play/utils/story-resume.js —— 故事流「中途退出能回到原处」(2026-09-24 用户:前 6 条都修)
 *
 * 本地存三样,按账号 + 场次/主题隔离(与暂停计时同一把 key,预览/缺身份时 key 为空 = 不存不读):
 *   pos        读到哪一段 {chapterId, i, nodeId}
 *   gates      看过开场门的章节 id
 *   openNodeId 退出时正开着的那个玩法
 *   trails     读过收尾文字的章节 id → true(P-29:换章前先读完本章收尾,读过的重进不再停)
 * 以及玩法里填了一半的草稿(draftKey,由玩法组件自己存取)。存不上 / 读不出都当没有,不打断游玩。
 */
const { pausedRunStorageKey } = require('./play-run-session.js');

const PREFIX = 'cy_story_resume:';

function keyOf(scope) {
  const base = pausedRunStorageKey(scope);
  return base ? PREFIX + base : '';
}

/** E-C2:续玩状态属于哪一局(routeSessionId)。记录上盖着局号,换了新一局就当没有,不把上一局的开场 / 章节门 / finished / 草稿带进来。
 *  局号还没读到(空)时与旧行为一致,不拦。 */
function runIdOf(scope) {
  return String((scope && scope.routeSessionId) == null ? '' : scope.routeSessionId).trim();
}

function read(scope) {
  const key = keyOf(scope);
  if (!key) return {};
  try {
    const value = wx.getStorageSync(key);
    if (!value || typeof value !== 'object') return {};
    const runId = runIdOf(scope);
    if (runId && value.runId && String(value.runId) !== runId) return {};
    return value;
  } catch (e) { return {}; }
}

function patch(scope, fields) {
  const key = keyOf(scope);
  if (!key) return;
  const runId = runIdOf(scope);
  try { wx.setStorageSync(key, Object.assign(read(scope), fields, runId ? { runId } : {})); } catch (e) { /* 存不上就算了 */ }
}

/** 新一局 / 通关收尾时清掉这一局的续玩状态。 */
function clear(scope) {
  const key = keyOf(scope);
  if (!key) return;
  try { wx.removeStorageSync(key); } catch (e) { /* 清不掉就算了,下一局的局号对不上也读不到 */ }
}

/** 玩法草稿的 key:同一局同一站一份。没有身份时返回空串,组件据此不存。 */
function draftKey(scope, nodeId) {
  const key = keyOf(scope);
  if (!key || nodeId == null) return '';
  const runId = runIdOf(scope);
  return key + (runId ? ':r' + runId : '') + ':n' + nodeId;
}

module.exports = { read, patch, clear, draftKey };
