const test = require('node:test');
const assert = require('node:assert/strict');
const { createServerDraftSaver, readbackIssue } = require('../../pages/publish/utils/publish/server-draft.js');

const payload = { name: '寒冬', publishMode: 'pro', productType: 1, chapters: [{ nodes: [{ name: '甲' }, { name: '乙' }] }] };
const detail = (extra) => Object.assign({ isDraft: true, topic: { name: '寒冬' },
  chapters: [{ cmsTopicNodeList: [{ id: 11, name: '乙' }, { id: 12, name: '甲' }] }] }, extra);

test('回读校验：必须仍是草稿、站点都有编号且与本机一致', () => {
  assert.equal(readbackIssue(payload, detail(), true), '');
  assert.match(readbackIssue(payload, detail({ isDraft: false }), true), /不是草稿/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ id: 11, name: '甲' }] }] }), true), /站点/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ name: '甲' }, { id: 2, name: '乙' }] }] }), true), /编号/);
  assert.match(readbackIssue(payload, detail({ topic: { name: '别的' } }), true), /主题名/);
});

test('保存草稿：带 saveDraft=1、回读确认后才成功；连点只发一次；失败可重试；存不进去就不发布', () => {
  const calls = [];
  const saver = createServerDraftSaver({
    save: (p, cb) => calls.push(['save', p, cb]),
    readback: (id, cb) => calls.push(['readback', id, cb]),
    publish: (id, cb) => calls.push(['publish', id, cb]),
  });
  const events = [];
  const h = { onSaved: id => events.push(['saved', id]), onSuccess: r => events.push(['ok', r]), onFail: r => events.push(['fail', r]) };
  assert.equal(saver.save(payload, h), true);
  assert.equal(saver.save(payload, h), false, '在途连点被拦');
  assert.equal(saver.saveAndPublish(payload, h), false);
  assert.equal(calls.length, 1); assert.equal(calls[0][1].saveDraft, 1);
  calls[0][2]({ ok: true, data: { topicId: 501 } });
  assert.deepEqual(events, [['saved', 501]], 'HTTP 成功还不算成功，要等回读');
  assert.equal(calls[1][0], 'readback'); assert.equal(calls[1][1], 501);
  calls[1][2]({ ok: true, data: detail() });
  assert.equal(events[1][0], 'ok'); assert.equal(events[1][1].topicId, 501);
  assert.equal(calls.length, 2, '保存草稿不发布');

  // 业务失败 → 不回读、可重试
  assert.equal(saver.save(payload, h), true);
  calls[2][2]({ ok: false, msg: '内容不合规' });
  assert.equal(events.at(-1)[1].stage, 'save'); assert.equal(calls.length, 3);
  // 回读对不上 → 失败(本机稿不该清)
  assert.equal(saver.save(payload, h), true);
  calls[3][2]({ ok: true, data: 501 });
  calls[4][2]({ ok: true, data: detail({ isDraft: false }) });
  assert.equal(events.at(-1)[0], 'fail'); assert.equal(events.at(-1)[1].stage, 'readback'); assert.equal(events.at(-1)[1].topicId, 501);

  // 发布:存失败(网络)就不调 publish-draft
  assert.equal(saver.saveAndPublish(payload, h), true);
  calls[5][2]({ ok: false, networkError: true });
  assert.equal(calls.filter(c => c[0] === 'publish').length, 0, '存不进去不发布旧版本');
  // 发布:存 → 回读 → publish-draft;publish 失败带回读结果,可重试
  assert.equal(saver.saveAndPublish(payload, h), true);
  calls[6][2]({ ok: true, data: { topicId: 501 } }); calls[7][2]({ ok: true, data: detail() });
  assert.equal(calls[8][0], 'publish'); assert.equal(calls[8][1], 501);
  calls[8][2]({ ok: false, msg: '在架额度已满' });
  assert.equal(events.at(-1)[1].stage, 'publish'); assert.ok(events.at(-1)[1].detail);
  assert.equal(saver.saveAndPublish(payload, h), true);
  calls[9][2]({ ok: true, data: { topicId: 501 } }); calls[10][2]({ ok: true, data: detail() });
  calls[11][2]({ ok: true, data: { published: true } });
  assert.equal(events.at(-1)[0], 'ok'); assert.deepEqual(events.at(-1)[1].published, { published: true });
  assert.equal(saver.saveAndPublish(payload, h), false, '发布成功后不再重复发布');
});

function loadPage() {
  const sent = [], removed = [], stored = {};
  global.wx = {
    getStorageSync: k => stored[k], setStorageSync: (k, v) => { stored[k] = v; }, removeStorageSync: k => { removed.push(k); delete stored[k]; },
    showToast() {}, showLoading() {}, hideLoading() {}, getSystemInfoSync() { return {}; }, getWindowInfo() { return {}; },
  };
  global.getCurrentPages = () => [];
  global.getApp = () => ({ globalData: {}, getUserID: () => 7, getRequestErrorMessage: (r, d) => d, sendRequest: opts => sent.push(opts) });
  let page; global.Page = value => { page = value; };
  delete require.cache[require.resolve('../../pages/publish/fabu/index')];
  require('../../pages/publish/fabu/index');
  page.data = { operationScope: '', editingTopicId: '', editLoaded: false, editIsDraft: false, submitting: false, draftSaving: false,
    draftUuid: 'u-1', draftMemberId: 7, formData: { name: '寒冬', chapters: [] }, pendingMaterials: [] };
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb(); };
  page._buildSubmitPayload = () => JSON.parse(JSON.stringify(payload));
  page.applied = [];
  page.applyEditingTopic = function (d) { this.applied.push(d); this.setData({ editIsDraft: d.isDraft === true, editLoaded: true }); this._finishEditingDraftBootstrap(d); return true; };
  return { page, sent, removed, stored };
}

test('编辑页：首次保存草稿走 create，回读后才清本机恢复稿；再次保存 update 同一主题；发布先存后发', () => {
  const { page, sent, removed } = loadPage();
  page.saveServerDraft();
  page.saveServerDraft();
  assert.equal(sent.length, 1, '连点只发一次');
  assert.equal(sent[0].url, '/api/topic/v2/create');
  const body = JSON.parse(sent[0].data); assert.equal(body.saveDraft, 1); assert.equal(body.id, undefined);
  sent[0].success({ code: 200, data: { topicId: 501, reviewState: 'DRAFT' } });
  assert.equal(page.data.editingTopicId, '501');
  assert.equal(removed.length, 0, '只拿到 200 不清本机稿');
  assert.equal(sent[1].url, '/api/topic/edit-detail'); assert.equal(sent[1].data.id, 501);
  sent[1].success({ code: 200, data: detail() });
  assert.equal(page.applied.length, 1, '回读版本换进编辑器');
  assert.ok(removed.some(k => k.includes('u-1')) && removed.some(k => k.includes('501')), '回读确认后清掉本机恢复稿');
  assert.equal(page.data.resultSheet.title, '草稿已保存到云端'); assert.equal(page.data.draftSaving, false);

  // 再次保存 → update 同一主题;失败保留内容并可重试
  removed.length = 0;
  page.saveServerDraft();
  assert.equal(sent[2].url, '/api/topic/v2/update'); assert.equal(JSON.parse(sent[2].data).id, '501'); assert.equal(JSON.parse(sent[2].data).saveDraft, 1);
  sent[2].fail({});
  assert.equal(page.data.resultSheet.kind, 'fail'); assert.equal(page.data.resultSheet.meta, '草稿仍在本机');
  assert.equal(removed.length, 0);
  page.saveServerDraft();
  assert.equal(sent[3].url, '/api/topic/v2/update', '重试仍更新同一份，不重复建题');
  sent[3].success({ code: 200, data: { topicId: 501 } }); sent[4].success({ code: 200, data: detail() });

  // 发布:先 update(saveDraft=1) → 回读 → publish-draft;存失败不发布
  page._doSubmit();
  assert.equal(sent[5].url, '/api/topic/v2/update'); assert.equal(JSON.parse(sent[5].data).saveDraft, 1);
  sent[5].success({ code: 500, msg: '保存失败' });
  assert.ok(!sent.some(r => r.url === '/api/topic/publish-draft'), '存不进去不发旧版本');
  assert.equal(page.data.resultSheet.title, '这次还没有发布出去');
  page._doSubmit();
  sent[6].success({ code: 200, data: { topicId: 501 } }); sent[7].success({ code: 200, data: detail() });
  assert.equal(sent[8].url, '/api/topic/publish-draft'); assert.equal(sent[8].data.id, 501);
  page._doSubmit();
  assert.equal(sent.length, 9, '发布在途不重复');
  sent[8].success({ code: 200, data: { published: true, reviewState: 'NOT_REQUIRED' } });
  assert.equal(page.data.resultSheet.title, '城市路线已发布'); assert.equal(page.data.editIsDraft, false);
});

test('已发布主题不出现草稿保存：editIsDraft=false 时 saveServerDraft 不发请求，发布仍走原 update', () => {
  const { page, sent } = loadPage();
  Object.assign(page.data, { editingTopicId: '990013', editLoaded: true, editIsDraft: false });
  page.saveServerDraft();
  assert.equal(sent.length, 0);
  page._doSubmit();
  assert.equal(sent[0].url, '/api/topic/v2/update'); assert.equal(JSON.parse(sent[0].data).saveDraft, undefined);
});

test('删改站点后：存草稿不被「章节与到达关系」闸拦住（那道闸要求先存草稿），发布仍过闸', () => {
  const { page, sent } = loadPage();
  const options = [];
  page._buildSubmitPayload = (opts) => { options.push(opts || null); return JSON.parse(JSON.stringify(payload)); };
  page.saveServerDraft();
  assert.deepEqual(options[0], { draft: true });
  assert.equal(sent[0].url, '/api/topic/v2/create');
  sent[0].success({ code: 500, msg: '后端校验失败' });
  page._doSubmit();
  assert.equal(options[1], null, '发布不带 draft，结构闸照旧');
});
