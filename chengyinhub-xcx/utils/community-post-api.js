/** Canonical community transport; legacy-shaped view models are local presentation only. */
const BASE = '/api/v1/community';
const LIFECYCLE_LABELS = { DRAFT: '草稿', CHECKING: '审核中', LIMITED: '展示受限', HIDDEN: '已隐藏', REMOVED: '已移除' };
function requestId() { return 'mini-community-' + Date.now() + '-' + Math.random().toString(36).slice(2); }
function value(row, camel, snake) { return row[camel] !== undefined ? row[camel] : row[snake]; }
function normalizePost(bundle) {
  if (!bundle || !bundle.post || !bundle.post.id) throw new Error('帖文响应不完整');
  const post = bundle.post;
  const row = Object.assign({}, post, {
    memberId: post.authorId, memberNickname: post.authorNickname, memberAvatar: post.authorAvatar,
    contents: post.body, isLiked: post.viewerLiked, isBookmarked: post.viewerBookmarked,
    isFollowTheUser: bundle.viewerFollowing === true ? 1 : bundle.viewerFollowing === false ? 0 : undefined,
    status: post.lifecycle === 'PUBLISHED' ? 1 : post.lifecycle === 'DRAFT' || post.lifecycle === 'CHECKING' ? 0 : post.lifecycle,
    pics: (bundle.media || []).map(m => value(m, 'derivedObjectKey', 'derived_object_key') || value(m, 'objectKey', 'object_key')).filter(Boolean).join(';'),
    lifecycleLabel: LIFECYCLE_LABELS[post.lifecycle] || ''
  });
  // Author-only exact grid remains in the edit response, never in card setData.
  delete row.approximateGeohash;
  const ref = (bundle.references || [])[0];
  if (ref) {
    let snapshot = value(ref, 'snapshotJson', 'snapshot_json');
    if (typeof snapshot === 'string') { try { snapshot = JSON.parse(snapshot); } catch (_) { snapshot = {}; } }
    snapshot = snapshot || {};
    const type = value(ref, 'referenceType', 'reference_type');
    row.dataType = type === 'ACTIVITY' ? 1 : type === 'TOPIC' ? 2 : type === 'ROUTE' ? 3 : 0;
    row.dataId = value(ref, 'referenceId', 'reference_id');
    row.sportName = snapshot.title; row.sportCover = snapshot.cover_url;
    const topicProjection = value(ref, 'topicId', 'topic_id');
    const topicId = typeof topicProjection === 'number' || typeof topicProjection === 'string' ? Number(topicProjection) : NaN;
    row.sportSubtitle = snapshot.subtitle;
    row.sportTopicId = type === 'TOPIC' ? row.dataId
      : type === 'ACTIVITY' && Number.isSafeInteger(topicId) && topicId > 0 ? topicId : undefined;
  }
  return row;
}
function normalizeComment(row) {
  return Object.assign({}, row, { memberId: value(row, 'authorId', 'author_id'),
    memberNickname: value(row, 'authorNickname', 'author_nickname'), memberAvatar: value(row, 'authorAvatar', 'author_avatar'),
    contents: row.body, createTime: value(row, 'createTime', 'create_time'),
    isLiked: value(row, 'viewerLiked', 'viewer_liked'), likeCount: value(row, 'likeCount', 'like_count'),
    isFollowTheUser: value(row, 'viewerFollowing', 'viewer_following'),
    rootId: value(row, 'rootId', 'root_id'), parentId: value(row, 'parentId', 'parent_id') });
}
function createClient(app, wxApi) {
  const posts = Object.create(null), comments = Object.create(null), pages = Object.create(null), uploads = Object.create(null), commands = Object.create(null);
  let owner = null, reporting = false;
  function resetForOwner() {
    const current = String(typeof app.getUserID === 'function' ? app.getUserID() || '' : '');
    if (owner !== current) { [posts, comments, pages, uploads, commands].forEach(store => Object.keys(store).forEach(key => delete store[key])); owner = current; }
  }
  function markFeedChanged(path, method) {
    if (method === 'GET' || !/^\/(posts(?:\/|$)|comments\/)/.test(path) || !app.globalData) return;
    app.globalData.squareListDirty = true;
    app.globalData.squareListDirtyVersion = (app.globalData.squareListDirtyVersion || 0) + 1;
  }
  function captureUpload(data) {
    resetForOwner();
    if (!data || !data.url || !data.uploadReceipt || !(data.byteSize > 0) || !data.mimeType) throw new Error('图片上传未完成，请重新上传');
    uploads[data.url] = Object.assign({}, data, { uploadRequestId: requestId() });
    return data.url;
  }
  function call(path, method, data, task) {
    return new Promise((resolve, reject) => {
      if (task.aborted) return reject({ code: 499, msg: '操作已取消' });
      if (String(app.getUserID() || '') !== task.owner) return reject({ code: 409, msg: '登录身份已变化，请重试' });
      task.cancel = () => reject({ code: 499, msg: '操作已取消' });
      task.current = app.sendRequest({ url: BASE + path, method, data: data || {}, hideLoading: true, silentError: true,
        header: { 'content-type': 'application/json' },
        success: res => { if (String(app.getUserID() || '') !== task.owner) return reject({ code: 409, msg: '登录身份已变化，请重试' }); if (Number(res && res.code) === 200) { markFeedChanged(path, method); resolve(res.data); } else reject(res || { code: 500, msg: '社区请求失败' }); },
        successStatusAbnormal: (res, status) => reject(Object.assign({}, res, { code: status, transportFailure: true })),
        fail: res => reject(Object.assign({}, res, { transportFailure: true })) });
    });
  }
  function modal(options, task) {
    return new Promise((resolve, reject) => {
      if (task.aborted) return reject({ code: 499, msg: '操作已取消' });
      task.cancel = () => reject({ code: 499, msg: '操作已取消' });
      wxApi.showModal(Object.assign({}, options, { success: res => res.confirm ? resolve() : reject({ code: 499, msg: '已取消发布' }),
        fail: () => reject({ code: 499, msg: '已取消发布' }) }));
    });
  }
  function pipeline(options, operation) {
    const task = { owner, aborted: false, current: null, abort() { this.aborted = true; if (this.cancel) this.cancel(); if (this.current && this.current.abort) this.current.abort(); } };
    Promise.resolve().then(() => operation(task)).then(data => {
      if (!task.aborted && options.success) options.success({ code: 200, data });
    }).catch(error => {
      if (task.aborted) return;
      if (error.transportFailure && options.fail) options.fail(error);
      else {
        const res = { code: error.code || 500, msg: error.msg || error.message || '社区操作失败' };
        if (!options.silentError && options.autoErrorToast !== false && res.code !== 499 && typeof app.tips === 'function') app.tips(typeof app.getRequestErrorMessage === 'function' ? app.getRequestErrorMessage(res, '社区操作失败') : res.msg);
        if (options.success) options.success(res);
      }
    }).then(() => { if (options.complete) options.complete(); });
    return task;
  }
  async function publish(form, task) {
    if (form.file_url) throw { code: 409, msg: '请移除尚未支持的附件后发布' };
    if (form.longitude || form.latitude || form.address) throw { code: 409, msg: '地点精度尚未接通，请先移除地点' };
    const key = form.request_id || requestId();
    const guideline = await call('/guidelines/active', 'GET', {}, task);
    if (!guideline || !guideline.id || !guideline.content_json) throw { code: 503, msg: '社区规范不完整，暂不能发布' };
    let document;
    try { document = typeof guideline.content_json === 'string' ? JSON.parse(guideline.content_json) : guideline.content_json; }
    catch (error) { throw { code: 503, msg: '社区规范不完整，暂不能发布' }; }
    const rules = document && document.rules;
    if (!Array.isArray(rules) || !rules.length || rules.some(rule => !rule || typeof rule.title !== 'string' || !rule.title.trim())) throw { code: 503, msg: '社区规范不完整，暂不能发布' };
    const content = rules.map((rule, index) => (index + 1) + '. ' + rule.title).join('\n');
    await modal({ title: guideline.title || '社区规范', content: (guideline.summary || '') + '\n' + content, confirmText: '同意发布' }, task);
    await call('/guidelines/ack', 'POST', { guidelineVersionId: guideline.id, requestId: key + '-ack', scene: 'PUBLISH' }, task);
    let previous = null;
    if (form.id) { previous = await call('/posts/' + form.id, 'GET', {}, task); posts[form.id] = normalizePost(previous); }
    const mediaIds = [];
    const paths = form.pics === undefined && previous ? null : String(form.pics || '').split(';').filter(Boolean);
    if (paths === null) (previous.media || []).forEach(m => mediaIds.push(m.id));
    else for (const path of paths) {
      const existing = previous && (previous.media || []).find(m => (value(m,'derivedObjectKey','derived_object_key') || value(m,'objectKey','object_key')) === path);
      if (existing) { mediaIds.push(existing.id); continue; }
      const receipt = uploads[path]; if (!receipt) throw { code: 409, msg: '图片上传状态无法确认，请先到我的推文查看发布结果，再重新选择图片' };
      const media = await call('/media/register', 'POST', { uploadRequestId: receipt.uploadRequestId, mediaType: 'IMAGE', objectKey: receipt.url,
        uploadReceipt: receipt.uploadReceipt, mimeType: receipt.mimeType, byteSize: receipt.byteSize }, task);
      if (!media || !media.id) throw { code: 500, msg: '媒体登记响应不完整' };
      mediaIds.push(media.id);
    }
    const references = [];
    if (form.data_id) {
      const type = Number(form.data_type), referenceType = type === 1 ? 'ACTIVITY' : type === 2 ? 'TOPIC' : type === 3 ? 'ROUTE' : null;
      if (!referenceType) throw { code: 409, msg: '关联来源尚未支持' };
      references.push({ referenceType, referenceId: Number(form.data_id) });
    }
    const original = previous && previous.post;
    if (previous && previous.references && previous.references.length) {
      const first = previous.references[0];
      const firstType = value(first, 'referenceType', 'reference_type');
      const firstId = value(first, 'referenceId', 'reference_id');
      if (references.length && references[0].referenceType === firstType && references[0].referenceId === Number(firstId)) {
        references.splice(0, references.length, ...previous.references.map(ref => ({ referenceType: value(ref,'referenceType','reference_type'), referenceId: value(ref,'referenceId','reference_id') })));
      } else {
        previous.references.slice(1).forEach(ref => references.push({ referenceType: value(ref,'referenceType','reference_type'), referenceId: value(ref,'referenceId','reference_id') }));
      }
    }
    const body = { clientRequestId: key, body: form.contents, postType: original ? original.postType : references[0] && references[0].referenceType === 'ACTIVITY' ? 'ACTIVITY_RECAP' : references[0] && references[0].referenceType === 'ROUTE' ? 'ROUTE_DISCOVERY' : 'MOMENT',
      audience: original ? original.audience : 'PUBLIC', commentPolicy: original ? original.commentPolicy : 'EVERYONE',
      guidelineVersionId: guideline.id, mediaIds, references, locationPrecision: original ? original.locationPrecision : 'NONE' };
    if (original && ['APPROXIMATE', 'EXACT'].includes(original.locationPrecision) && !original.approximateGeohash) throw { code: 409, msg: '原帖地点信息不完整，请刷新后重试' };
    if (original) { body.topicCodes = previous.topicCodes || []; body.mentionedMemberIds = previous.mentions || []; try { body.safetyLabels = JSON.parse(original.safetyLabelsJson || '[]'); } catch (_) { throw { code: 409, msg: '原帖安全标签无法读取，请刷新后重试' }; } ['title','communityId','replyApprovalEnabled','slowModeSeconds','cityCode','poiName','approximateGeohash','disclosureType'].forEach(k => { if (original[k] !== undefined) body[k] = original[k]; }); body.expectedVersion = original.version; }
    const draft = await call(original ? '/posts/' + original.id : '/posts', original ? 'PATCH' : 'POST', body, task);
    if (!draft || !draft.post) throw { code: 500, msg: '草稿响应不完整' };
    const result = original && original.lifecycle !== 'DRAFT' ? draft : await call('/posts/' + draft.post.id + '/publish', 'POST', { expectedVersion: draft.post.version, requestId: key + '-publish' }, task);
    const row = normalizePost(result); posts[row.id] = row;
    return row;
  }
  async function report(options, task) {
    if (reporting) throw { code: 409, msg: '举报正在处理中' };
    reporting = true;
    try {
      const taxonomy = (await call('/capabilities', 'GET', {}, task)).reporting;
      if (!taxonomy || !Array.isArray(taxonomy.reasons) || !taxonomy.reasons.length) throw { code: 503, msg: '举报原因暂不可用' };
      if (typeof options.selectReportReason !== 'function') throw { code: 503, msg: '举报原因选择暂不可用，请重试' };
      const index = await options.selectReportReason(taxonomy.reasons.map(r => r.label), task);
      if (!Number.isInteger(index) || !taxonomy.reasons[index]) throw { code: 500, msg: '举报原因选择无效，请重试' };
      return await call('/reports', 'POST', { targetType: options.url === '/api/comment/report' ? 'COMMENT' : 'CONTENT',
        targetId: options.data.id, reasonCode: taxonomy.reasons[index].code, policyVersion: taxonomy.policyVersion, requestId: options.data.request_id || requestId() }, task);
    } finally {
      reporting = false;
    }
  }
  function send(options) {
    resetForOwner();
    const original = options, data = options.data || {}, url = options.url;
    if (!/^\/api\/(creativesquare|comment)\//.test(url)) return app.sendRequest(options);
    if (url === '/api/creativesquare/action') return pipeline(options, task => publish(data, task));
    if (url === '/api/creativesquare/report' || url === '/api/comment/report') return pipeline(options, task => report(options, task));
    const requestOwner = owner;
    let commandKey;
    const stableCommand = (key, proposed) => { commandKey = key; if (!commands[key]) commands[key] = proposed || requestId(); return commands[key]; };
    let path, method = 'GET', body = {}, map = x => x, pageKey, page;
    const id = data.id || data.owner_id;
    if (url === '/api/creativesquare/list' || url === '/api/comment/list') {
      page = Number(data.pageNum || 1);
      pageKey = url + ':' + (data.favorite_only ? 'BOOKMARKS' : data.is_my ? 'OWN' : data.user_id || data.owner_id || 'LATEST');
      if (page === 1) pages[pageKey] = { generation: requestId(), cursors: { 1: null } };
      const state = pages[pageKey];
      if (!state || !Object.prototype.hasOwnProperty.call(state.cursors, page)) return reject(options, '分页游标已失效，请刷新');
      path = url === '/api/comment/list' ? '/posts/' + id + '/comments' : data.favorite_only ? '/me/bookmarks' : data.is_my ? '/me/posts' : '/feeds/LATEST';
      body = { limit: data.pageSize };
      if (state.cursors[page] != null) body.cursor = state.cursors[page];
      if (data.user_id > 0) body.authorId = data.user_id;
      map = response => {
        if (!response || !Array.isArray(response.items) || typeof response.hasMore !== 'boolean') throw new Error('分页响应不完整');
        const rows = response.items.map(item => {
          if (url === '/api/comment/list') { const row = normalizeComment(item); comments[row.id] = row; return row; }
          const row = normalizePost(item); posts[row.id] = row; return row;
        });
        if (pages[pageKey] === state) state.cursors[page + 1] = response.nextCursor;
        return { rows: rows, hasMore: response.hasMore, nextCursor: response.nextCursor };
      };
    } else if (url === '/api/creativesquare/info') {
      path = '/posts/' + id; map = bundle => { const row = normalizePost(bundle); posts[row.id] = row; return row; };
    } else if (url === '/api/creativesquare/bookmark' || url === '/api/creativesquare/like') {
      const type = url.endsWith('/bookmark') ? 'BOOKMARK' : 'LIKE';
      if (type === 'LIKE' && Number(data.type) !== 1) return reject(options, '此互动暂未接通');
      const previous = posts[id];
      if (type === 'LIKE' && !previous) return reject(options, '请刷新帖文后再操作');
      const enabled = type === 'BOOKMARK' ? Number(data.bookmark) === 1 : Number(previous.isLiked) !== 1;
      path = '/posts/' + id + '/actions' + (enabled ? '' : '/' + type); method = enabled ? 'POST' : 'DELETE';
      body = { requestId: stableCommand('action:' + id + ':' + type + ':' + enabled, data.request_id) }; if (enabled) { body.actionType = type; body.source = 'LEGACY_MINIPROGRAM'; }
      map = bundle => { const row = normalizePost(bundle); posts[row.id] = row; return row; };
    } else if (url === '/api/creativesquare/delete') {
      if (!posts[id]) return reject(options, '请刷新帖文后再操作');
      path = '/posts/' + id; method = 'DELETE'; body = { expectedVersion: posts[id].version, requestId: stableCommand('delete:' + id + ':' + posts[id].version, data.request_id) };
    } else if (url === '/api/comment/add') {
      path = '/posts/' + data.owner_id + '/comments'; method = 'POST';
      body = { body: data.contents, parentId: Number(data.reply_id) || null, clientRequestId: stableCommand('comment:' + data.owner_id + ':' + (data.reply_id || 0) + ':' + data.contents, data.request_id) };
    } else if (url === '/api/comment/like') {
      const row = comments[id]; if (!row) return reject(options, '请刷新评论后再操作');
      const enabled = Number(row.isLiked) !== 1;
      path = '/comments/' + id + '/actions' + (enabled ? '' : '/LIKE'); method = enabled ? 'POST' : 'DELETE'; body = { requestId: stableCommand('comment-like:' + id + ':' + enabled, data.request_id) };
      map = () => { comments[id] = Object.assign({}, row, { isLiked: enabled ? 1 : 0 }); return { isLiked: enabled ? 1 : 0 }; };
    } else if (url === '/api/comment/delete') {
      const row = comments[id]; if (!row) return reject(options, '请刷新评论后再操作');
      path = '/posts/' + value(row, 'postId', 'post_id') + '/comments/' + id; method = 'DELETE';
      body = { expectedVersion: row.version, requestId: stableCommand('delete-comment:' + id + ':' + row.version, data.request_id) };
    } else return reject(options, '此社区功能尚未接通');
    return app.sendRequest(Object.assign({}, options, { url: BASE + path, method: method, data: body,
      header: Object.assign({}, options.header, { 'content-type': 'application/json' }),
      success: res => {
        if (String(app.getUserID() || '') !== requestOwner) res = { code: 409, msg: '登录身份已变化，请重试' };
        if (commandKey && res && (Number(res.code) === 200 || (Number(res.code) >= 400 && Number(res.code) < 500))) delete commands[commandKey];
        if (res && Number(res.code) === 200) {
          markFeedChanged(path, method);
          try { res = Object.assign({}, res, { data: map(res.data) }); }
          catch (error) { res = { code: 500, msg: error.message }; }
        }
        if (original.success) original.success(res);
      }
    }));
  }
  function reject(options, msg) {
    const res = { code: 409, msg: msg };
    if (options.success) options.success(res); if (options.complete) options.complete(res);
    return { abort() {} };
  }
  return { request: send, normalizePost, normalizeComment, captureUpload };
}
module.exports = { createClient, normalizePost, normalizeComment, requestId };
