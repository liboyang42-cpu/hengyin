const view = require('./community-post-view.js');
const intent = require('./publish/publish-intent.js');
/** Canonical transport for the existing community entry points. */
function requestId() { return 'mini-community-' + Date.now() + '-' + Math.random().toString(36).slice(2); }
// wx GET encodes present null/undefined values as text; an absent cursor must be omitted.
function paginationQuery(query) {
  const data = Object.assign({}, query);
  if (data.cursor == null) delete data.cursor;
  return data;
}
function createClient(app, wxApi) {
  let reporting = false, disposed = false;
  const tasks = new Set(), uploads = new Map();
  const uploadOwner = String(typeof app.getUserID === 'function' ? app.getUserID() || '' : '');
  function currentOwner() { return String(typeof app.getUserID === 'function' ? app.getUserID() || '' : ''); }
  function call(task, dispatch) {
    return new Promise((resolve, reject) => {
      if (task.aborted || disposed || !task.isCurrent()) return reject({ code: 499, msg: '操作已取消' });
      if (currentOwner() !== task.owner) return reject({ code: 409, msg: '登录身份已变化，请重试' });
      task.cancel = () => reject({ code: 499, msg: '操作已取消' });
      task.current = dispatch({
        hideLoading: true, silentError: true,
        success: res => {
          if (task.aborted || disposed || !task.isCurrent()) return reject({ code: 499, msg: '操作已取消' });
          if (currentOwner() !== task.owner) return reject({ code: 409, msg: '登录身份已变化，请重试' });
          if (Number(res && res.code) === 200) resolve(res.data);
          else reject(res || { code: 500, msg: '社区请求失败' });
        },
        successStatusAbnormal: (res, status) => reject(Object.assign({}, res, { code: status, transportFailure: true })),
        fail: res => reject(Object.assign({}, res, { transportFailure: true }))
      });
    });
  }
  function pipeline(options, operation) {
    const task = { owner: currentOwner(), isCurrent: typeof options.current === 'function' ? options.current : () => true, aborted: false, current: null, abort() {
      this.aborted = true;
      if (this.cancel) this.cancel();
      if (this.current && this.current.abort) this.current.abort();
    } };
    tasks.add(task);
    Promise.resolve().then(() => {
      if (disposed || !task.isCurrent()) throw { code: 499, msg: '操作已取消' };
      return operation(task);
    }).then(data => {
      if (!task.aborted && !disposed && task.isCurrent() && options.success) options.success(Object.assign({code:200,data},task.recoveredPrevious?{recoveredPrevious:true}:{}));
    }).catch(error => {
      if (task.aborted || disposed || !task.isCurrent()) return;
      error = error || {};
      if (error.transportFailure && options.fail) options.fail(error);
      else {
        const res = Object.assign({}, error, { code: error.code || 500, msg: error.msg || error.message || '社区操作失败' },task.recoveredPrevious?{recoveredPrevious:true}:{});
        if (!options.silentError && options.autoErrorToast !== false && res.code !== 499 && typeof app.tips === 'function')
          app.tips(typeof app.getRequestErrorMessage === 'function' ? app.getRequestErrorMessage(res, '社区操作失败') : res.msg);
        if (options.success) options.success(res);
      }
    }).then(() => {
      tasks.delete(task);
      if (!disposed && task.isCurrent() && options.complete) options.complete();
    });
    return task;
  }
  async function report(options, task) {
    if (reporting) throw { code: 409, msg: '举报正在处理中' };
    reporting = true;
    try {
      const capabilities = await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/capabilities', method: 'GET', data: {},
        ...callbacks
      }));
      const taxonomy = capabilities && capabilities.reporting;
      if (!taxonomy || typeof taxonomy.policyVersion !== 'string' || !taxonomy.policyVersion.trim()
        || !Array.isArray(taxonomy.reasons) || !taxonomy.reasons.length
        || taxonomy.reasons.some(reason => !reason || typeof reason.label !== 'string' || !reason.label.trim()
          || typeof reason.code !== 'string' || !reason.code.trim()))
        throw { code: 503, msg: '举报原因暂不可用' };
      if (typeof options.selectReportReason !== 'function')
        throw { code: 503, msg: '举报原因选择暂不可用，请重试' };
      const index = await options.selectReportReason(taxonomy.reasons.map(r => r.label), task);
      if (!Number.isInteger(index) || !taxonomy.reasons[index])
        throw { code: 500, msg: '举报原因选择无效，请重试' };
      const data = options.data || {};
      return await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/reports', method: 'POST', header: 'application/json',
        data: { targetType: options.url === '/api/comment/report' ? 'COMMENT' : 'CONTENT',
          targetId: data.id, reasonCode: taxonomy.reasons[index].code,
          description: typeof data.description === 'string' && data.description.trim() ? data.description : taxonomy.reasons[index].label,
          policyVersion: taxonomy.policyVersion, requestId: data.request_id || requestId() },
        ...callbacks
      }));
    } finally {
      reporting = false;
    }
  }
  function feed(options) { return pipeline(options, async task => view.validatePostPage(await call(task, callbacks => app.sendRequest({
    url: '/api/v1/community/feeds/LATEST', auth: Boolean(task.owner), method: 'GET', data: paginationQuery(options.query), ...callbacks
  })), options.query)); }
  function myPosts(options) { return pipeline(options, async task => view.validatePostPage(await call(task, callbacks => app.sendRequest({
    url: '/api/v1/community/me/posts', method: 'GET', data: paginationQuery(options.query), ...callbacks
  })), options.query)); }
  function bookmarks(options) { return pipeline(options, async task => view.validatePostPage(await call(task, callbacks => app.sendRequest({
    url: '/api/v1/community/me/bookmarks', method: 'GET', data: paginationQuery(options.query), ...callbacks
  })), options.query)); }
  function detail(options) { return pipeline(options, async task => view.validateBundle(await readDetail(options.postId, task))); }
  async function readDetail(postId, task) { return view.validateBundle(await call(task, callbacks => app.sendRequest({
    url: '/api/v1/community/posts/' + view.id(postId), auth: Boolean(task.owner), method: 'GET', data: {}, ...callbacks
  })), postId); }
  function comments(options) { return pipeline(options, async task => {
    const page = view.validatePage(await call(task, callbacks => app.sendRequest({
      url: '/api/v1/community/posts/' + view.id(options.postId) + '/comments', auth: Boolean(task.owner), method: 'GET', data: paginationQuery(options.query), ...callbacks
    })), options.query);
    const roots = new Set(); let lastRoot;
    for (const row of page.items) {
      const normalized = view.toCommentView(row);
      if (String(normalized.postId) !== String(options.postId)) throw { code: 502, msg: '评论来源不一致，请刷新重试' };
      if (normalized.parentId == null) { lastRoot = String(normalized.id); roots.add(lastRoot); }
      else if (!roots.has(String(normalized.rootId))) throw { code: 502, msg: '评论线程响应不完整，请刷新重试' };
    }
    if (page.items.length && (page.nextCursor == null || String(page.nextCursor) !== lastRoot))
      throw { code: 502, msg: '评论根游标不完整，请刷新重试' };
    return page;
  }); }
  function requireCommand(command, key) {
    if (!command || typeof command[key] !== 'string' || !/^[A-Za-z0-9_-]{8,100}$/.test(command[key]))
      throw { code: 400, msg: '操作标识不完整，请重新发起' };
    return command;
  }
  function actionResult(result, postId, actionType) {
    view.validateBundle(result, postId);
    const state = result.post[actionType === 'LIKE' ? 'viewerLiked' : 'viewerBookmarked'];
    const count = result.post[actionType === 'LIKE' ? 'likeCount' : 'bookmarkCount'];
    if (![true,false,0,1].includes(state) || !Number.isSafeInteger(count) || count < 0)
      throw { code:502,msg:'互动结果未确认，请刷新',unknown:true };
    return result;
  }
  function addPostAction(options) { return pipeline(options, async task => {
    const command = requireCommand(options.command, 'requestId');
    if (!['LIKE','BOOKMARK','NOT_INTERESTED'].includes(command.actionType)) throw { code: 400, msg: '此互动尚未接通' };
    const result = await call(task, callbacks => app.sendRequest({
      url: '/api/v1/community/posts/' + view.id(options.postId) + '/actions', method: 'POST', header: 'application/json', data: command, ...callbacks
    }));
    if (command.actionType === 'NOT_INTERESTED') {
      if (!result || String(result.postId) !== String(options.postId) || result.action !== 'NOT_INTERESTED' || result.applied !== true)
        throw { code: 502, msg: '内容隐藏结果未确认，请刷新重试', unknown: true };
      return result;
    }
    return actionResult(result, options.postId, command.actionType);
  }); }
  function removePostAction(options) { return pipeline(options, async task => {
    if (!['LIKE','BOOKMARK'].includes(options.actionType)) throw { code: 400, msg: '此互动尚未接通' };
    const result = await call(task, callbacks => app.sendRequest({
      url: '/api/v1/community/posts/' + view.id(options.postId) + '/actions/' + options.actionType,
      method: 'DELETE', header: 'application/json', data: requireCommand(options.command, 'requestId'), ...callbacks
    }));
    return actionResult(result, options.postId, options.actionType);
  }); }
  function addComment(options) { return pipeline(options, async task => {
    const input = requireCommand(options.command, 'clientRequestId');
    const result = await call(task, callbacks => app.sendRequest({
      url: '/api/v1/community/posts/' + view.id(options.postId) + '/comments', method: 'POST', header: 'application/json',
      data: { clientRequestId: input.clientRequestId, body: input.body, parentId: input.parentId == null ? null : view.id(input.parentId) }, ...callbacks
    }));
    if (!result || typeof result.approvalState !== 'string' || typeof result.lifecycle !== 'string')
      throw { code: 502, msg: '评论提交结果未确认，请刷新重试', unknown: true };
    view.validateBundle(result.post, options.postId);
    return result;
  }); }
  function likeComment(options) { return pipeline(options, task => call(task, callbacks => app.sendRequest({
    url: `/api/v1/community/comments/${view.id(options.commentId)}/actions`, method: 'POST', header: 'application/json',
    data: requireCommand(options.command, 'requestId'), ...callbacks
  }))); }
  function unlikeComment(options) { return pipeline(options, task => call(task, callbacks => app.sendRequest({
    url: `/api/v1/community/comments/${view.id(options.commentId)}/actions/LIKE`, method: 'DELETE', header: 'application/json',
    data: requireCommand(options.command, 'requestId'), ...callbacks
  }))); }
  function deletePost(options) { return pipeline(options, task => {
    const command = requireCommand(options.command, 'requestId'); view.version(command.expectedVersion);
    return call(task, callbacks => app.sendRequest({
      url: '/api/v1/community/posts/' + view.id(options.postId), method: 'DELETE', header: 'application/json', data: command, ...callbacks
    }));
  }); }
  function deleteComment(options) { return pipeline(options, task => {
    const command = requireCommand(options.command, 'requestId'); view.version(command.expectedVersion);
    return call(task, callbacks => app.sendRequest({
      url: '/api/v1/community/posts/' + view.id(options.postId) + '/comments/' + view.id(options.commentId),
      method: 'DELETE', header: 'application/json', data: command, ...callbacks
    }));
  }); }
  function captureUpload(data) {
    if (disposed || !uploadOwner || uploadOwner !== currentOwner()) throw { code: 409, msg: '登录身份已变化，请重新选择图片' };
    if (!data || typeof data.url !== 'string' || !data.url || typeof data.uploadReceipt !== 'string' || !data.uploadReceipt
      || !Number.isSafeInteger(data.byteSize) || data.byteSize <= 0 || typeof data.mimeType !== 'string' || !/^image\//.test(data.mimeType))
      throw { code: 409, msg: '图片未上传完整，请重新选择图片' };
    const previous = uploads.get(data.url);
    if (previous && (previous.uploadReceipt !== data.uploadReceipt || previous.byteSize !== data.byteSize || previous.mimeType !== data.mimeType))
      throw { code: 409, msg: '图片上传状态已变化，请重新选择图片' };
    uploads.set(data.url, Object.assign({}, data, { owner: uploadOwner, uploadRequestId: previous ? previous.uploadRequestId : requestId() }));
    return data.url;
  }
  function clone(value) { return JSON.parse(JSON.stringify(value)); }
  function assertTask(task) {
    if (disposed || task.aborted || !task.isCurrent()) throw { code: 499, msg: '操作已取消' };
    if (task.owner !== currentOwner()) throw { code: 409, msg: '登录身份已变化，请重试' };
  }
  function saveWire(ctx, patch) {
    if (!intent.saveStage(wxApi, ctx.scope, ctx.owner, ctx.key, patch))
      throw { code: 507, msg: '无法保存提交进度，内容已保留，请稍后重试' };
    Object.assign(ctx, clone(patch));
  }
  function beginWire(options, input, task, mode) {
    assertTask(task);
    if (!task.owner) throw { code: 401, msg: '请先登录' };
    if (typeof options.scope !== 'string' || !options.scope) throw { code: 400, msg: '编辑来源不完整，请重新打开' };
    let existing;
    try { existing=wxApi.getStorageSync(intent.storageKey(options.scope,task.owner)); }
    catch (_) { throw {code:507,msg:'无法读取上次提交进度，内容已保留，请稍后重试'}; }
    const payload=[mode,options.postId || null,input], fingerprint=intent.payloadKey(payload);
    const completeWire=existing && existing.input && (existing.createBody || existing.publishBody || existing.patchBody
      || existing.ackBody || Object.keys(existing.mediaBodies || {}).length);
    const restart=options.restartDiscarded===true;
    if(restart && !(completeWire && existing.terminalDiscarded===true))throw {code:409,msg:'没有已确认删除的原提交，请先核对'};
    if(completeWire && (restart || (['sending','unknown'].includes(existing.status) && existing.payloadKey!==fingerprint))){
      if(String(existing.memberId)!==task.owner || existing.mode!==mode
        || existing.payloadKey!==intent.payloadKey([mode,options.postId || null,existing.input]) || !intent.isIntentKey(existing.key))
        throw {code:409,msg:'上次提交来源不一致，请先核对原草稿'};
      if(!restart && options.recoverPrevious!==true)throw {code:409,requiresRecovery:true,msg:'上次提交结果未确认，请先核对；本次输入仍保留'};
      task.recoveredPrevious=existing.payloadKey!==fingerprint;
      const ctx=Object.assign({},clone(existing),{scope:options.scope,owner:task.owner});
      saveWire(ctx,{stage:ctx.stage || 'PREPARE'});
      return ctx;
    }
    const started = intent.begin(wxApi, { scope: options.scope, memberId: task.owner, payload });
    const stored = intent.read(wxApi, options.scope, task.owner);
    if (!stored || stored.key !== started.key || stored.memberId !== task.owner)
      throw { code: 507, msg: '无法保存提交进度，内容已保留，请稍后重试' };
    const ctx = Object.assign({}, clone(stored), { scope: options.scope, owner: task.owner });
    saveWire(ctx,{mode,input,stage:ctx.stage || 'PREPARE'});
    const mediaBodies=clone(ctx.mediaBodies || {}), registered=clone(ctx.registered || {});
    for(const path of input.mediaPaths || []) {
      if(mediaBodies[path])continue;
      const receipt=uploads.get(path);
      if(!receipt){if(mode==='CREATE')throw {code:409,msg:'图片上传状态无法确认，请重新选择图片'};continue;}
      if(receipt.owner!==task.owner)throw {code:409,msg:'图片上传身份已变化，请重新选择'};
      const body={uploadRequestId:receipt.uploadRequestId,mediaType:'IMAGE',objectKey:receipt.url,
        uploadReceipt:receipt.uploadReceipt,mimeType:receipt.mimeType,byteSize:receipt.byteSize};
      for(const field of ['width','height'])if(receipt[field]!==undefined)body[field]=receipt[field];
      mediaBodies[path]=body;
    }
    saveWire(ctx, { mode, input, mediaBodies, registered, stage: ctx.stage || 'PREPARE' });
    return ctx;
  }
  async function confirmGuideline(options, ctx, task) {
    if (!ctx.guideline) {
      const document = await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/guidelines/active', method: 'GET', data: {}, ...callbacks
      }));
      if (!document || !document.id) throw { code: 503, msg: '社区规范不完整，暂不能提交' };
      let content = view.field(document, 'contentJson', 'content_json');
      if (typeof content === 'string') { try { content = JSON.parse(content); } catch (_) { content = null; } }
      if (!content || !Array.isArray(content.rules) || !content.rules.length
        || content.rules.some(rule => !rule || typeof rule.title !== 'string' || !rule.title.trim()))
        throw { code: 503, msg: '社区规范不完整，暂不能提交' };
      if (typeof options.selectGuideline !== 'function') throw { code: 503, msg: '社区规范确认暂不可用，请重试' };
      const approved = await options.selectGuideline(Object.assign({}, document, { rules: clone(content.rules) }), task);
      assertTask(task);
      if (approved !== true) throw { code: 499, msg: '已取消提交' };
      saveWire(ctx, { guideline: clone(document), ackBody: { guidelineVersionId: view.id(document.id), requestId: requestId(), scene: 'PUBLISH' }, stage: 'ACK' });
    }
    if (!ctx.acked) {
      assertTask(task);
      await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/guidelines/ack', method: 'POST', header: 'application/json', data: ctx.ackBody, ...callbacks
      }));
      saveWire(ctx, { acked: true, stage: 'MEDIA' });
    }
    return ctx.guideline.id;
  }
  async function resolveMedia(paths, previous, ctx, task) {
    if (paths === undefined && previous) return previous.media.map(media => view.id(media.id));
    if (!Array.isArray(paths) || paths.length > 9) throw { code: 400, msg: '图片列表不完整，请重新选择' };
    const old = previous ? previous.media : [];
    const urlOf = media => view.field(media, 'derivedObjectKey', 'derived_object_key') || view.field(media, 'objectKey', 'object_key');
    const visible = media => ['IMAGE','MAP_SNAPSHOT'].includes(view.field(media, 'mediaType', 'media_type'));
    if (paths.length + old.filter(media => !visible(media)).length > 9)
      throw { code: 400, msg: '保留附件与图片数量超过上限，请减少图片后重试' };
    const registered = clone(ctx.registered || {}), mediaBodies = clone(ctx.mediaBodies || {}), ids = [];
    for (const path of paths) {
      const existing = old.find(media => urlOf(media) === path);
      if (existing) { ids.push(view.id(existing.id)); continue; }
      if (registered[path]) { ids.push(view.id(registered[path])); continue; }
      if (!mediaBodies[path]) {
        const receipt = uploads.get(path);
        if (!receipt || receipt.owner !== task.owner) throw { code: 409, msg: '图片上传状态无法确认，请重新选择图片' };
        mediaBodies[path] = { uploadRequestId: receipt.uploadRequestId, mediaType: 'IMAGE', objectKey: receipt.url,
          uploadReceipt: receipt.uploadReceipt, mimeType: receipt.mimeType, byteSize: receipt.byteSize };
        for (const field of ['width','height']) if (receipt[field] !== undefined) mediaBodies[path][field] = receipt[field];
        saveWire(ctx, { mediaBodies });
      }
      assertTask(task);
      const media = await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/media/register', method: 'POST', header: 'application/json', data: mediaBodies[path], ...callbacks
      }));
      registered[path] = view.id(media && media.id);
      saveWire(ctx, { registered, stage: 'MEDIA' });
      ids.push(registered[path]);
    }
    if (!previous) return ids;
    const queue = ids.slice(), merged = [];
    for (const media of old) {
      if (!visible(media)) merged.push(view.id(media.id));
      else if (queue.length) merged.push(queue.shift());
    }
    return merged.concat(queue);
  }
  function refsFrom(bundle) {
    return bundle.references.map(ref => ({ referenceType: view.field(ref,'referenceType','reference_type'),
      referenceId: view.id(view.field(ref,'referenceId','reference_id')) }));
  }
  function baseWire(form, previous) {
    if (form.file_url || form.is_crowdfunding === 1 || form.is_crowdfunding === '1')
      throw { code: 409, msg: '旧附件或投票配置尚未接通，输入已保留，请先核对' };
    if ((form.pics && form.mediaPaths === undefined)
      || ['longitude','latitude','address'].some(key => form[key] != null && form[key] !== '') && form.locationPrecision !== 'EXACT')
      throw { code: 409, msg: '图片或位置配置未确认，输入已保留，请重新选择' };
    const post = previous && previous.post;
    let safety = [];
    if (post) {
      try { safety = JSON.parse(post.safetyLabelsJson || '[]'); } catch (_) { throw { code: 502, msg: '原帖安全信息无法读取，请刷新重试' }; }
      if (!Array.isArray(previous.topicCodes) || !Array.isArray(previous.mentions))
        throw { code: 502, msg: '原帖关联信息不完整，请刷新重试' };
    }
    const wire = {
      communityId: post ? post.communityId : null, postType: post ? post.postType : 'MOMENT',
      title: post ? post.title : null, body: post ? post.body : '', audience: post ? post.audience : 'PUBLIC',
      commentPolicy: post ? post.commentPolicy : 'EVERYONE', replyApprovalEnabled: post ? post.replyApprovalEnabled : 0,
      slowModeSeconds: post ? post.slowModeSeconds : 0, locationPrecision: post ? post.locationPrecision : 'NONE',
      cityCode: post ? post.cityCode : null, poiName: post ? post.poiName : null,
      approximateGeohash: post ? post.approximateGeohash : null, disclosureType: post ? post.disclosureType : 'NONE',
      safetyLabels: safety, topicCodes: post ? previous.topicCodes : [],
      mentionedMemberIds: post ? previous.mentions : [], references: post ? refsFrom(previous) : [],
      guidelineVersionId: post ? post.guidelineVersionId : null,
      clientRequestId: post ? post.clientRequestId || null : null
    };
    for (const key of Object.keys(wire)) if (form[key] !== undefined && key !== 'clientRequestId') wire[key] = clone(form[key]);
    if (typeof wire.body !== 'string' || !wire.body.trim() || wire.body.length > 5000) throw { code: 400, msg: '正文应为1至5000字' };
    wire.body = wire.body.trim();
    if (wire.locationPrecision === 'EXACT' && (wire.audience !== 'PRIVATE' || typeof wire.approximateGeohash !== 'string'
      || !/^[0123456789bcdefghjkmnpqrstuvwxyz]{9,12}$/.test(wire.approximateGeohash)))
      throw { code: 409, msg: '精确位置仅限私密内容，请明确选择可见范围，位置与输入已保留' };
    return wire;
  }
  function sameEffect(bundle, expected) {
    const actual = baseWire({}, bundle);
    actual.mediaIds = bundle.media.map(media => String(view.id(media.id)));
    const wanted = clone(expected);
    delete actual.clientRequestId; delete wanted.clientRequestId; delete wanted.expectedVersion;
    for (const body of [actual, wanted]) for (const key of ['communityId','guidelineVersionId']) if (body[key] != null) body[key] = String(body[key]);
    wanted.mediaIds = wanted.mediaIds.map(String);
    for (const body of [actual, wanted]) {
      body.topicCodes = body.topicCodes.map(value => String(value).trim().toUpperCase()).sort();
      body.mentionedMemberIds = body.mentionedMemberIds.map(String).sort();
      body.references = body.references.map(ref => ({ referenceType: ref.referenceType, referenceId: String(ref.referenceId) }));
    }
    return JSON.stringify(actual) === JSON.stringify(wanted);
  }
  function recordFailure(ctx, error) {
    const unknown = !(error && error.intentDiscarded===true) && !!(ctx.status === 'unknown' || ctx.patchUnknown || error && (error.unknown || error.transportFailure || Number(error.code) >= 500
      || Number(error.code) === 499 && !!(ctx.ackBody || ctx.createBody || ctx.patchBody || ctx.publishBody)));
    intent.saveStage(wxApi, ctx.scope, ctx.owner, ctx.key, { status: unknown ? 'unknown' : 'rejected',
      pendingStage: ctx.stage, patchUnknown: ctx.mode === 'EDIT' && !!ctx.patchBody && (unknown || Number(error.code) === 499) });
    return Object.assign({}, error, { unknown, stage: ctx.stage, draftId: ctx.draftId });
  }
  function publishNew(options) { return pipeline(options, async task => {
    const callerInput=clone(options.form || {});
    let ctx=beginWire(options,callerInput,task,'CREATE'),input=ctx.input;
    try {
      for(let attempt=0;attempt<2;attempt++){
      if (!ctx.createBody) {
        const body = baseWire(input);
        const guideline = await confirmGuideline(options, ctx, task);
        body.guidelineVersionId = guideline;
        body.mediaIds = await resolveMedia(input.mediaPaths || [], null, ctx, task);
        body.clientRequestId = ctx.key;
        saveWire(ctx, { createBody: body, stage: 'CREATE' });
      }
      let bundle;
      if (!ctx.draftId) {
        assertTask(task);
        const raw=await call(task, callbacks => app.sendRequest({
          url: '/api/v1/community/posts', method: 'POST', header: 'application/json', data: ctx.createBody, ...callbacks
        }));
        if(raw && raw.intentDiscarded===true && Object.keys(raw).length===1){
          saveWire(ctx,{terminalDiscarded:true,stage:'DELETED_TERMINAL'});
          if(options.restartDiscarded!==true || attempt>0)throw {code:409,intentDiscarded:true,requiresRestart:true,msg:'原提交已删除，内容保留；请确认是否作为新帖重新提交'};
          const oldMedia=Object.values(ctx.mediaBodies || {});
          for(const path of callerInput.mediaPaths || []){
            const capture=uploads.get(path);
            if((ctx.mediaBodies && ctx.mediaBodies[path]) || (ctx.registered && ctx.registered[path])
              || !capture || capture.owner!==task.owner || oldMedia.some(media=>media.uploadReceipt===capture.uploadReceipt))
              throw {code:409,requiresReupload:true,msg:'原图片或路线预览不能直接重发，请重新选图或重选活动上传；正文、关联和可见范围仍保留'};
          }
          assertTask(task);
          if(!intent.discard(wxApi,ctx.scope,ctx.owner,ctx.key))throw {code:507,msg:'无法保存原提交的删除痕迹，内容已保留'};
          ctx=beginWire({...options,restartDiscarded:false,recoverPrevious:false},callerInput,task,'CREATE');
          input=ctx.input;task.recoveredPrevious=false;
          continue;
        }
        bundle=view.validateBundle(raw);
        if (bundle.viewerIsAuthor !== true || String(bundle.post.authorId) !== task.owner
          || String(bundle.post.clientRequestId) !== ctx.key) throw { code: 502, msg: '草稿归属无法确认' };
        saveWire(ctx, { draftId: bundle.post.id, draftVersion: bundle.post.version, stage: 'PUBLISH' });
      } else bundle = view.validateBundle(await readDetail(ctx.draftId, task));
      if (!sameEffect(bundle, ctx.createBody)) throw { code: 409, msg: '原草稿内容已变化，请在我的推文中核对', conflict: true };
      if (bundle.post.lifecycle !== 'DRAFT' && !ctx.publishBody) {
        if (String(bundle.post.id) !== String(ctx.draftId) || bundle.viewerIsAuthor !== true
        || String(bundle.post.authorId) !== task.owner || !['CHECKING','PUBLISHED'].includes(bundle.post.lifecycle)) throw { code: 409, msg: '原草稿状态已变化，请在我的推文中核对' };
        if(!intent.settle(wxApi, ctx.scope, ctx.owner, ctx.key, 'success'))throw {code:507,msg:'提交结果已确认，但手机记录未能清理；内容已保留，请稍后核对'}; return bundle;
      }
      if (!ctx.publishBody) saveWire(ctx, { publishBody: { expectedVersion: ctx.draftVersion, requestId: requestId() }, stage: 'PUBLISH' });
      assertTask(task);
      bundle = view.validateBundle(await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/posts/' + view.id(ctx.draftId) + '/publish', method: 'POST', header: 'application/json', data: ctx.publishBody, ...callbacks
      })));
      if (String(bundle.post.id) !== String(ctx.draftId) || bundle.viewerIsAuthor !== true
        || String(bundle.post.authorId) !== task.owner || !['CHECKING','PUBLISHED'].includes(bundle.post.lifecycle)) throw { code: 502, msg: '发布状态未确认', unknown: true };
      if(!intent.settle(wxApi, ctx.scope, ctx.owner, ctx.key, 'success'))throw {code:507,msg:'提交结果已确认，但手机记录未能清理；内容已保留，请稍后核对'}; return bundle;
      }
    } catch (error) { throw recordFailure(ctx, error); }
  }); }
  function saveEdit(options) { return pipeline(options, async task => {
    const callerInput = clone(options.changes || {}), ctx = beginWire(options, callerInput, task, 'EDIT'), input=ctx.input;
    try {
      const current = view.validateBundle(await readDetail(options.postId, task));
      if (current.viewerIsAuthor !== true || String(current.post.authorId) !== task.owner) throw { code: 403, msg: '无权编辑该帖文' };
      if (!ctx.patchBody) {
        if (!['DRAFT','CHECKING','PUBLISHED','LIMITED','HIDDEN'].includes(current.post.lifecycle)) throw { code: 409, msg: '该帖文当前不可编辑' };
        const body = baseWire(input, current);
        if (current.post.lifecycle !== 'DRAFT') body.guidelineVersionId = await confirmGuideline(options, ctx, task);
        body.mediaIds = await resolveMedia(input.mediaPaths, current, ctx, task);
        body.expectedVersion = current.post.version;
        const baseline = baseWire({}, current); baseline.mediaIds = current.media.map(media => view.id(media.id));
        saveWire(ctx, { patchBody: body, beforeBody: baseline, stage: 'PATCH' });
      } else {
        const expected = ctx.patchBody.expectedVersion;
        if (current.post.version === expected + 1 && sameEffect(current, ctx.patchBody)) {
          if(!intent.settle(wxApi, ctx.scope, ctx.owner, ctx.key, 'success'))throw {code:507,msg:'提交结果已确认，但手机记录未能清理；内容已保留，请稍后核对'}; return current;
        }
        if (current.post.version !== expected || !sameEffect(current, ctx.beforeBody))
          throw { code: 409, msg: '帖文已变化，请核对后再保存', conflict: true };
        if (options.retryUnknown !== true) throw { code: 409, msg: '上次修改结果未确认，请核对后重试', retrySameWire: true };
      }
      assertTask(task);
      const result = view.validateBundle(await call(task, callbacks => app.sendRequest({
        url: '/api/v1/community/posts/' + view.id(options.postId), method: 'PATCH', header: 'application/json', data: ctx.patchBody, ...callbacks
      })));
      if (String(result.post.id) !== String(options.postId) || result.viewerIsAuthor !== true
        || String(result.post.authorId) !== task.owner || result.post.version !== ctx.patchBody.expectedVersion + 1 || !sameEffect(result, ctx.patchBody))
        throw { code: 502, msg: '修改结果未确认，请回读后再操作', unknown: true };
      if(!intent.settle(wxApi, ctx.scope, ctx.owner, ctx.key, 'success'))throw {code:507,msg:'提交结果已确认，但手机记录未能清理；内容已保留，请稍后核对'}; return result;
    } catch (error) { throw recordFailure(ctx, error); }
  }); }

  function dispose() { disposed = true; tasks.forEach(task => task.abort()); uploads.clear(); }

  function send(options) {
    return pipeline(options, task => {
      if (options.url !== '/api/creativesquare/report' && options.url !== '/api/comment/report')
        throw { code: 409, msg: '此社区功能尚未接通' };
      return report(options, task);
    });
  }
  return { request: send, feed, myPosts, bookmarks, detail, comments, addPostAction, removePostAction,
    addComment, likeComment, unlikeComment, deletePost, deleteComment, captureUpload, publishNew, saveEdit, dispose };
}
module.exports = { createClient };
