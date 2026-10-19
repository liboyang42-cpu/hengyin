const test = require('node:test');
const assert = require('node:assert/strict');
const { createServerDraftSaver, readbackIssue } = require('../../pages/publish/utils/publish/server-draft.js');

const payload = { name: '寒冬', publishMode: 'pro', productType: 1, chapters: [{ nodes: [{ name: '甲' }, { name: '乙' }] }] };
const detail = (extra) => Object.assign({ isDraft: true, topic: { id: 501, name: '寒冬' },
  chapters: [{ id: 101, cmsTopicNodeList: [{ id: 11, name: '乙' }, { id: 12, name: '甲' }] }] }, extra);

test('回读校验：必须仍是草稿、站点都有编号且与本机一致', () => {
  assert.equal(readbackIssue(payload, detail(), true), '');
  assert.match(readbackIssue(payload, detail({ isDraft: false }), true), /不是草稿/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ id: 11, name: '甲' }] }] }), true), /站点/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ name: '甲' }, { id: 2, name: '乙' }] }] }), true), /编号/);
  assert.match(readbackIssue(payload, detail({ topic: { id: 501, name: '别的' } }), true), /主题名/);
});

test('各章站点串位或章节改名不能当作完整草稿回读，原稿不采用或发布', () => {
  const sent = { name: '寒冬', chapters: [
    { name: '第一幕', nodes: [{ name: '甲' }] }, { name: '第二幕', nodes: [{ name: '乙' }] }
  ] };
  const wrong = [
    [{ id: 101, name: '第一幕', cmsTopicNodeList: [{ id: 11, name: '乙' }] },
      { id: 102, name: '第二幕', cmsTopicNodeList: [{ id: 12, name: '甲' }] }],
    [{ id: 101, name: '被改名', cmsTopicNodeList: [{ id: 11, name: '甲' }] },
      { id: 102, name: '第二幕', cmsTopicNodeList: [{ id: 12, name: '乙' }] }]
  ];
  for (const chapters of wrong) {
    let failure;
    const saver = createServerDraftSaver({
      save: (p, cb) => cb({ ok: true, data: 501 }),
      readback: (id, cb) => cb({ ok: true, data: detail({ chapters }) }),
      publish: () => assert.fail('不匹配版本不得发布'),
    });
    saver.saveAndPublish(sent, {
      onFail: res => { failure = res; },
      onSuccess: () => assert.fail('不得采用不匹配版本'),
    });
    assert.equal(failure.stage, 'readback');
    assert.match(failure.msg, /章节|站点/);
    assert.equal(saver.isBusy(), false);
    const { page, sent: requests, removed } = loadPage();
    page._runServerDraft(sent, true);
    const original = JSON.stringify(page.data.formData);
    requests[0].success({ code: 200, data: 501 });
    requests[1].success({ code: 200, data: detail({ chapters }) });
    assert.equal(JSON.stringify(page.data.formData), original);
    assert.equal(page.applied.length, 0);
    assert.equal(removed.length, 0);
    assert.equal(requests.length, 2);
  }
  const reordered = [
    { id: 102, name: '第二幕', cmsTopicNodeList: [{ id: 12, name: '乙' }] },
    { id: 101, name: ' 第一幕 ', cmsTopicNodeList: [{ id: 11, name: '甲' }] }
  ];
  assert.equal(readbackIssue(sent, detail({ chapters: reordered }), true), '',
    '合法排序或名称首尾空格规范化不误拦');
});

test('回读保留空章节并要求章节和站点编号各自唯一，Long字符串不舍入', () => {
  const sent = { name: '寒冬', chapters: [{ nodes: [{ name: '甲' }] }, { nodes: [] }] };
  const got = [{ id: '9223372036854775807', cmsTopicNodeList: [{ id: '9223372036854775807', name: '甲' }] }, { id: 2, cmsTopicNodeList: [] }];
  assert.equal(readbackIssue(sent, detail({ chapters: got }), true), '');
  assert.match(readbackIssue(sent, detail({ chapters: got.slice(0, 1) }), true), /章节/);
  for (const badId of [undefined, 0, Number.MAX_SAFE_INTEGER + 1, '9223372036854775808']) {
    const invalid = JSON.parse(JSON.stringify(got)); invalid[1].id = badId;
    assert.match(readbackIssue(sent, detail({ chapters: invalid }), true), /编号/);
  }
  assert.match(readbackIssue(sent, detail({ chapters: [got[0], Object.assign({}, got[1], { id: got[0].id })] }), true), /重复/);
  const nodes = [{ id: 11, name: '甲' }, { id: '11', name: '乙' }];
  assert.match(readbackIssue(payload, detail({ chapters: [{ id: 101, cmsTopicNodeList: nodes }] }), true), /重复/);
  assert.match(readbackIssue({ name: '寒冬', chapters: [{ nodes: [{ name: '甲' }] }, { nodes: [{ name: '乙' }] }] }, detail({ chapters: [{ id: 101, cmsTopicNodeList: [nodes[0]] }, { id: 102, cmsTopicNodeList: [nodes[1]] }] }), true), /重复/);
});

test('畸形章节回读不会采用或发布，保存器解锁后可重试原稿', () => {
  const calls = [], failed = [], accepted = [];
  let bad = true;
  const saver = createServerDraftSaver({
    save(value, done) { calls.push('save'); done({ ok: true, data: 501 }); },
    readback(value, done) { calls.push('readback'); done({ ok: true, data: bad ? detail({ chapters: [{ cmsTopicNodeList: [{ id: 11, name: '乙' }, { id: 12, name: '甲' }] }] }) : detail() }); },
    publish(value, done) { calls.push('publish'); done({ ok: true, data: {} }); },
  });
  const handlers = { onFail: r => failed.push(r), onSuccess: r => accepted.push(r) };
  assert.equal(saver.saveAndPublish(payload, handlers), true);
  assert.equal(saver.isBusy(), false);
  assert.deepEqual(calls, ['save', 'readback']);
  assert.equal(failed[0].stage, 'readback'); assert.equal(failed[0].topicId, 501);
  assert.equal(accepted.length, 0);
  bad = false;
  assert.equal(saver.saveAndPublish(payload, handlers), true);
  assert.deepEqual(calls, ['save', 'readback', 'save', 'readback', 'publish']);
  assert.equal(accepted.length, 1);
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

test('编辑页的畸形回读保留原表单和恢复稿，不发布；重试仍更新原主题', () => {
  const { page, sent, removed } = loadPage();
  Object.assign(page.data, { editingTopicId: '501', editIsDraft: true, editLoaded: true });
  const originalForm = JSON.stringify(page.data.formData);
  page._doSubmit();
  sent[0].success({ code: 200, data: { topicId: 501 } });
  sent[1].success({ code: 200, data: detail({ chapters: [{ cmsTopicNodeList: [{ id: 11, name: '乙' }, { id: 12, name: '甲' }] }] }) });
  assert.equal(JSON.stringify(page.data.formData), originalForm);
  assert.equal(page.applied.length, 0);
  assert.equal(removed.length, 0);
  assert.equal(page.data.submitting, false);
  assert.equal(page.data.resultSheet.kind, 'fail');
  assert.equal(page.data.editingTopicId, '501');
  assert.ok(!sent.some(r => r.url === '/api/topic/publish-draft'));
  page.saveServerDraft();
  assert.equal(sent[2].url, '/api/topic/v2/update');
  assert.equal(JSON.parse(sent[2].data).id, '501');
  sent[2].success({ code: 200, data: { topicId: 501 } });
  sent[3].success({ code: 200, data: detail() });
  assert.equal(page.applied.length, 1);
});

function loadPage() {
  const sent = [], removed = [], stored = {};
  let memberId = 7;
  global.wx = {
    getStorageSync: k => stored[k], setStorageSync: (k, v) => { stored[k] = v; }, removeStorageSync: k => { removed.push(k); delete stored[k]; },
    showToast() {}, showLoading() {}, hideLoading() {}, getSystemInfoSync() { return {}; }, getWindowInfo() { return {}; },
  };
  global.getCurrentPages = () => [];
  global.getApp = () => ({ globalData: {}, getUserID: () => memberId, getRequestErrorMessage: (r, d) => d, sendRequest: opts => sent.push(opts) });
  let page; global.Page = value => { page = value; };
  delete require.cache[require.resolve('../../pages/publish/fabu/index')];
  require('../../pages/publish/fabu/index');
  page.data = { operationScope: '', editingTopicId: '', editLoaded: false, editIsDraft: false, submitting: false, draftSaving: false,
    draftUuid: 'u-1', draftMemberId: 7, formData: { name: '寒冬', chapters: [] }, pendingMaterials: [] };
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb(); };
  page._buildSubmitPayload = () => JSON.parse(JSON.stringify(payload));
  page.applied = [];
  page.applyEditingTopic = function (d) { this.applied.push(d); this.setData({ editIsDraft: d.isDraft === true, editLoaded: true }); this._finishEditingDraftBootstrap(d); return true; };
  return { page, sent, removed, stored, setMemberId: value => { memberId = value; } };
}

test('发布网络异常保留原稿，不采用旧回读或把未知说成未发布', () => {
  const { page, sent, removed } = loadPage();
  page._runServerDraft(payload, true);
  sent[0].success({ code: 200, data: { topicId: 501 } });
  sent[1].success({ code: 200, data: detail() });
  sent[2].fail({});
  assert.equal(page.applied.length, 0);
  assert.equal(removed.filter(k => k.includes('u-1') || k.includes('501')).length, 0);
  assert.equal(page.data.resultSheet.title, '发布结果待核对');
  assert.equal(page.data.submitting, false);
});

test('草稿回读必须属于原主题，章节畸形按失败结束而不是卡住或发布', () => {
  for (const back of [detail({ topic: { id: 502, name: '寒冬' } }), detail({ chapters: {} }), detail({ chapters: [null] })]) {
    const calls = [], failures = [];
    const saver = createServerDraftSaver({
      save: (p, cb) => cb({ ok: true, data: { topicId: 501 } }),
      readback: (id, cb) => cb({ ok: true, data: back }),
      publish: (id, cb) => calls.push(id),
    });
    assert.doesNotThrow(() => saver.saveAndPublish(payload, { onFail: r => failures.push(r) }));
    assert.equal(calls.length, 0);
    assert.equal(failures.length, 1);
    assert.equal(failures[0].stage, 'readback');
    assert.equal(saver.isBusy(), false);
  }
});

test('旧阶段重复回调不能重复读回/发布，不能解除下一次保存锁', () => {
  const calls = [], successes = [];
  const saver = createServerDraftSaver({
    save: (p, cb) => calls.push(['save', cb]),
    readback: (id, cb) => calls.push(['readback', cb]),
    publish: (id, cb) => calls.push(['publish', cb]),
  });
  saver.saveAndPublish(payload, { onSuccess: r => successes.push(r) });
  const saved = calls[0][1];
  saved({ ok: true, data: 501 });
  saved({ ok: true, data: 501 });
  assert.equal(calls.filter(c => c[0] === 'readback').length, 1);
  const read = calls[1][1];
  read({ ok: true, data: detail() });
  read({ ok: true, data: detail() });
  assert.equal(calls.filter(c => c[0] === 'publish').length, 1);
  calls[2][1]({ ok: false });
  saver.save(payload);
  saved({ ok: false });
  assert.equal(saver.isBusy(), true);
  assert.equal(saver.save(payload), false);
  assert.equal(successes.length, 0);
});

test('保存冻结JSON内容，调用期间修改原对象不改变提交或回读校验', () => {
  const p = JSON.parse(JSON.stringify(payload));
  let saved, read, submitted, result;
  const saver = createServerDraftSaver({
    save: (data, cb) => { submitted = data; saved = cb; },
    readback: (id, cb) => { read = cb; },
    publish: () => assert.fail('保存不发布'),
  });
  saver.save(p, { onSuccess: r => { result = r; } });
  p.name = '后续修改';
  p.chapters[0].nodes[0].name = '后续站点';
  assert.equal(submitted.name, '寒冬');
  assert.notEqual(submitted.chapters[0].nodes[0].name, '后续站点');
  saved({ ok: true, data: 501 });
  read({ ok: true, data: detail() });
  assert.equal(result.topicId, 501);
});

test('保存/回读/发布同步抛错会释放本次锁且保留阶段，不卡住重试', () => {
  for (const stage of ['save', 'readback', 'publish']) {
    let failure;
    const saver = createServerDraftSaver({
      save: (p, cb) => { if (stage === 'save') throw new Error('unavailable'); cb({ ok: true, data: 501 }); },
      readback: (id, cb) => { if (stage === 'readback') throw new Error('unavailable'); cb({ ok: true, data: detail() }); },
      publish: () => { throw new Error('unavailable'); },
    });
    assert.doesNotThrow(() => saver.saveAndPublish(payload, { onFail: r => { failure = r; } }));
    assert.equal(saver.isBusy(), false);
    assert.equal(failure.stage, stage);
  }
});

test('保存期间的新修改不被旧回读替换或清掉，也不发布旧版本', () => {
  for (const publish of [false, true]) {
    const { page, sent, removed, stored } = loadPage();
    page._runServerDraft(payload, publish);
    page.data.formData.name = '后续修改';
    sent[0].success({ code: 200, data: { topicId: 501 } });
    sent[1].success({ code: 200, data: detail() });
    assert.equal(page.applied.length, 0);
    assert.equal(page.data.formData.name, '后续修改');
    assert.equal(removed.length, 0);
    assert.equal(sent.filter(r => r.url === '/api/topic/publish-draft').length, 0);
    assert.equal(page.data.draftSaving, false);
    assert.equal(page.data.submitting, false);
    assert.ok(Object.keys(stored).some(k => k.includes('501')));
  }
});

test('旧账号保存回包不能切到另一账号继续读回或采用草稿', () => {
  const { page, sent, removed, setMemberId } = loadPage();
  page.saveServerDraft();
  setMemberId(8);
  sent[0].success({ code: 200, data: { topicId: 501 } });
  assert.equal(sent.length, 1);
  assert.equal(page.data.editingTopicId, '');
  assert.equal(page.applied.length, 0);
  assert.equal(removed.length, 0);
});

test('已发出的发布结果保持真实，期间新修改保留本机；失败也不采用旧回读', () => {
  for (const ok of [true, false]) {
    const { page, sent, removed } = loadPage();
    page._runServerDraft(payload, true);
    sent[0].success({ code: 200, data: { topicId: 501 } });
    sent[1].success({ code: 200, data: detail() });
    assert.equal(sent[2].url, '/api/topic/publish-draft');
    page.data.formData.name = '发布期间的新修改';
    sent[2].success({ code: ok ? 200 : 500, data: ok ? { published: true } : undefined, msg: '发布失败' });
    assert.equal(page.data.formData.name, '发布期间的新修改');
    assert.equal(removed.filter(k => k.includes('u-1') || k.includes('501')).length, 0);
    assert.equal(page.applied.length, 0);
    assert.equal(page.data.resultSheet.kind, ok ? 'success' : 'fail');
    assert.match(page.data.resultSheet.sub, /后续修改|随后修改/);
  }
});

test('已有主题更新返回另一ID不得改绑；大Long字符串保存/回读/发布不舍入', () => {
  const { page, sent } = loadPage();
  page.data.editingTopicId = '501'; page.data.editLoaded = true; page.data.editIsDraft = true;
  page.saveServerDraft();
  sent[0].success({ code: 200, data: { topicId: 502 } });
  assert.equal(page.data.editingTopicId, '501');
  assert.equal(sent.length, 1);
  const id = '9007199254740993';
  let savedId, publishedId, result;
  const saver = createServerDraftSaver({
    save: (p, cb) => cb({ ok: true, data: { topicId: id } }),
    readback: (value, cb) => { savedId = value; cb({ ok: true, data: detail({ topic: { id, name: '寒冬' } }) }); },
    publish: (value, cb) => { publishedId = value; cb({ ok: true, data: { published: true } }); },
  });
  saver.saveAndPublish(payload, { onSuccess: r => { result = r; } });
  assert.equal(savedId, id); assert.equal(publishedId, id); assert.equal(result.topicId, id);
});

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
