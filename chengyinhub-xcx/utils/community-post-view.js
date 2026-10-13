/** Canonical community responses adapted only for the existing presentation fields. */
function field(row, camel, snake) { return row[camel] !== undefined ? row[camel] : row[snake]; }
function id(value) {
  if ((typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
    || (typeof value === 'string' && /^[1-9][0-9]*$/.test(value))) return value;
  throw { code: 502, msg: '社区数据标识不完整，请刷新重试' };
}
function version(value) {
  if (!Number.isInteger(value) || value < 0) throw { code: 502, msg: '社区数据版本不完整，请刷新重试' };
  return value;
}
function flag(value) { return value === true || value === 1 ? true : value === false || value === 0 ? false : undefined; }
function validateBundle(bundle, expectedId) {
  if (!bundle || !bundle.post || !Array.isArray(bundle.media) || !Array.isArray(bundle.references))
    throw { code: 502, msg: '帖文响应不完整，请刷新重试' };
  id(bundle.post.id); id(bundle.post.authorId); version(bundle.post.version);
  if (expectedId != null && String(bundle.post.id) !== String(id(expectedId)))
    throw { code: 502, msg: '帖文响应来源不一致，请刷新重试' };
  if (!['DRAFT','CHECKING','PUBLISHED','LIMITED','HIDDEN','REMOVED'].includes(bundle.post.lifecycle))
    throw { code: 502, msg: '帖文状态不完整，请刷新重试' };
  return bundle;
}
function validatePage(page, query) {
  if (!page || !Array.isArray(page.items) || typeof page.hasMore !== 'boolean'
    || !Object.prototype.hasOwnProperty.call(page, 'nextCursor'))
    throw { code: 502, msg: '分页响应不完整，请刷新重试' };
  if (page.nextCursor != null) id(page.nextCursor);
  if (page.hasMore && page.nextCursor == null) throw { code: 502, msg: '分页游标不完整，请刷新重试' };
  if (!page.items.length && (page.hasMore || page.nextCursor != null)) throw { code: 502, msg: '空页游标异常，请刷新重试' };
  if (page.nextCursor != null && query && query.cursor != null) {
    const before = String(id(query.cursor)), after = String(page.nextCursor);
    if (after.length > before.length || (after.length === before.length && after >= before))
      throw { code: 502, msg: '分页游标未推进，请刷新重试' };
  }
  return page;
}
function validatePostPage(page, query) {
  validatePage(page, query); page.items.forEach(bundle => validateBundle(bundle));
  if (page.items.length && page.nextCursor != null
    && String(page.nextCursor) !== String(page.items[page.items.length - 1].post.id))
    throw { code: 502, msg: '帖文游标来源异常，请刷新重试' };
  return page;
}
function toPostView(bundle) {
  validateBundle(bundle);
  const post = bundle.post;
  const row = { id: post.id, version: post.version, lifecycle: post.lifecycle,
    memberId: post.authorId, memberNickname: post.authorNickname, memberAvatar: post.authorAvatar,
    contents: post.body, createTime: post.createTime, likeCount: post.likeCount,
    commentCount: post.commentCount, bookmarkCount: post.bookmarkCount,
    isLiked: flag(post.viewerLiked), isBookmarked: flag(post.viewerBookmarked),
    isFollowTheUser: flag(bundle.viewerFollowing), viewerIsAuthor: flag(bundle.viewerIsAuthor),
    viewerCanComment: flag(bundle.viewerCanComment), audience: post.audience,
    topicCodes: Array.isArray(bundle.topicCodes) ? bundle.topicCodes.slice() : undefined,
    status: post.lifecycle,
    lifecycleLabel: { DRAFT:'草稿',CHECKING:'审核中',PUBLISHED:'',LIMITED:'展示受限',HIDDEN:'已隐藏',REMOVED:'已移除' }[post.lifecycle],
    pics: bundle.media.filter(m => ['IMAGE','MAP_SNAPSHOT'].includes(field(m,'mediaType','media_type')))
      .map(m => field(m,'derivedObjectKey','derived_object_key') || field(m,'objectKey','object_key')).filter(Boolean).join(';') };
  const ref = bundle.references[0];
  if (ref) {
    let snapshot = field(ref,'snapshotJson','snapshot_json');
    if (typeof snapshot === 'string') { try { snapshot = JSON.parse(snapshot); } catch (_) { throw { code:502,msg:'关联内容响应不完整，请刷新重试' }; } }
    snapshot = snapshot || {};
    const type = field(ref,'referenceType','reference_type');
    row.dataType = { ACTIVITY:1,TOPIC:2,ROUTE:3 }[type] || 0;
    row.dataId = field(ref,'referenceId','reference_id');
    row.sportName = snapshot.title; row.sportCover = snapshot.cover_url; row.sportSubtitle = snapshot.subtitle;
    if (type === 'TOPIC') row.sportTopicId = row.dataId;
  }
  return row;
}
function toCommentView(raw) {
  const row = Object.assign({}, raw, { id:id(raw.id), postId:id(field(raw,'postId','post_id')),
    memberId:id(field(raw,'authorId','author_id')), version:version(raw.version),
    memberNickname:field(raw,'authorNickname','author_nickname'), memberAvatar:field(raw,'authorAvatar','author_avatar'),
    contents:raw.body, createTime:field(raw,'createTime','create_time'),
    rootId:field(raw,'rootId','root_id'), parentId:field(raw,'parentId','parent_id'),
    repliedToMemberId:field(raw,'repliedToMemberId','replied_to_member_id'),
    isLiked:flag(field(raw,'viewerLiked','viewer_liked')), likeCount:field(raw,'likeCount','like_count'),
    isFollowTheUser:flag(field(raw,'viewerFollowing','viewer_following')) });
  return row;
}
module.exports = { toPostView, toCommentView, validateBundle, validatePage, validatePostPage, id, version, field };
