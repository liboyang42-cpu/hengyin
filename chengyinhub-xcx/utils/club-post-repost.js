/**
 * 俱乐部帖文「转发」(2026-10-29 业主拍板:动作行 = 点赞 / 评论 / 转发 / 分享)。
 *
 * 后端合同(ApiClubController):
 *   POST /api/club/post/repost       { postId }  → { reposted, repostCount }
 *   POST /api/club/post/repost/undo  { postId }  → { reposted, repostCount }
 *   /api/club/post/list、/api/club/post/feed 的条目里混着转发条目:
 *     { itemType: 'repost', repostId, reposterMemberId, reposterNick, reposterAvatar, repostTime, post: {原帖} }
 *   原帖查看者已读不到的转发,后端整条不返回。
 *
 * 三个页面(俱乐部详情 / 俱乐部 tab / 商家关系)共用这一份映射与切换,不各写一套。
 * 转发条目摊平成「原帖 + repostBy」:点赞、评论、转发都作用在原帖 id 上,
 * 列表 key 用 feedKey(原帖与它的转发条目 id 相同,不能再拿 id 当 key)。
 */
const REPOST_URL = '/api/club/post/repost';
const UNDO_URL = '/api/club/post/repost/undo';

function isRepostRow(row) {
  return !!row && row.itemType === 'repost' && !!row.post && typeof row.post === 'object';
}

function flattenRepost(row, normalize) {
  const base = normalize(row.post);
  return Object.assign({}, base, {
    feedKey: 'r' + row.repostId,
    isRepostItem: true,
    // 原帖在别处置顶,不该在转发里再顶一次;公告标签保留、去掉「置顶」
    isPinned: false,
    noticeBadge: base.noticeBadge ? '公告' : '',
    repostBy: {
      memberId: row.reposterMemberId,
      nick: row.reposterNick || '',
      time: row.repostTime || '',
    },
  });
}

/** 后端行 → 卡片行。普通帖文照原 normalize;转发条目摊平;itemType 认不得的转发残行丢掉。 */
function mapClubFeedRows(rows, normalize) {
  const out = [];
  (rows || []).forEach(function (row) {
    if (!row || typeof row !== 'object') return;
    if (row.itemType === 'repost') {
      if (isRepostRow(row)) out.push(flattenRepost(row, normalize));
      return;
    }
    const post = normalize(row);
    out.push(Object.assign({}, post, { feedKey: String(post.id) }));
  });
  return out;
}

/** 同一帖文可能同时以原帖和转发条目出现:按原帖 id 一起改。 */
function applyRepostState(list, postId, reposted, repostCount) {
  return (list || []).map(function (row) {
    if (!row || row.source === 'community' || String(row.id) !== String(postId)) return row;
    return Object.assign({}, row, { viewerReposted: reposted, repostCount: repostCount });
  });
}

/**
 * 乐观切换:先改卡面,失败按发起前的状态回滚;后端回的提示语原样给用户,一个字不改。
 * opts: { page, listKey, postId, app(取 sendRequest), toast(msg) }
 * autoErrorToast:false —— 失败提示由这里出一次(后端 msg 原样),请求层不再自动弹第二次。
 * 同一帖文请求未回来之前再点不发第二个请求。
 */
function toggleRepost(opts) {
  const page = opts.page;
  const listKey = opts.listKey;
  const postId = opts.postId;
  const list = page.data[listKey] || [];
  // 发现页混着社区帖(source=community),两边 id 可能撞号;转发只认俱乐部帖文
  const row = list.find(function (r) { return r && r.source !== 'community' && String(r.id) === String(postId); });
  if (!row) return false;
  const pending = page._repostPending || (page._repostPending = {});
  const key = String(postId);
  if (pending[key]) return false;
  const before = { reposted: row.viewerReposted === true, count: Math.max(0, Number(row.repostCount) || 0) };
  const target = !before.reposted;
  const token = {};
  pending[key] = token;
  page.setData({ [listKey]: applyRepostState(list, postId, target, Math.max(0, before.count + (target ? 1 : -1))) });
  function settle() {
    if (pending[key] !== token) return false;
    delete pending[key];
    return true;
  }
  function rollback(message) {
    page.setData({ [listKey]: applyRepostState(page.data[listKey], postId, before.reposted, before.count) });
    if (message) opts.toast(message);
  }
  opts.app.sendRequest({
    hideLoading: true,
    autoErrorToast: false,
    url: target ? REPOST_URL : UNDO_URL,
    method: 'POST',
    data: JSON.stringify({ postId: row.id }),
    header: { 'Content-Type': 'application/json' },
    success(res) {
      if (!settle()) return;
      const data = res && (res.code == 200 || res.code == '200') && res.data;
      if (!data || typeof data.reposted !== 'boolean') {
        rollback((res && res.msg) || '转发没成功');
        return;
      }
      const count = Number.isInteger(data.repostCount) ? data.repostCount : before.count;
      page.setData({ [listKey]: applyRepostState(page.data[listKey], postId, data.reposted, count) });
    },
    fail() {
      if (!settle()) return;
      rollback('网络异常，请重试');
    },
  });
  return true;
}

module.exports = { REPOST_URL, UNDO_URL, isRepostRow, mapClubFeedRows, applyRepostState, toggleRepost };
