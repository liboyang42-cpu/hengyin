'use strict'
/**
 * 帖文第三轮(业主定稿 P-1):发现 / 俱乐部详情 / 我的 三处帖文卡同一套动作 —— 点赞、头像「+」关注。
 * 列表里可能混着两种帖文:俱乐部帖文(source 缺省或 'club',接口 /api/club/post/like)
 * 和社区帖文(source='community',接口 /api/creativesquare/like)。
 *
 * 点赞:先改卡面(心形与数字),回包以服务端为准;失败回滚到点之前,后端提示原样给用户。
 * 同一条帖子请求没回来之前再点不发第二个请求。
 * 关注:作者在列表里所有帖子一起改;失败回滚。
 * opts: { page, listKey, index, app, toast, apply(nextList) } —— apply 由页面写成 this.setData({ feed: next })。
 */

function rowAt(page, listKey, index) {
  const list = page.data[listKey] || []
  return list[index]
}

// 改动交回页面自己 setData(固定字段名,UI 门禁可静态核对),这里只算新列表
function patchWhere(page, listKey, apply, pred, fields) {
  const list = page.data[listKey] || []
  let changed = false
  const next = list.map(function (row) {
    if (!row || !pred(row)) return row
    changed = true
    return Object.assign({}, row, fields)
  })
  if (changed) apply(next)
}

function samePost(a, b) {
  return !!a && !!b && String(a.id) === String(b.id) && (a.source === 'community') === (b.source === 'community')
}

function isOk(res) { return !!res && (res.code === 200 || res.code === '200') }

function toggleLike(opts) {
  const page = opts.page, listKey = opts.listKey, app = opts.app, toast = opts.toast, apply = opts.apply
  const row = rowAt(page, listKey, opts.index)
  if (!row || row.id == null) return false
  const community = row.source === 'community'
  const pending = page._postLikePending || (page._postLikePending = {})
  const key = (community ? 'c' : 'p') + row.id
  if (pending[key]) return false
  pending[key] = true
  const before = { isLiked: row.isLiked, likeCount: Math.max(0, Number(row.likeCount) || 0) }
  const liked = !(row.isLiked === true || Number(row.isLiked) === 1)
  const optimistic = { isLiked: liked ? 1 : 0, likeCount: Math.max(0, before.likeCount + (liked ? 1 : -1)) }
  patchWhere(page, listKey, apply, function (r) { return samePost(r, row) }, optimistic)
  const rollback = function (res, fallback) {
    patchWhere(page, listKey, apply, function (r) { return samePost(r, row) }, before)
    if (toast) toast((res && res.msg) || fallback)
  }
  app.sendRequest({
    hideLoading: true, silentError: true, autoErrorToast: false,
    url: community ? '/api/creativesquare/like' : '/api/club/post/like',
    method: 'POST',
    // 俱乐部帖文带目标态(服务端按目标态幂等);社区帖文 type=1 是点赞/取消同一个开关
    data: community ? { id: row.id, type: 1 } : JSON.stringify({ postId: row.id, liked: liked }),
    header: community ? undefined : { 'Content-Type': 'application/json' },
    success: function (res) {
      delete pending[key]
      if (!isOk(res)) return rollback(res, '点赞没成功')
      const d = res.data
      if (community && d && typeof d === 'object') {
        patchWhere(page, listKey, apply, function (r) { return samePost(r, row) }, { isLiked: d.isLiked, likeCount: d.likeCount })
      }
    },
    fail: function (res) { delete pending[key]; rollback(res, '网络错误，请重试') },
  })
  return true
}

function follow(opts) {
  const page = opts.page, listKey = opts.listKey, app = opts.app, toast = opts.toast, apply = opts.apply
  const row = rowAt(page, listKey, opts.index)
  const memberId = row && row.memberId
  if (!memberId) return false
  const pending = page._followPending || (page._followPending = {})
  if (pending[memberId]) return false
  pending[memberId] = true
  const byAuthor = function (r) { return String(r.memberId) === String(memberId) }
  patchWhere(page, listKey, apply, byAuthor, { isFollowTheUser: 1 })
  app.sendRequest({
    hideLoading: true, silentError: true, autoErrorToast: false,
    url: '/api/user/follow/action', method: 'POST',
    data: { follow_member_id: memberId, follow: 1 },
    success: function (res) {
      delete pending[memberId]
      if (isOk(res)) return
      patchWhere(page, listKey, apply, byAuthor, { isFollowTheUser: 0 })
      if (toast) toast((res && res.msg) || '关注没成功')
    },
    fail: function () {
      delete pending[memberId]
      patchWhere(page, listKey, apply, byAuthor, { isFollowTheUser: 0 })
      if (toast) toast('网络错误，请重试')
    },
  })
  return true
}

module.exports = { toggleLike, follow }
