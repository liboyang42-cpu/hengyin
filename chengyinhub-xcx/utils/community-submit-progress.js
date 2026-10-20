const intent = require('./publish/publish-intent.js');
const { decodeDisplayText } = require('./display-text.js');
const clone = value => JSON.parse(JSON.stringify(value));
const field = (row, a, b) => row[a] !== undefined ? row[a] : row[b];

module.exports = async function submitProgress(env) {
  const { wxApi, task, call, modal, requestId, uploads } = env;
  const owner = task.owner, scope = 'square:wire:' + (env.options.intentScope || (env.form.id ? 'edit:' + env.form.id : 'create'));
  const input = clone(env.form); delete input.request_id;
  const selectedMediaIds = form => {
    const post = env.previousPost, selected = {};
    if (!form.id || !post || String(post.id) !== String(form.id) || String(post.memberId) !== owner || !Array.isArray(post.media)) return selected;
    for (const path of String(form.pics || '').split(';').filter(Boolean)) {
      const matches = post.media.filter(m => m.url === path && /^[1-9][0-9]*$/.test(String(m.id)));
      if (matches.length > 1) throw { code: 409, msg: '图片身份无法唯一确认，请刷新后重试' };
      if (matches.length === 1) selected[path] = matches[0].id;
    }
    return selected;
  };
  let ctx, freshPartial = false, wasUnknown = false, unknownPhase = null, failedWritePhase = null, attemptWrote = false;
  const check = () => {
    if (task.aborted || !task.isCurrent()) throw { code: 499, msg: '操作已取消' };
    if (String(env.app.getUserID() || '') !== owner) throw { code: 409, msg: '登录身份已变化，请重试' };
  };
  const save = patch => {
    check();
    if (!intent.saveStage(wxApi, scope, owner, ctx.key, patch)) throw { code: 507, msg: '提交进度保存失败，内容已保留，未继续提交' };
    Object.assign(ctx, clone(patch));
  };
  const write = async (phase, path, method, body) => {
    save({ status: 'sending', writePhase: phase });
    attemptWrote = true;
    try { return await call(path, method, body, task); }
    catch (error) { failedWritePhase = phase; throw error; }
  };
  const owned = bundle => {
    if (!bundle || !bundle.post || !Array.isArray(bundle.media) || !Array.isArray(bundle.references)
      || String(bundle.post.authorId) !== owner || bundle.viewerIsAuthor !== true
      || !Number.isInteger(bundle.post.version) || bundle.post.version < 0)
      throw { code: 502, msg: '本人帖文状态未能确认，请先核对' };
    return bundle;
  };
  const effect = bundle => {
    const p = bundle.post;
    const result = { body: decodeDisplayText(p.body), audience: p.audience, mediaIds: bundle.media.map(m => String(m.id)),
      references: bundle.references.map(r => ({ referenceType: field(r, 'referenceType', 'reference_type'), referenceId: String(field(r, 'referenceId', 'reference_id')) })) };
    for (const key of ['postType', 'title', 'communityId', 'commentPolicy', 'replyApprovalEnabled', 'slowModeSeconds', 'locationPrecision', 'cityCode', 'poiName', 'approximateGeohash', 'disclosureType']) result[key] = p[key];
    result.topicCodes = (bundle.topicCodes || []).map(String).sort(); result.mentionedMemberIds = (bundle.mentions || []).map(String).sort();
    try { result.safetyLabels = JSON.parse(p.safetyLabelsJson || '[]'); } catch (_) { throw { code: 502, msg: '帖文安全信息不完整' }; }
    return result;
  };
  const same = (bundle, body) => {
    const actual = effect(bundle), expected = clone(body);
    delete expected.clientRequestId; delete expected.expectedVersion; delete expected.guidelineVersionId;
    expected.body = decodeDisplayText(expected.body); expected.mediaIds = expected.mediaIds.map(String);
    expected.references = expected.references.map(r => ({ referenceType: r.referenceType, referenceId: String(r.referenceId) }));
    if (expected.topicCodes) expected.topicCodes = expected.topicCodes.map(String).sort();
    if (expected.mentionedMemberIds) expected.mentionedMemberIds = expected.mentionedMemberIds.map(String).sort();
    return Object.keys(expected).every(key => JSON.stringify(actual[key]) === JSON.stringify(expected[key]));
  };
  const read = id => call('/posts/' + id, 'GET', {}, task).then(owned);
  const finish = bundle => {
    owned(bundle); save({ status: 'confirmed' });
    try { wxApi.removeStorageSync(intent.storageKey(scope, owner)); if (wxApi.getStorageSync(intent.storageKey(scope, owner))) throw Error(); }
    catch (_) { throw { code: 507, msg: '提交已确认，但本机进度未能清理，请先核对' }; }
    return bundle;
  };
  try {
    check();
    if (!owner) throw { code: 401, msg: '请先登录' };
    try { ctx = wxApi.getStorageSync(intent.storageKey(scope, owner)); }
    catch (_) { throw { code: 507, msg: '上次提交记录无法读取，未继续提交' }; }
    if (ctx) {
      const rejected = ctx.status === 'rejected' && (ctx.rejectionCode === 400 || ctx.rejectionCode === 409 && ctx.rejectedPhase === 'ack');
      const createdDraft = ctx.createdAsNew && ctx.draftId;
      if (String(ctx.memberId) !== owner || !intent.isIntentKey(ctx.key) || !ctx.input
        || (String(ctx.input.id || '') !== String(input.id || '')
          && !(createdDraft && (!input.id || String(input.id) === String(ctx.draftId))))) throw { code: 409, msg: '原提交来源不一致，请先核对' };
      const comparisonInput = clone(ctx.input);
      if (createdDraft && !input.id) delete comparisonInput.id;
      wasUnknown = !rejected && (ctx.status === 'unknown' || ctx.status === 'sending');
      unknownPhase = ctx.writePhase;
      if (rejected) {
        await modal({ title: '修正后重新提交', content: ctx.draftId ? '上次提交明确未被接受。将在同一份本人草稿上保存修正，再重新提交。' : '上次提交明确未被接受。将按当前输入重新确认规则后提交。', confirmText: '修正提交', cancelText: '取消' }, task);
        check();
        const captures = Object.assign({}, ctx.captures);
        for (const path of String(input.pics || '').split(';').filter(Boolean)) if (uploads[path]) captures[path] = clone(uploads[path]);
        save({ input: ctx.draftId ? Object.assign({}, input, { id: ctx.draftId }) : input, captures, mediaSelections: selectedMediaIds(input),
          createBody: null, patchBody: null, beforeEffect: null, publishBody: null, guideline: null, acked: false,
          retryPublishAfterEdit: !!ctx.draftId, createWasSent: !!ctx.draftId, status: 'prepared', writePhase: null, rejectionCode: null });
      } else if (JSON.stringify(comparisonInput) !== JSON.stringify(input)) {
        await modal({ title: '先核对上次提交', content: '上次结果尚未确认。先核对原提交，本次新输入会保留，不会代替原内容重试。', confirmText: '核对原稿', cancelText: '取消' }, task);
        check(); task.recoveredPrevious = true;
      }
    } else {
      const started = intent.begin(wxApi, { scope, memberId: owner, payload: input });
      ctx = intent.read(wxApi, scope, owner);
      if (!ctx || ctx.key !== started.key || String(ctx.memberId) !== owner) throw { code: 507, msg: '提交记录无法保存，未继续提交' };
      freshPartial = true;
      const captures = {};
      for (const path of String(input.pics || '').split(';').filter(Boolean)) {
        if (uploads[path]) captures[path] = clone(uploads[path]);
      }
      save({ input, captures, registrations: {}, mediaSelections: selectedMediaIds(input), wireKey: env.form.request_id || ctx.key, createdAsNew: !input.id, status: 'prepared' });
      freshPartial = false;
    }
    const form = ctx.input;
    if (form.file_url || form.longitude || form.latitude || form.address) throw { code: 409, msg: '请先移除尚未接通的附件或地点，内容已保留' };
    if (ctx.patchBody) {
      const latest = await read(form.id);
      if (latest.post.version === ctx.patchBody.expectedVersion + 1 && same(latest, ctx.patchBody)) {
        if (!ctx.retryPublishAfterEdit) return finish(latest);
        save({ createBody: ctx.patchBody, patchBody: null, beforeEffect: null, publishBody: null, status: 'prepared', writePhase: null });
        wasUnknown = false;
      } else {
        if (latest.post.version !== ctx.patchBody.expectedVersion || JSON.stringify(effect(latest)) !== JSON.stringify(ctx.beforeEffect))
          throw { code: 409, msg: '帖文已变化，未重发原修改，请先核对' };
        await modal({ title: '原修改尚未确认', content: '是否使用原版本和相同内容重试？当前新输入会保留。', confirmText: '原样重试', cancelText: '取消' }, task); check();
      }
    }
    if (!ctx.createBody && !ctx.patchBody) {
      if (!ctx.guideline) {
        const guideline = await call('/guidelines/active', 'GET', {}, task);
        let document; try { document = JSON.parse(guideline.content_json); } catch (_) { document = guideline.contentJson || guideline.content_json; }
        if (!guideline.id || !document || !Array.isArray(document.rules) || !document.rules.length
          || document.rules.some(rule => !rule || typeof rule.title !== 'string' || !rule.title.trim())) throw { code: 503, msg: '社区规范不完整，暂不能提交' };
        await modal({ title: guideline.title || '社区规范', content: (guideline.summary || '') + '\n' + document.rules.map((r, i) => (i + 1) + '. ' + r.title).join('\n'), confirmText: '同意发布' }, task);
        save({ guideline: { id: guideline.id }, ackBody: { guidelineVersionId: guideline.id, requestId: requestId(), scene: 'PUBLISH' } });
      }
      if (!ctx.acked) { await write('ack', '/guidelines/ack', 'POST', ctx.ackBody); save({ acked: true, status: 'prepared', writePhase: null }); attemptWrote = false; if (unknownPhase === 'ack') wasUnknown = false; }
      const previous = form.id ? await read(form.id) : null;
      if (previous && !['DRAFT', 'CHECKING', 'PUBLISHED', 'LIMITED', 'HIDDEN'].includes(previous.post.lifecycle)) throw { code: 409, msg: '当前帖文不可编辑' };
      const original = previous && previous.post, mediaIds = [];
      const paths = form.pics === undefined && previous ? null : String(form.pics || '').split(';').filter(Boolean);
      if (paths === null) previous.media.forEach(m => mediaIds.push(m.id));
      else for (const path of paths) {
        const selectedId = ctx.mediaSelections && ctx.mediaSelections[path];
        const existing = previous && previous.media.find(m => selectedId !== undefined ? String(m.id) === String(selectedId)
          : (field(m, 'derivedObjectKey', 'derived_object_key') || field(m, 'objectKey', 'object_key')) === path);
        if (existing) { mediaIds.push(existing.id); continue; }
        if (selectedId !== undefined) throw { code: 409, msg: '原图片已变化，未重新绑定，请刷新后核对' };
        if (!ctx.registrations[path]) {
          const receipt = ctx.captures[path];
          if (!receipt) throw { code: 409, msg: '图片上传状态无法确认，请先到我的推文查看发布结果，再重新选择图片' };
          const media = await write('media', '/media/register', 'POST', { uploadRequestId: receipt.uploadRequestId, mediaType: 'IMAGE', objectKey: receipt.objectKey || receipt.url,
            uploadReceipt: receipt.uploadReceipt, mimeType: receipt.mimeType, byteSize: receipt.byteSize }, task);
          if (!media || !media.id) throw { code: 502, msg: '媒体登记未确认' };
          save({ registrations: Object.assign({}, ctx.registrations, { [path]: media.id }), status: 'prepared', writePhase: null });
          attemptWrote = false;
          if (unknownPhase === 'media') wasUnknown = false;
        }
        mediaIds.push(ctx.registrations[path]);
      }
      const references = [];
      if (form.data_id) {
        const type = { 1: 'ACTIVITY', 2: 'TOPIC', 3: 'ROUTE' }[Number(form.data_type)];
        if (!type) throw { code: 409, msg: '关联来源尚未支持' };
        references.push({ referenceType: type, referenceId: Number(form.data_id) });
      }
      if (previous && previous.references.length) {
        const refs = previous.references.map(r => ({ referenceType: field(r, 'referenceType', 'reference_type'), referenceId: field(r, 'referenceId', 'reference_id') }));
        const first = refs[0];
        if (references.length && references[0].referenceType === first.referenceType && String(references[0].referenceId) === String(first.referenceId)) references.splice(0, references.length, ...refs);
        else references.push(...refs.slice(1));
      }
      const body = { clientRequestId: ctx.wireKey, body: String(form.contents || '').trim(), postType: original ? original.postType : references[0] && references[0].referenceType === 'ACTIVITY' ? 'ACTIVITY_RECAP' : references[0] && references[0].referenceType === 'ROUTE' ? 'ROUTE_DISCOVERY' : 'MOMENT',
        audience: original ? original.audience : 'PUBLIC', commentPolicy: original ? original.commentPolicy : 'EVERYONE', guidelineVersionId: ctx.guideline.id,
        mediaIds, references, locationPrecision: original ? original.locationPrecision : 'NONE' };
      if (original) {
        if (['APPROXIMATE', 'EXACT'].includes(original.locationPrecision) && !original.approximateGeohash) throw { code: 409, msg: '原帖地点信息不完整，请刷新后重试' };
        body.topicCodes = previous.topicCodes || []; body.mentionedMemberIds = previous.mentions || [];
        try { body.safetyLabels = JSON.parse(original.safetyLabelsJson || '[]'); } catch (_) { throw { code: 409, msg: '原帖安全标签无法读取' }; }
        ['title', 'communityId', 'replyApprovalEnabled', 'slowModeSeconds', 'cityCode', 'poiName', 'approximateGeohash', 'disclosureType'].forEach(k => { if (original[k] !== undefined) body[k] = original[k]; });
        body.expectedVersion = original.version;
        save({ patchBody: body, beforeEffect: effect(previous), originalLifecycle: original.lifecycle });
      } else save({ createBody: body });
    }
    if (ctx.patchBody) {
      await write('patch', '/posts/' + form.id, 'PATCH', ctx.patchBody);
      const latest = await read(form.id);
      if (latest.post.version !== ctx.patchBody.expectedVersion + 1 || !same(latest, ctx.patchBody)) throw { code: 502, msg: '修改结果未确认，请回读后重试' };
      if (!ctx.retryPublishAfterEdit) return finish(latest);
      save({ createBody: ctx.patchBody, patchBody: null, beforeEffect: null, publishBody: null, status: 'prepared', writePhase: null });
      wasUnknown = false;
    }
    if (!ctx.draftId) {
      if (ctx.createWasSent) {
        const page = await call('/drafts', 'GET', { limit: 100 }, task);
        if (!page || !Array.isArray(page.items) || page.hasMore !== false) throw { code: 409, msg: '原草稿尚未唯一确认，请先到我的推文核对' };
        const matches = page.items.filter(b => b && b.post && b.post.clientRequestId === ctx.wireKey);
        if (matches.length > 1) throw { code: 409, msg: '原提交结果不唯一，请先核对' };
        if (matches.length) { owned(matches[0]); save({ draftId: matches[0].post.id }); }
        else { await modal({ title: '原提交尚未确认', content: '暂未查到原草稿，不代表写入未发生。是否使用原请求和相同内容重试？', confirmText: '原样重试', cancelText: '取消' }, task); check(); }
      }
      if (!ctx.draftId) {
        save({ createWasSent: true }); const created = await write('create', '/posts', 'POST', ctx.createBody);
        if (created && created.intentDiscarded === true) {
          intent.discard(wxApi, scope, owner, ctx.key);
          if (wxApi.getStorageSync(intent.storageKey(scope, owner))) throw { code: 507, msg: '原提交记录未能清理，请先核对' };
          throw { code: 409, msg: '原提交已删除，请先核对后重新创建', intentDiscarded: true };
        }
        owned(created); if (created.post.clientRequestId !== ctx.wireKey) throw { code: 502, msg: '原提交标识未能确认' };
        save({ draftId: created.post.id });
      }
    }
    let latest = await read(ctx.draftId);
    if (!same(latest, ctx.createBody)) throw { code: 409, msg: '原草稿内容已变化，请先核对' };
    if (unknownPhase === 'create') wasUnknown = false;
    if (['CHECKING', 'PUBLISHED'].includes(latest.post.lifecycle)) return finish(latest);
    if (latest.post.lifecycle !== 'DRAFT') throw { code: 409, msg: '原草稿状态已变化，请先核对' };
    if (!ctx.publishBody) save({ publishBody: { expectedVersion: latest.post.version, requestId: requestId() } });
    await write('publish', '/posts/' + ctx.draftId + '/publish', 'POST', ctx.publishBody);
    latest = await read(ctx.draftId);
    if (!['CHECKING', 'PUBLISHED'].includes(latest.post.lifecycle) || !same(latest, ctx.createBody)) throw { code: 502, msg: '发布结果未确认，请回读后再操作' };
    return finish(latest);
  } catch (error) {
    if (ctx && ctx.key && String(env.app.getUserID() || '') === owner) {
      if (freshPartial) intent.removeUnsentPartial(wxApi, scope, owner, ctx.key);
      else if (failedWritePhase && !wasUnknown && !error.transportFailure
        && (Number(error.code) === 400 || failedWritePhase === 'ack' && Number(error.code) === 409))
        intent.saveStage(wxApi, scope, owner, ctx.key, { status: 'rejected', rejectedPhase: failedWritePhase, rejectionCode: Number(error.code) });
      else if (attemptWrote || wasUnknown) intent.saveStage(wxApi, scope, owner, ctx.key, { status: 'unknown', rejectionCode: null });
    }
    throw error;
  }
};
