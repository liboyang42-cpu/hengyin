/** Canonical community transport; legacy-shaped view models are local presentation only. */
const { decodeDisplayText } = require('./display-text.js');
const reportIntent = require('./publish/publish-intent.js');
const { exactId } = require('./user-character.js');
const commentRecovery = require('./community-comment-recovery.js');
const BASE = '/api/v1/community';
const LIFECYCLE_LABELS = { DRAFT: '草稿', CHECKING: '审核中', LIMITED: '展示受限', HIDDEN: '已隐藏', REMOVED: '已移除' };
function requestId() { return 'mini-community-' + Date.now() + '-' + Math.random().toString(36).slice(2); }
function value(row, camel, snake) { return row[camel] !== undefined ? row[camel] : row[snake]; }
function normalizePost(bundle) {
  if (!bundle || !bundle.post || !bundle.post.id) throw new Error('帖文响应不完整');
  const post = bundle.post, media = mediaRows(bundle.media || []);
  const row = Object.assign({}, post, {
    memberId: post.authorId, memberNickname: post.authorNickname, memberAvatar: post.authorAvatar,
    contents: decodeDisplayText(post.body), isLiked: post.viewerLiked, isBookmarked: post.viewerBookmarked,
    isFollowTheUser: bundle.viewerFollowing === true ? 1 : bundle.viewerFollowing === false ? 0 : undefined,
    status: post.lifecycle === 'PUBLISHED' ? 1 : post.lifecycle === 'DRAFT' || post.lifecycle === 'CHECKING' ? 0 : post.lifecycle,
    pics: media.map(m => m.url).filter(Boolean).join(';'), media,
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
    contents: decodeDisplayText(row.body), createTime: value(row, 'createTime', 'create_time'),
    isLiked: value(row, 'viewerLiked', 'viewer_liked'), likeCount: value(row, 'likeCount', 'like_count'),
    isFollowTheUser: value(row, 'viewerFollowing', 'viewer_following'),
    rootId: value(row, 'rootId', 'root_id'), parentId: value(row, 'parentId', 'parent_id') });
}
function createClient(app, wxApi) {
  const posts = Object.create(null), comments = Object.create(null), pages = Object.create(null), uploads = Object.create(null), commands = Object.create(null);
  let owner = null, credential = '', ownerEpoch = 0, mutationRevision = 0, reporting = null;
  const currentCredential = () => typeof app.getAuthorization === 'function' ? app.getAuthorization() || '' : '';
  function resetForOwner() {
    const current = String(typeof app.getUserID === 'function' ? app.getUserID() || '' : '');
    if (owner !== current || credential !== currentCredential()) {
      [posts, comments, pages, uploads, commands].forEach(store => Object.keys(store).forEach(key => delete store[key]));
      owner = current; credential = currentCredential(); ownerEpoch += 1; reporting = null;
    }
  }
  function markFeedChanged(path, method) {
    if (method === 'GET' || !/^\/(posts(?:\/|$)|comments\/)/.test(path)) return;
    mutationRevision += 1;
    if (!app.globalData) return;
    app.globalData.squareListDirty = true;
    app.globalData.squareListDirtyVersion = (app.globalData.squareListDirtyVersion || 0) + 1;
  }
  function captureUpload(data) {
    resetForOwner();
    if (!data || !data.url || !data.uploadReceipt || !(data.byteSize > 0) || !data.mimeType) throw new Error('图片上传未完成，请重新上传');
    uploads[data.url] = Object.assign({}, data, { uploadRequestId: requestId() });
    return data.url;
  }
  function call(path, method, data, task, ownReportLookup) {
    return new Promise((resolve, reject) => {
      if (task.aborted || !task.isCurrent()) return reject({ code: 499, msg: '操作已取消' });
      if (String(app.getUserID() || '') !== task.owner) return reject({ code: 409, msg: '登录身份已变化，请重试' });
      let settled = false;
      task.cancel = () => { settled = true; reject({ code: 499, msg: '操作已取消' }); };
      const request = { url: BASE + path, method, data: data || {}, hideLoading: true, silentError: true,
        header: { 'content-type': 'application/json' },
        success: res => { if (settled) return; settled = true; if (task.aborted || !task.isCurrent()) return reject({ code: 499, msg: '操作已取消' }); if (String(app.getUserID() || '') !== task.owner) return reject({ code: 409, msg: '登录身份已变化，请重试' }); if (Number(res && res.code) === 200) { markFeedChanged(path, method); resolve(res.data); } else reject(res || { code: 500, msg: '社区请求失败' }); },
        successStatusAbnormal: (res, status) => { if (settled) return; settled = true; reject(Object.assign({}, res, { code: status, transportFailure: true })); },
        fail: res => { if (settled) return; settled = true; reject(Object.assign({}, res, { transportFailure: true })); } };
      if (ownReportLookup === true) request.url = '/api/community-trust/reports/mine';
      task.current = app.sendRequest(request);
    });
  }
  function modal(options, task) {
    return new Promise((resolve, reject) => {
      if (task.aborted || !task.isCurrent()) return reject({ code: 499, msg: '操作已取消' });
      task.cancel = () => reject({ code: 499, msg: '操作已取消' });
      wxApi.showModal(Object.assign({}, options, { success: res => res.confirm ? resolve() : reject({ code: 499, msg: '已取消发布' }),
        fail: () => reject({ code: 499, msg: '已取消发布' }) }));
    });
  }
  function pipeline(options, operation) {
    const epoch = ownerEpoch, auth = credential;
    const task = { owner, isCurrent: () => epoch === ownerEpoch && auth === currentCredential()
      && (typeof options.current !== 'function' || options.current()), aborted: false, current: null, abort() { this.aborted = true; if (this.cancel) this.cancel(); if (this.current && this.current.abort) this.current.abort(); } };
    Promise.resolve().then(() => operation(task)).then(data => {
      if (!task.aborted && task.isCurrent() && String(app.getUserID() || '') === task.owner && options.success) options.success(Object.assign({ code: 200, data }, task.recoveredPrevious ? { recoveredPrevious: true } : {}));
    }).catch(error => {
      if (task.aborted || !task.isCurrent() || String(app.getUserID() || '') !== task.owner) return;
      if (error.transportFailure && options.fail) options.fail(error);
      else {
        const res = Object.assign({}, error, { code: error.code || 500, msg: error.msg || error.message || '社区操作失败' });
        if (!options.silentError && options.autoErrorToast !== false && res.code !== 499 && typeof app.tips === 'function') app.tips(typeof app.getRequestErrorMessage === 'function' ? app.getRequestErrorMessage(res, '社区操作失败') : res.msg);
        if (options.success) options.success(res);
      }
    }).then(() => { if (task.isCurrent() && String(app.getUserID() || '') === task.owner && options.complete) options.complete(); });
    return task;
  }
  async function publish(form, task, options) {
    const bundle = await require('./community-submit-progress.js')({ app, wxApi, task, form, options,
      call, modal, requestId, uploads, previousPost: posts[form.id] });
    const row = normalizePost(bundle); posts[row.id] = row;
    return row;
  }
  async function report(options, task) {
    if (reporting) throw { code: 409, msg: '举报正在处理中' };
    reporting = task;
    try {
      const data = options.data || {}, targetType = options.url === '/api/comment/report' ? 'COMMENT' : 'CONTENT';
      const scope = 'square:report:' + targetType + ':' + data.id;
      const validReportKey = value => typeof value === 'string' && value.trim().length >= 8 && value.trim().length <= 96;
      const storageError = () => ({ code: 507, msg: '举报记录未能保存，未重新提交，请稍后确认' });
      const current = () => {
        if (task.aborted || !task.isCurrent()) throw { code: 499, msg: '已取消举报' };
        if (String(app.getUserID() || '') !== task.owner) throw { code: 409, msg: '登录身份已变化，请重试' };
      };
      let stored, wasUnknown = false;
      try { stored = wxApi.getStorageSync(reportIntent.storageKey(scope, task.owner)); }
      catch (_) { throw storageError(); }
      const save = patch => {
        current();
        if (!reportIntent.saveStage(wxApi, scope, task.owner, stored.key, patch)) throw storageError();
        stored = Object.assign({}, stored, patch);
      };
      const matches = row => row && /^[1-9][0-9]*$/.test(String(row.id)) && String(row.targetType) === stored.wire.targetType
        && String(row.targetId) === String(stored.wire.targetId)
        && row.reasonCode === stored.wire.reasonCode.trim().toUpperCase()
        && row.policyVersion === stored.wire.policyVersion.trim()
        && row.description === stored.wire.description.trim()
        && Array.isArray(row.evidenceAssetIds) && JSON.stringify(row.evidenceAssetIds.map(String).sort()) === JSON.stringify(stored.wire.evidenceAssetIds.map(String).sort());
      const finish = row => {
        if (!matches(row)) throw { code: 409, msg: '举报结果异常，请刷新后再试' };
        save({ status: 'confirmed' });
        try {
          wxApi.removeStorageSync(reportIntent.storageKey(scope, task.owner));
          if (wxApi.getStorageSync(reportIntent.storageKey(scope, task.owner))) throw storageError();
        } catch (_) { throw storageError(); }
        return row;
      };
      if (stored) {
        if (String(stored.memberId) !== task.owner || !reportIntent.isIntentKey(stored.key) || !stored.wire
          || !validReportKey(stored.wire.requestId) || stored.wire.targetType !== targetType
          || String(stored.wire.targetId) !== String(data.id) || typeof stored.wire.description !== 'string'
          || !stored.wire.description.trim() || typeof stored.wire.reasonCode !== 'string'
          || typeof stored.wire.policyVersion !== 'string' || !Array.isArray(stored.wire.evidenceAssetIds)) throw { code: 409, msg: '原举报记录不完整，请先确认' };
        if (stored.status === 'rejected' && stored.rejectionCode === 400) {
          await modal({ title: '重新确认举报', content: '上次举报明确未被接受。将重新读取当前规则和原因，再提交修正后的举报。', confirmText: '重新确认', cancelText: '取消' }, task);
          current();
        } else {
          wasUnknown = true;
          const rows = await call('', 'GET', { requestId: stored.wire.requestId }, task, true);
          current();
          if (!Array.isArray(rows) || rows.length > 1) throw { code: 409, msg: '举报结果未能唯一确认，请稍后确认' };
          if (rows.length === 1) return finish(rows[0]);
          await modal({ title: '上次举报尚未确认', content: '暂未查到结果，是否重试？', confirmText: '原样重试', cancelText: '取消' }, task);
          current();
        }
      }
      if (!stored || stored.status === 'rejected') {
        const taxonomy = (await call('/capabilities', 'GET', {}, task)).reporting;
        if (!taxonomy || typeof taxonomy.policyVersion !== 'string' || !taxonomy.policyVersion.trim()
          || !Array.isArray(taxonomy.reasons) || !taxonomy.reasons.length
          || taxonomy.reasons.some(reason => !reason || typeof reason.label !== 'string' || !reason.label.trim()
            || typeof reason.code !== 'string' || !reason.code.trim())) throw { code: 503, msg: '举报原因暂不可用' };
        if (typeof options.selectReportReason !== 'function') throw { code: 503, msg: '举报原因选择暂不可用，请重试' };
        const index = await options.selectReportReason(taxonomy.reasons.map(r => r.label), task);
        current();
        if (!Number.isInteger(index) || !taxonomy.reasons[index]) throw { code: 500, msg: '举报原因选择无效，请重试' };
        const reason = taxonomy.reasons[index];
        if (data.request_id != null && !validReportKey(data.request_id)) throw { code: 400, msg: '举报编号无效，请重试' };
        if (data.evidenceAssetIds !== undefined && !Array.isArray(data.evidenceAssetIds)) throw { code: 400, msg: '举报证据格式不合法' };
        const wire = { targetType, targetId: data.id, reasonCode: reason.code, policyVersion: taxonomy.policyVersion,
          description: typeof data.description === 'string' && data.description.trim() ? data.description : reason.label,
          evidenceAssetIds: Array.isArray(data.evidenceAssetIds) ? JSON.parse(JSON.stringify(data.evidenceAssetIds)) : [] };
        const previousRequestId = stored && stored.wire && stored.wire.requestId;
        const fresh = !stored;
        if (fresh) {
          const started = reportIntent.begin(wxApi, { scope, memberId: task.owner, payload: wire });
          stored = reportIntent.read(wxApi, scope, task.owner);
          if (!stored || stored.key !== started.key || String(stored.memberId) !== task.owner) throw storageError();
        }
        wire.requestId = previousRequestId || data.request_id || stored.key;
        try { save({ wire, status: 'prepared', rejectionCode: null }); }
        catch (error) {
          if (fresh) reportIntent.removeUnsentPartial(wxApi, scope, task.owner, stored.key);
          throw error;
        }
      }
      save({ status: 'sending' });
      try { return finish(await call('/reports', 'POST', stored.wire, task)); }
      catch (error) {
        if (String(app.getUserID() || '') === task.owner && !task.aborted && task.isCurrent())
          reportIntent.saveStage(wxApi, scope, task.owner, stored.key,
            !wasUnknown && !error.transportFailure && Number(error.code) === 400
              ? { status: 'rejected', rejectionCode: 400 } : { status: 'unknown', rejectionCode: null });
        throw error;
      }
    } finally {
      if (reporting === task) reporting = null;
    }
  }
  function send(options) {
    resetForOwner();
    if (typeof options.current === 'function' && !options.current()) return { abort() {} };
    const original = options, data = options.data || {}, url = options.url;
    if (!/^\/api\/(creativesquare|comment)\//.test(url)) return app.sendRequest(options);
    if (url === '/api/creativesquare/action') return pipeline(options, task => publish(data, task, options));
    if (url === '/api/creativesquare/report' || url === '/api/comment/report') return pipeline(options, task => report(options, task));
    const requestOwner = owner, requestCredential = credential, requestOwnerEpoch = ownerEpoch, readRevision = mutationRevision;
    let aborted = false, settled = false, completed = false;
    const viewCurrent = () => !aborted && (typeof options.current !== 'function' || options.current());
    const identityCurrent = () => String(app.getUserID() || '') === requestOwner
      && currentCredential() === requestCredential && ownerEpoch === requestOwnerEpoch;
    let commandKey, commentRecord;
    const stableCommand = (key, proposed) => { commandKey = key; if (!commands[key]) commands[key] = proposed || requestId(); return commands[key]; };
    let path, method = 'GET', body = {}, map = x => x, pageKey, page, pageState;
    const id = data.id || data.owner_id;
    if (url === '/api/creativesquare/list' || url === '/api/comment/list') {
      page = Number(data.pageNum || 1);
      pageKey = url + ':' + (data.favorite_only ? 'BOOKMARKS' : data.is_my ? 'OWN' : data.user_id || data.owner_id || 'LATEST');
      if (page === 1) pages[pageKey] = { generation: requestId(), cursors: { 1: null } };
      const state = pages[pageKey];
      pageState = state;
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
      if (type === 'LIKE' && Number(data.type) !== 1) return reject(options, '此互动暂未开放');
      const previous = posts[id];
      if (type === 'LIKE' && !previous) return reject(options, '请刷新帖文后再操作');
      const enabled = type === 'BOOKMARK' ? Number(data.bookmark) === 1 : Number(previous.isLiked) !== 1;
      path = '/posts/' + id + '/actions' + (enabled ? '' : '/' + type); method = enabled ? 'POST' : 'DELETE';
      body = { requestId: stableCommand('action:' + id + ':' + type + ':' + enabled, data.request_id) }; if (enabled) { body.actionType = type; body.source = 'LEGACY_MINIPROGRAM'; }
      map = bundle => { const row = normalizePost(bundle); posts[row.id] = row; return row; };
    } else if (url === '/api/creativesquare/delete') {
      if (!posts[id]) return reject(options, '请刷新帖文后再操作');
      path = '/posts/' + id; method = 'DELETE'; body = { expectedVersion: posts[id].version, requestId: stableCommand('delete:' + id + ':' + posts[id].version, data.request_id) };
      map = result => {
        delete posts[id];
        Object.keys(comments).forEach(key => { if (String(value(comments[key], 'postId', 'post_id')) === String(id)) delete comments[key]; });
        return result;
      };
    } else if (url === '/api/comment/add') {
      path = '/posts/' + data.owner_id + '/comments'; method = 'POST';
      const hasParent = data.reply_id != null && data.reply_id !== '' && data.reply_id !== 0 && data.reply_id !== '0';
      const parent = hasParent ? exactId(data.reply_id) : '';
      if (hasParent && !parent) return reject(options, '回复目标无效，请重新选择');
      const parentId = parent ? Number.isSafeInteger(Number(parent)) ? Number(parent) : parent : null;
      try {
        commentRecord = commentRecovery.prepare(wxApi, requestOwner, data.owner_id,
          { body: String(data.contents || '').trim(), parentId, clientRequestId: data.request_id || requestId() });
        body = { ...commentRecord.row.wire };
      } catch (error) { return reject(options, error.msg, error.code, error.pendingComment); }
      map = result => { commentRecovery.finish(wxApi, requestOwner, data.owner_id, commentRecord, 200); return result; };
    } else if (url === '/api/comment/like') {
      const row = comments[id]; if (!row) return reject(options, '请刷新评论后再操作');
      const enabled = Number(row.isLiked) !== 1;
      path = '/comments/' + id + '/actions' + (enabled ? '' : '/LIKE'); method = enabled ? 'POST' : 'DELETE'; body = { requestId: stableCommand('comment-like:' + id + ':' + enabled, data.request_id) };
      map = () => { comments[id] = Object.assign({}, row, { isLiked: enabled ? 1 : 0 }); return { isLiked: enabled ? 1 : 0 }; };
    } else if (url === '/api/comment/delete') {
      const row = comments[id]; if (!row) return reject(options, '请刷新评论后再操作');
      path = '/posts/' + value(row, 'postId', 'post_id') + '/comments/' + id; method = 'DELETE';
      body = { expectedVersion: row.version, requestId: stableCommand('delete-comment:' + id + ':' + row.version, data.request_id) };
    } else return reject(options, '此社区功能暂未开放');
    const transport = app.sendRequest(Object.assign({}, options, { url: BASE + path, method: method, data: body,
      header: Object.assign({}, options.header, { 'content-type': 'application/json' }),
      success: res => {
        if (settled || !viewCurrent()) return;
        settled = true;
        if (!identityCurrent()) {
          if (original.success) original.success({ code: 409, msg: '登录身份已变化，请重试' });
          return;
        }
        if (method === 'GET' && readRevision !== mutationRevision) {
          if (original.success) original.success({ code: 409, msg: '社区内容已变化，请刷新' });
          return;
        }
        if (pageState && pages[pageKey] !== pageState) {
          if (original.success) original.success({ code: 409, msg: '分页游标已失效，请刷新' });
          return;
        }
        if (commentRecord && Number(res && res.code) !== 200) {
          try { commentRecovery.finish(wxApi, requestOwner, data.owner_id, commentRecord, res && res.code); }
          catch (error) { res = { code: error.code || 507, msg: error.msg }; }
        }
        if (commandKey && commands[commandKey] === (body.requestId || body.clientRequestId) && res && (Number(res.code) === 200 || (Number(res.code) >= 400 && Number(res.code) < 500))) delete commands[commandKey];
        if (res && Number(res.code) === 200) {
          markFeedChanged(path, method);
          try { res = Object.assign({}, res, { data: map(res.data) }); }
          catch (error) { res = { code: 500, msg: error.message }; }
        }
        if (original.success) original.success(res);
      },
      fail: (res, status) => {
        if (settled || !viewCurrent()) return;
        settled = true;
        if (!identityCurrent()) {
          if (original.success) original.success({ code: 409, msg: '登录身份已变化，请重试' });
        } else {
          if (commentRecord) { try { commentRecovery.finish(wxApi, requestOwner, data.owner_id, commentRecord, null); } catch (error) { res = Object.assign({}, res, { msg: error.msg, storageFailure: true }); } }
          if (original.fail) original.fail(res, status);
        }
      },
      successStatusAbnormal: (res, status) => {
        if (settled || !viewCurrent()) return;
        settled = true;
        if (!identityCurrent()) {
          if (original.success) original.success({ code: 409, msg: '登录身份已变化，请重试' });
        } else if (original.successStatusAbnormal) original.successStatusAbnormal(res, status);
        else if (original.fail) original.fail(res, status);
      },
      complete: res => {
        if (completed || !viewCurrent()) return;
        completed = true;
        if (original.complete) original.complete(identityCurrent() ? res : { code: 409, msg: '登录身份已变化，请重试' });
      }
    }));
    return { abort() { aborted = true; if (transport && transport.abort) transport.abort(); } };
  }
  function reject(options, msg, code = 409, pendingComment) {
    const res = { code, msg, ...(pendingComment ? { pendingComment } : {}) };
    if (options.success) options.success(res); if (options.complete) options.complete(res);
    return { abort() {} };
  }
  return { request: send, normalizePost, normalizeComment, captureUpload,
    pendingComment(postId) {
      resetForOwner();
      const row = commentRecovery.pending(wxApi, owner, postId);
      return row && row.status !== 'rejected' ? { ...row.wire } : null;
    } };
}
function mediaRows(rows) {
  return rows.map(m => ({ id: m.id,
    url: value(m, 'derivedObjectKey', 'derived_object_key') || value(m, 'objectKey', 'object_key') }));
}
module.exports = { createClient, normalizePost, normalizeComment, requestId };
