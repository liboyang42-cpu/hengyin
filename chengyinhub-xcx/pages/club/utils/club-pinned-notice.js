/**
 * pages/club/utils/club-pinned-notice.js —— 俱乐部置顶公告(帖子 tab 顶部「公告 · 新人先看」)
 *
 * 数据来自已有的 /api/club/post/list(type=2 为公告,isPinned 为置顶),不新增接口。
 * 已读状态只存本机 storage(按 clubId 分桶,只存帖子 id,留最近 50 个),换设备会重新显示为未读。
 */
const KEY_PREFIX = 'cy_club_notice_read_'
const MAX_SHOWN = 2
const MAX_KEPT = 50

function isPinnedNotice(p) {
  return !!p && Number(p.type) === 2 && (p.isPinned === true || Number(p.isPinned) === 1)
}

function ts(p) {
  const t = Date.parse(String(p && p.createTime || '').replace(/-/g, '/'))
  return Number.isFinite(t) ? t : 0
}

function loadRead(clubId, env) {
  const w = env || (typeof wx !== 'undefined' ? wx : null)
  try {
    const v = w && w.getStorageSync(KEY_PREFIX + clubId)
    return Array.isArray(v) ? v.map(String) : []
  } catch (e) { return [] }
}

function markRead(clubId, postId, env) {
  const w = env || (typeof wx !== 'undefined' ? wx : null)
  if (!clubId || postId === undefined || postId === null || postId === '') return false
  const list = loadRead(clubId, env).filter((x) => x !== String(postId))
  list.push(String(postId))
  try {
    w.setStorageSync(KEY_PREFIX + clubId, list.slice(-MAX_KEPT))
    return true
  } catch (e) { return false }
}

/** 取最新的 ≤2 条置顶公告,附 unread / summary(单行摘要) */
function pick(posts, clubId, env) {
  const read = loadRead(clubId, env)
  return (Array.isArray(posts) ? posts : [])
    .filter(isPinnedNotice)
    .sort((a, b) => ts(b) - ts(a))
    .slice(0, MAX_SHOWN)
    .map((p) => ({
      id: p.id,
      summary: String(p.contents || p.content || '').replace(/\s+/g, ' ').trim().slice(0, 60),
      unread: read.indexOf(String(p.id)) < 0,
    }))
}

module.exports = { MAX_SHOWN, isPinnedNotice, loadRead, markRead, pick }
