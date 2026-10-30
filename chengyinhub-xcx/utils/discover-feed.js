// 2026-10-29 业主:俱乐部 tab 改名「发现」,帖文看全站。全站帖文有两个来源:
//   俱乐部帖文  /api/club/post/feed {scope:'all'}(beforeId 翻页)
//   社区帖文    /api/v1/community/feeds/LATEST(经 community-post-api 的 /api/creativesquare/list 兼容口,pageNum 翻页)
// 两边的可见性、审核、拉黑、删除都在各自后端判;这里只按发布时间把两条倒序流合成一条。
const { decodeDisplayText } = require('./display-text.js');
const { toTimestamp } = require('./datetime');
const {
  buildFeedPlaySubtitle,
  resolveFeedPlayCover,
  hasFeedPlayCover,
  resolveCompletionShareImage,
  isRoamResultShare,
  isCompletedShare,
} = require('./feed-play-card.js');

function sortTs(value) {
  const ts = toTimestamp(value);
  return typeof ts === 'number' && Number.isFinite(ts) ? ts : 0;
}

function normalizeClubPost(p) {
  // refType 的编号就是广场的 dataType;后端投影不出 sportName(被引对象已删/下架)时
  // 两个判定都会自然判假,卡片降级成普通图文帖。
  const ref = Object.assign({}, p, { dataType: p.refType, completed: true });
  return Object.assign({}, p, {
    source: 'club',
    feedKey: 'club-' + p.id,
    sortTs: sortTs(p.createTime),
    memberId: p.authorMemberId,   // 帖文卡头像「+」关注与点头像都认 memberId
    memberNickname: p.nickname,
    memberAvatar: p.avatar,
    contents: decodeDisplayText(p.content),
    picList: String(p.images || '').split(/[;,]/).filter(Boolean).slice(0, 3),
    formattedCreateTime: String(p.createTime || '').slice(5, 16),
    isCompletionShare: isCompletedShare(ref),
    hasPlayCover: hasFeedPlayCover(ref),
  });
}

function normalizeCommunityPost(row) {
  const item = Object.assign({}, row, {
    source: 'community',
    feedKey: 'post-' + row.id,
    sortTs: sortTs(row.createTime),
    // R10-06:清空后的 '' 不得被解析成一个空图片位
    picList: row.pics != null ? String(row.pics).split(';').filter(Boolean) : [],
    formattedCreateTime: String(row.createTime || '').slice(5, 16),
  });
  item.playCover = resolveFeedPlayCover(item);
  item.hasPlayCover = hasFeedPlayCover(item);
  item.isCompletionShare = isCompletedShare(item);
  item.isRoamResultShare = isRoamResultShare(item);
  item.completionImage = resolveCompletionShareImage(item);
  if (item.sportName) item.playSubtitle = item.sportSubtitle || buildFeedPlaySubtitle(item);
  return item;
}

/**
 * 多路倒序流归并。每路 fetch(cursor) → Promise<{rows, hasMore, cursor}>,rows 已按 sortTs 倒序。
 * 某一路缓冲见底且还有下一页时先补齐再比较,保证吐出去的顺序不会被后到的页插队。
 */
function createFeedMerger(sources) {
  const lanes = sources.map(function (source) { return { source: source, buf: [], cursor: null, hasMore: true }; });
  function fill(lane) {
    return lane.source.fetch(lane.cursor).then(function (page) {
      const rows = page && Array.isArray(page.rows) ? page.rows : [];
      lane.buf = lane.buf.concat(rows);
      lane.cursor = page ? page.cursor : null;
      lane.hasMore = !!(page && page.hasMore === true && rows.length);
    });
  }
  function take(n) {
    const out = [];
    function step() {
      const pending = lanes.filter(function (lane) { return !lane.buf.length && lane.hasMore; });
      if (pending.length) return Promise.all(pending.map(fill)).then(step);
      while (out.length < n) {
        let best = null;
        lanes.forEach(function (lane) {
          if (lane.buf.length && (!best || lane.buf[0].sortTs > best.buf[0].sortTs)) best = lane;
        });
        if (!best) break;
        out.push(best.buf.shift());
        if (out.length < n && !best.buf.length && best.hasMore) return step();
      }
      return { rows: out, hasMore: lanes.some(function (lane) { return lane.buf.length > 0 || lane.hasMore; }) };
    }
    return Promise.resolve().then(step);
  }
  return { take: take };
}

module.exports = { normalizeClubPost, normalizeCommunityPost, createFeedMerger };
