const test = require('node:test');
const assert = require('node:assert/strict');
const { createServerDraftSaver, readbackIssue } = require('../../pages/publish/utils/publish/server-draft.js');

const payload = { name: '寒冬', publishMode: 'pro', productType: 1, chapters: [{ nodes: [{ name: '甲' }, { name: '乙' }] }] };
const detail = (extra) => Object.assign({ isDraft: true, topic: { name: '寒冬' },
  chapters: [{ cmsTopicNodeList: [{ id: 11, name: '甲' }, { id: 12, name: '乙' }] }] }, extra);

test('回读校验：必须仍是草稿、站点都有编号且与本机一致', () => {
  assert.equal(readbackIssue(payload, detail(), true), '');
  assert.match(readbackIssue(payload, detail({ isDraft: false }), true), /不是草稿/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ id: 11, name: '甲' }] }] }), true), /站点/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ id: 11, name: '乙' }, { id: 12, name: '甲' }] }] }), true), /顺序/);
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
  // 草稿删除只删本人确有的信封(发布分包 pro-editor-draft):先让新草稿 u-1 真的落一份本机稿
  assert.equal(page._persistDraftEnvelope(), true);
  page.saveServerDraft();
  page.saveServerDraft();
  assert.equal(sent.length, 1, '连点只发一次');
  assert.equal(sent[0].url, '/api/topic/v2/create');
  const body = JSON.parse(sent[0].data); assert.equal(body.saveDraft, 1); assert.equal(body.id, undefined);
  sent[0].success({ code: 200, data: { topicId: 501, reviewState: 'DRAFT' } });
  assert.equal(page.data.editingTopicId, '501');
  assert.equal(removed.filter(k => k.startsWith('pro_editor')).length, 0, '只拿到 200 不清本机稿');
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
  assert.equal(removed.filter(k => k.startsWith('pro_editor')).length, 0);
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


// ── 回读语义核对:章节归属/顺序、玩法与资源对应、章节规则、配给,经服务端合法换号后仍须一致 ──
const richSent = {
  name: '寒冬',
  journeyRules: JSON.stringify({ schemaVersion: 1, stateEnabled: true, hp: { enabled: true, init: 8, max: 12 } }),
  chapters: [
    { name: '一', nodes: [{ id: 101, name: '甲', templateId: 7 }, { name: '乙', templateId: 8, clientNodeKey: 'kb' }] },
    { name: '二', nodes: [{ id: 201, name: '丙', templateId: 9 }, { id: 202, name: '丁', templateId: 0 }] },
  ],
  themeRuntimeConfig: JSON.stringify({
    items: [{ id: 'i1', kind: 'ITEM', name: '口粮' }],
    chapters: [{ index: 1, completion: { type: 'ANY_N', quantity: 1, overflowSlot: 'shared' }, extraNodes: [{ nodeId: 202, slot: 'shared' }] },
      { index: 0, exclusiveGroups: [[101, 'client:kb']] }],
    templateBindings: { 101: { templateId: 7, values: { value_1: 'sys.hp' }, items: {}, slotDigest: 'a' }, 'client:kb': { templateId: 8, values: {}, items: { item_1: 'i1' } } },
    gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', quests: [{ questKey: 'q1', nodeId: 101, actKey: 'act_a' }], survival: { rations: [{ rationKey: 'r1', actKey: 'act_a', itemId: 'i1', quantity: 1, fallbackHpCost: 2 }] } },
  }),
};
// 服务端重建站点:新编号 901/902/903/904,client 键与旧编号都换成新编号;slotDigest/编译字段可以变
const richBack = (mutate) => {
  const cfg = {
    items: [{ id: 'i1', kind: 'ITEM', name: '口粮' }],
    chapters: [{ index: 0, hpCost: 0, luckCost: 0, grants: [], eggs: [], exclusiveGroups: [[902, 901]] },
      { index: 1, hpCost: 0, luckCost: 0, grants: [], eggs: [], completion: { type: 'ANY_N', quantity: 1, overflowSlot: 'shared' }, extraNodes: [{ nodeId: 904, slot: 'shared' }] }],
    templateBindings: { 901: { templateId: 7, values: { value_1: 'sys.hp' }, items: {}, slotDigest: 'changed' }, 902: { templateId: 8, values: {}, items: { item_1: 'i1' } } },
    gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', quests: [{ questKey: 'q1', nodeId: 901, actKey: 'act_a', extraSlot: 'compiled' }], survival: { rations: [{ rationKey: 'r1', actKey: 'act_a', itemId: 'i1', quantity: 1, fallbackHpCost: 2 }] } },
  };
  const d = { isDraft: true, topic: { name: '寒冬' }, journeyRules: JSON.stringify({ schemaVersion: 1, stateEnabled: true, checkEnabled: true, hp: { enabled: true, init: 8, max: 12 } }),
    chapters: [
      { name: '一', cmsTopicNodeList: [{ id: 901, name: '甲', templateId: 7 }, { id: 902, name: '乙', templateId: 8 }] },
      { name: '二', cmsTopicNodeList: [{ id: 903, name: '丙', templateId: 9 }, { id: 904, name: '丁', templateId: 0 }] },
    ], themeRuntimeConfig: cfg };
  if (mutate) mutate(d, cfg);
  d.themeRuntimeConfig = JSON.stringify(d.themeRuntimeConfig);
  return d;
};

test('回读核对：服务端合法换号通过；章节合并/顺序、玩法、资源对应、规则缺失、配置被清空都要拦下', () => {
  assert.equal(readbackIssue(richSent, richBack(), true), '');
  // 章节合并
  assert.match(readbackIssue(richSent, richBack(d => { d.chapters = [{ name: '一', cmsTopicNodeList: d.chapters[0].cmsTopicNodeList.concat(d.chapters[1].cmsTopicNodeList) }]; }), true), /章节数/);
  // 站点挪到别的章(名称数量不变)
  assert.match(readbackIssue(richSent, richBack(d => { const t = d.chapters[0].cmsTopicNodeList[1]; d.chapters[0].cmsTopicNodeList[1] = d.chapters[1].cmsTopicNodeList[0]; d.chapters[1].cmsTopicNodeList[0] = t; }), true), /第1章/);
  // 玩法换了
  assert.match(readbackIssue(richSent, richBack(d => { d.chapters[1].cmsTopicNodeList[0].templateId = 99; }), true), /玩法/);
  // 资源对应变了
  assert.match(readbackIssue(richSent, richBack((d, c) => { c.templateBindings[902].items.item_1 = 'other'; }), true), /资源对应/);
  // 规则缺失 / 额度关系变了
  assert.match(readbackIssue(richSent, richBack((d, c) => { delete c.chapters[1].extraNodes; }), true), /章节规则/);
  assert.match(readbackIssue(richSent, richBack((d, c) => { c.chapters[1].extraNodes[0].slot = 'other'; }), true), /章节规则/);
  assert.match(readbackIssue(richSent, richBack((d, c) => { c.chapters[0].exclusiveGroups = [[901, 903]]; }), true), /章节规则/);
  // 整段运行配置为空
  assert.match(readbackIssue(richSent, richBack(d => { d.themeRuntimeConfig = {}; }), true), /章节规则|资源对应|物品/);
  // 配给与初始状态
  assert.match(readbackIssue(richSent, richBack((d, c) => { c.gameplayRuntime.survival.rations = []; }), true), /章末配给/);
  assert.match(readbackIssue(richSent, richBack(d => { d.journeyRules = JSON.stringify({ schemaVersion: 1, stateEnabled: true, hp: { enabled: true, init: 10, max: 12 } }); }), true), /初始状态/);
});

test('首次建草稿丢回包：重试被服务端认出(recovered)后立刻用当前内容再存一次，再回读', () => {
  const calls = [];
  const saver = createServerDraftSaver({
    save: (p, cb) => calls.push(['save', p, cb]),
    readback: (id, cb) => calls.push(['readback', id, cb]),
    publish: (id, cb) => calls.push(['publish', id, cb]),
  });
  const events = [];
  const h = { onSaved: id => events.push(['saved', id]), onSuccess: r => events.push(['ok', r]), onFail: r => events.push(['fail', r]) };
  saver.save(payload, h);
  calls[0][2]({ ok: false, networkError: true });
  assert.equal(events[0][1].unknown, true, '丢回包是「不确定」,不是「没保存」');
  saver.save(payload, h);
  calls[1][2]({ ok: true, data: { topicId: 501, recovered: true } });
  assert.deepEqual(events[1], ['saved', 501]);
  assert.equal(calls[2][0], 'save', '找回后再存一次当前内容');
  calls[2][2]({ ok: true, data: { topicId: 501 } });
  assert.equal(calls[3][0], 'readback');
  calls[3][2]({ ok: true, data: detail() });
  assert.equal(events.at(-1)[0], 'ok');
});

test('发布丢回包：回读查到已发布就按已发布收口；仍是草稿才说没发布；查不到则下次先查状态', () => {
  const run = () => {
    const calls = [], events = [];
    const saver = createServerDraftSaver({
      save: (p, cb) => calls.push(['save', p, cb]), readback: (id, cb) => calls.push(['readback', id, cb]), publish: (id, cb) => calls.push(['publish', id, cb]),
    });
    const h = { onSuccess: r => events.push(['ok', r]), onFail: r => events.push(['fail', r]) };
    saver.saveAndPublish(payload, h);
    calls[0][2]({ ok: true, data: { topicId: 501 } }); calls[1][2]({ ok: true, data: detail() });
    return { saver, calls, events, h };
  };
  // 已提交但回包丢了
  let t = run();
  t.calls[2][2]({ ok: false, networkError: true });
  assert.equal(t.calls[3][0], 'readback');
  t.calls[3][2]({ ok: true, data: detail({ isDraft: false, topic: { name: '寒冬', status: 1 } }) });
  assert.equal(t.events[0][0], 'ok'); assert.equal(t.events[0][1].published.recovered, true);
  // 真没发布出去
  t = run();
  t.calls[2][2]({ ok: false, networkError: true });
  t.calls[3][2]({ ok: true, data: detail() });
  assert.equal(t.events[0][1].stage, 'publish'); assert.equal(t.events[0][1].unknown, false);
  // 重试时服务端说「不是草稿」:同样先查
  t = run();
  t.calls[2][2]({ ok: false, msg: '该主题不是草稿，无法发布' });
  assert.equal(t.calls[3][0], 'readback');
  t.calls[3][2]({ ok: true, data: detail({ isDraft: false, topic: { name: '寒冬', status: 0 } }) });
  assert.equal(t.events[0][0], 'ok');
  // 查不到:不确定;下次发布先查状态,已发布就不再对它存草稿
  t = run();
  t.calls[2][2]({ ok: false, networkError: true });
  t.calls[3][2]({ ok: false, networkError: true });
  assert.equal(t.events[0][1].unknown, true);
  t.saver.saveAndPublish(payload, t.h);
  assert.equal(t.calls[4][0], 'readback', '重试前先查真实状态');
  t.calls[4][2]({ ok: true, data: detail({ isDraft: false, topic: { name: '寒冬', status: 1 } }) });
  assert.equal(t.calls.length, 5, '已发布:不再存草稿、不再发布');
  assert.equal(t.events.at(-1)[0], 'ok');
});

test('编辑页：首次建草稿带稳定意图键，丢回包后重试沿用同一个键；拿到主题号后意图结算', () => {
  const { page, sent, stored } = loadPage();
  page.saveServerDraft();
  const first = JSON.parse(sent[0].data).requestId;
  assert.match(first, /^[A-Za-z0-9_-]{16,64}$/);
  sent[0].fail({});
  assert.equal(page.data.resultSheet.title, '保存结果待确认');
  assert.match(page.data.submitError, /不会重复创建/);
  page.data.formData = { name: '寒冬', chapters: [], description: '改了内容' };
  page.saveServerDraft();
  assert.equal(JSON.parse(sent[1].data).requestId, first, '内容改了也沿用同一键');
  sent[1].success({ code: 200, data: { topicId: 501, recovered: true } });
  assert.equal(page.data.editingTopicId, '501');
  assert.equal(sent[2].url, '/api/topic/v2/update', '找回后用当前内容更新同一份');
  assert.equal(JSON.parse(sent[2].data).requestId, undefined);
  assert.ok(!Object.keys(stored).some(k => k.startsWith('square_publish_intent_v1:') && !k.endsWith(':previous') && stored[k] && stored[k].status !== 'success'), '意图已结算');
});
