const test = require('node:test');
const assert = require('node:assert/strict');
const { createServerDraftSaver, readbackIssue } = require('../../pages/publish/utils/publish/server-draft.js');

const payload = { name: '寒冬', publishMode: 'pro', productType: 1, chapters: [{ nodes: [{ name: '甲' }, { name: '乙' }] }] };
const detail = (extra) => Object.assign({ isDraft: true,
  chapters: [{ id: 101, topicId: 501, cmsTopicNodeList: [{ id: 11, chapterId: 101, topicId: 501, name: '甲' }, { id: 12, chapterId: 101, topicId: 501, name: '乙' }] }] }, extra,
  { topic: Object.assign({ id: 501, status: 0, name: '寒冬' }, extra && extra.topic) });
// 真实 author-edit-detail 恒带 topic.configVersion(cms_topic.config_version NOT NULL DEFAULT 1);
// 页面发布未知后的回读按保存时的版本核对，页面级发布恢复夹具必须带上它。
const versioned = (extra) => detail(Object.assign({}, extra, { topic: Object.assign({ configVersion: 3 }, extra && extra.topic) }));

test('回读校验：必须仍是草稿、节点都有编号且与本机一致', () => {
  assert.equal(readbackIssue(payload, detail(), true), '');
  assert.match(readbackIssue(payload, detail({ isDraft: false }), true), /不是草稿/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ id: 11, name: '甲' }] }] }), true), /节点/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ id: 11, name: '乙' }, { id: 12, name: '甲' }] }] }), true), /顺序/);
  assert.match(readbackIssue(payload, detail({ chapters: [{ cmsTopicNodeList: [{ name: '甲' }, { id: 2, name: '乙' }] }] }), true), /编号/);
  assert.match(readbackIssue(payload, detail({ topic: { name: '别的' } }), true), /主题名/);
});

test('封面回读只忽略本站同一对象的完整OSS签名，保留对象、来源及版本差异', () => {
  const asset = 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + 'a'.repeat(32) + '.jpg';
  const before = asset + '?Expires=100&OSSAccessKeyId=old&Signature=a%2Bb';
  const after = asset + '?Signature=new%3D&Expires=200&OSSAccessKeyId=rotated';
  const sent = Object.assign({}, payload, { imgUrl: before, imgArr: before + ',' + before });
  assert.equal(readbackIssue(sent, detail({ topic: { imgUrl: after, imgArr: after + ',' + after } }), true), '');
  for (const changed of [
    after.replace('/' + 'a'.repeat(32), '/' + 'b'.repeat(32)),
    after.replace('chengyin-oss.', 'another-bucket.'), after.replace('https:', 'http:'),
    after + '&versionId=changed', after + '&Signature=duplicate',
    after.replace('&Expires=200', ''), after.replace('Expires=200', 'Expires=invalid'),
    after.replace('new%3D', '%ZZ'), after + '#fragment',
  ]) {
    assert.match(readbackIssue(Object.assign({}, payload, { imgUrl: before }), detail({ topic: { imgUrl: changed } }), true), /主封面/);
  }
  assert.match(readbackIssue(sent, detail({ topic: { imgUrl: after, imgArr: after } }), true), /多图封面/);
  assert.match(readbackIssue(Object.assign({}, payload, { imgUrl: 'https://external.example/p.png?Signature=one' }),
    detail({ topic: { imgUrl: 'https://external.example/p.png?Signature=two' } }), true), /主封面/);
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
  assert.deepEqual(events, [['saved', 501]], 'HTTP 成功还不算成功，要等刷新');
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
  calls[11][2]({ ok: true, data: { topicId: 501, published: true } });
  assert.equal(events.at(-1)[0], 'ok'); assert.deepEqual(events.at(-1)[1].published, { topicId: 501, published: true });
  assert.equal(saver.saveAndPublish(payload, h), false, '发布成功后不再重复发布');
});

function loadPage(stored = {}) {
  const sent = [], removed = [];
  let currentMember = 7;
  global.wx = {
    getStorageSync: k => stored[k], setStorageSync: (k, v) => { stored[k] = v; }, removeStorageSync: k => { removed.push(k); delete stored[k]; },
    showToast() {}, showLoading() {}, hideLoading() {}, getSystemInfoSync() { return {}; }, getWindowInfo() { return {}; },
  };
  global.getCurrentPages = () => [];
  // L21 BL-185:发布前试检查是独立一步(见 l21-bl-185-topic-publish-check.test.js);本文件只验存/回读/发布,
  // 试检查按「无问题」原地放行,也不占 sent 的下标。
  const answerPublishCheck = opts => { opts.success({ code: 200, data: [] }); if (opts.complete) opts.complete(); };
  global.getApp = () => ({ globalData: {}, getUserID: () => currentMember, getRequestErrorMessage: (r, d) => d,
    sendRequest: opts => (opts.url === '/api/topic/publish-check' ? answerPublishCheck(opts) : sent.push(opts)) });
  let page; global.Page = value => { page = value; };
  delete require.cache[require.resolve('../../pages/publish/fabu/index')];
  require('../../pages/publish/fabu/index');
  page.data = { operationScope: '', editingTopicId: '', editLoaded: false, editIsDraft: false, submitting: false, draftSaving: false,
    draftUuid: 'u-1', draftMemberId: 7, formData: { name: '寒冬', chapters: [] }, pendingMaterials: [] };
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb(); };
  page._buildSubmitPayload = () => JSON.parse(JSON.stringify(payload));
  page.applied = [];
  page.applyEditingTopic = function (d) { this.applied.push(d); this.setData({ editIsDraft: d.isDraft === true, editLoaded: true }); this._finishEditingDraftBootstrap(d); return true; };
  return { page, sent, removed, stored, switchMember: id => { currentMember = id; } };
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
  assert.equal(sent[1].url, '/api/topic/author-edit-detail'); assert.equal(sent[1].data.id, 501);
  sent[1].success({ code: 200, data: detail() });
  assert.equal(page.applied.length, 1, '刷新版本换进编辑器');
  assert.ok(removed.some(k => k.includes('u-1')) && removed.some(k => k.includes('501')), '刷新确认后清掉本机恢复稿');
  assert.equal(page.data.resultSheet.title, '草稿已保存到云端'); assert.equal(page.data.draftSaving, false);

  // 再次保存 → update 同一主题;失败保留内容并可重试
  removed.length = 0;
  page.saveServerDraft();
  assert.equal(sent[2].url, '/api/topic/v2/update'); assert.equal(JSON.parse(sent[2].data).id, '501'); assert.equal(JSON.parse(sent[2].data).saveDraft, 1);
  sent[2].fail({});
  assert.equal(page.data.resultSheet.kind, 'fail'); assert.equal(page.data.resultSheet.meta, '草稿仍在本机');
  assert.equal(removed.filter(k => k.startsWith('pro_editor')).length, 0);
  page.saveServerDraft();
  assert.equal(sent[3].url, '/api/topic/author-edit-detail', '未知更新只核对原主题，不重复写');
  sent[3].success({ code: 200, data: detail() });
  assert.equal(page._serverDraftAttempt, null);

  // 明确的输入拒绝可以修改后重试；5xx未知另有反例覆盖。
  page._doSubmit();
  assert.equal(sent[4].url, '/api/topic/v2/update'); assert.equal(JSON.parse(sent[4].data).saveDraft, 1);
  sent[4].success({ code: 400, msg: '参数非法' });
  assert.ok(!sent.some(r => r.url === '/api/topic/publish-draft'));
  assert.equal(page.data.resultSheet.title, '这次还没有发布出去');
  page._doSubmit();
  sent[5].success({ code: 200, data: { topicId: 501 } }); sent[6].success({ code: 200, data: detail() });
  assert.equal(sent[7].url, '/api/topic/publish-draft'); assert.equal(sent[7].data.id, 501);
  page._doSubmit(); assert.equal(sent.length, 8, '发布在途不重复');
  sent[7].success({ code: 200, data: { topicId: 501, published: true, reviewState: 'NOT_REQUIRED' } });
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

test('删改节点后：存草稿不被「章节与到达关系」闸拦住（那道闸要求先存草稿），发布仍过闸', () => {
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
// 服务端重建节点:新编号 901/902/903/904,client 键与旧编号都换成新编号;slotDigest/编译字段可以变
const richBack = (mutate) => {
  const cfg = {
    items: [{ id: 'i1', kind: 'ITEM', name: '口粮' }],
    chapters: [{ index: 0, hpCost: 0, luckCost: 0, grants: [], eggs: [], exclusiveGroups: [[902, 901]] },
      { index: 1, hpCost: 0, luckCost: 0, grants: [], eggs: [], completion: { type: 'ANY_N', quantity: 1, overflowSlot: 'shared' }, extraNodes: [{ nodeId: 904, slot: 'shared' }] }],
    templateBindings: { 901: { templateId: 7, values: { value_1: 'sys.hp' }, items: {}, slotDigest: 'changed' }, 902: { templateId: 8, values: {}, items: { item_1: 'i1' } } },
    gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', quests: [{ questKey: 'q1', nodeId: 901, actKey: 'act_a', extraSlot: 'compiled' }], survival: { rations: [{ rationKey: 'r1', actKey: 'act_a', itemId: 'i1', quantity: 1, fallbackHpCost: 2 }] } },
  };
  const d = { isDraft: true, topic: { id: 501, name: '寒冬' }, journeyRules: JSON.stringify({ schemaVersion: 1, stateEnabled: true, checkEnabled: true, hp: { enabled: true, init: 8, max: 12 } }),
    chapters: [
      { id: 301, topicId: 501, name: '一', cmsTopicNodeList: [{ id: 901, chapterId: 301, topicId: 501, name: '甲', templateId: 7 }, { id: 902, chapterId: 301, topicId: 501, name: '乙', templateId: 8 }] },
      { id: 302, topicId: 501, name: '二', cmsTopicNodeList: [{ id: 903, chapterId: 302, topicId: 501, name: '丙', templateId: 9 }, { id: 904, chapterId: 302, topicId: 501, name: '丁', templateId: 0 }] },
    ], themeRuntimeConfig: cfg };
  if (mutate) mutate(d, cfg);
  d.themeRuntimeConfig = JSON.stringify(d.themeRuntimeConfig);
  return d;
};

test('回读核对：服务端合法换号通过；章节合并/顺序、玩法、资源对应、规则缺失、配置被清空都要拦下', () => {
  assert.equal(readbackIssue(richSent, richBack(), true), '');
  // 章节合并
  assert.match(readbackIssue(richSent, richBack(d => { d.chapters = [{ name: '一', cmsTopicNodeList: d.chapters[0].cmsTopicNodeList.concat(d.chapters[1].cmsTopicNodeList) }]; }), true), /章节数/);
  // 节点挪到别的章(名称数量不变)
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

test('首次建草稿丢回包：重试被服务端认出(recovered)后先回读，不抢写当前内容', () => {
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
  assert.equal(calls[2][0], 'readback', '找回后先核对原内容');
  calls[2][2]({ ok: true, data: detail() });
  assert.equal(calls.length, 3, '不能未经回读立即更新');
  assert.equal(events.at(-1)[0], 'ok');
});

test('发布丢回包：回读查到已发布就按已发布收口；仍是草稿也保留未知；查不到则下次先查状态', () => {
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
  // 读到草稿不能排除原发布仍在执行
  t = run();
  t.calls[2][2]({ ok: false, networkError: true });
  t.calls[3][2]({ ok: true, data: detail() });
  assert.equal(t.events[0][1].stage, 'publish'); assert.equal(t.events[0][1].unknown, true);
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
  assert.match(page.data.submitError, /已保留原请求和内容/);
  assert.equal(page._serverDraftAttempt.status, 'UNKNOWN');
  assert.equal(page._serverDraftAttempt.payload.requestId, first);
  page.data.formData = { name: '寒冬', chapters: [], description: '改了内容' };
  page.saveServerDraft();
  assert.equal(JSON.parse(sent[1].data).requestId, first, '内容改了也沿用同一键');
  sent[1].success({ code: 200, data: { topicId: 501, recovered: true } });
  assert.equal(page.data.editingTopicId, '501');
  assert.equal(sent[2].url, '/api/topic/author-edit-detail', '找回后先核对原内容与版本');
  assert.equal(sent[2].data.id, 501);
  assert.ok(!Object.keys(stored).some(k => k.startsWith('square_publish_intent_v1:') && !k.endsWith(':previous') && stored[k] && stored[k].status !== 'success'), '意图已结算');
});


test('服务端草稿发布前落盘 UNKNOWN；丢回包后重进只查原主题，确认已发布不再保存或发布', () => {
  const first = loadPage();
  Object.assign(first.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  first.page._doSubmit();
  first.sent[0].success({ code: 200, data: 501 });
  first.sent[1].success({ code: 200, data: versioned() });
  assert.equal(first.sent[2].url, '/api/topic/publish-draft');
  const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  const saved = drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 });
  assert.deepEqual(saved.envelope.publishAttempt, { status: 'UNKNOWN', kind: 'SERVER_DRAFT', topicId: '501', configVersion: '3' }, '请求发出前已持久化');
  first.sent[2].fail({}); first.sent[3].fail({});
  assert.equal(first.page.data.resultSheet.title, '发布结果待确认');
  first.page.onUnload();

  const next = loadPage(first.stored);
  Object.assign(next.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  next.page._ensureLocalIds = next.page._materializeCityStoryChapters = next.page.updateAllStatistics = () => {};
  next.page._applyDraftEnvelope(saved.envelope);
  next.page._doSubmit();
  assert.equal(next.sent.length, 1);
  assert.equal(next.sent[0].url, '/api/topic/author-edit-detail');
  assert.equal(next.sent[0].data.id, 501);
  next.sent[0].success({ code: 200, data: versioned({ isDraft: false, topic: { name: '寒冬', status: 1 } }) });
  assert.equal(next.sent.length, 1, '已发布主题不再写草稿或重复发布');
  assert.equal(next.page.data.resultSheet.title, '城市路线已发布');
  assert.equal(drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 }).status, 'missing');
});

test('保存或回读的迟到回包不能在换账号/范围后继续发发布请求或改页面', () => {
  for (const boundary of ['save', 'readback']) {
    for (const changed of ['account', 'scope']) {
      const t = loadPage();
      Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
      t.page._doSubmit();
      if (boundary === 'readback') t.sent[0].success({ code: 200, data: 501 });
      const count = t.sent.length;
      const before = JSON.stringify(t.page.data);
      if (changed === 'account') t.switchMember(8);
      else t.page.data.operationScope = 'MERCHANT';
      const expected = changed === 'scope' ? JSON.stringify(t.page.data) : before;
      t.sent.at(-1).success({ code: 200, data: boundary === 'save' ? 501 : detail() });
      assert.equal(t.sent.length, count, boundary + '/' + changed + ': 不推进下一次请求');
      assert.equal(JSON.stringify(t.page.data), expected, '不改当前账号页面');
    }
  }
});

test('发布标记无法落盘时不发 publish-draft；不完整回读不能解除 UNKNOWN 后写草稿', () => {
  const t = loadPage();
  Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  t.page._doSubmit(); t.sent[0].success({ code: 200, data: 501 });
  wx.setStorageSync = () => { throw new Error('full'); };
  t.sent[1].success({ code: 200, data: detail() });
  assert.equal(t.sent.length, 2, '落盘失败不发发布');
  assert.match(t.page.data.submitError, /尚未发起发布/);

  const calls = [], events = [];
  const saver = createServerDraftSaver({
    pendingPublish: () => 501,
    save: () => calls.push('save'), publish: () => calls.push('publish'),
    readback: (id, cb) => { calls.push('readback'); cb({ ok: true, data: { topic: { id } } }); }
  });
  saver.saveAndPublish(payload, { onFail: r => events.push(r) });
  assert.deepEqual(calls, ['readback']);
  assert.equal(events[0].unknown, true);
});


test('发布同步异常仍保留 UNKNOWN，迟到或重复回调不能再结算一次', () => {
  const calls = [], events = [];
  let latePublish;
  const saver = createServerDraftSaver({
    save: (p, cb) => { cb({ ok: true, data: 501 }); cb({ ok: true, data: 501 }); },
    readback: (id, cb) => { calls.push('readback'); cb(calls.length === 1 ? { ok: true, data: detail() } : { ok: false, networkError: true }); },
    publish: (id, cb) => { calls.push('publish'); latePublish = cb; throw new Error('network'); },
  });
  saver.saveAndPublish(payload, { onFail: r => events.push(r), onSuccess: r => events.push(r) });
  assert.deepEqual(calls, ['readback', 'publish', 'readback']);
  assert.equal(events.length, 1); assert.equal(events[0].unknown, true);
  latePublish({ ok: true, data: { published: true } });
  assert.equal(events.length, 1);
  assert.equal(saver.isBusy(), false);
});


test('UNKNOWN后改写本机稿，回读确认原版本已发布时不丢后续修改', () => {
  const t = loadPage();
  Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  t.page._doSubmit(); t.sent[0].success({ code: 200, data: 501 }); t.sent[1].success({ code: 200, data: versioned() });
  t.sent[2].fail({}); t.sent[3].fail({});
  t.page.data.formData.name = '后续修改';
  t.page._buildSubmitPayload = () => Object.assign({}, payload, { name: '后续修改' });
  t.page._doSubmit();
  assert.equal(t.sent[4].url, '/api/topic/author-edit-detail');
  t.sent[4].success({ code: 200, data: versioned({ isDraft: false, topic: { name: '寒冬', status: 1 } }) });
  assert.equal(t.sent.length, 5, '只读原主题、不重复发布后续修改');
  const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  const saved = drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 });
  assert.equal(saved.envelope.formData.name, '后续修改');
  assert.equal(saved.envelope.publishAttempt, undefined, '原发布已确认，保留新稿而不是旧UNKNOWN');
  assert.equal(t.page.data.resultSheet.meta, '草稿仍在本机');
});


test('发布200但仍DRAFT、UNKNOWN回读空或异主题不得当成成功', () => {
  for (const wrong of [{ isDraft: false, topic: {} }, { isDraft: false, topic: { id: 999, status: 1 } },
    { isDraft: false, topic: { id: 501, status: 'invalid' } }, { isDraft: false, topic: { id: 501, status: false } },
    { isDraft: false, topic: { id: 501, status: ' ' } }]) {
    const t = loadPage();
    Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
    t.page._doSubmit(); t.sent[0].success({ code: 200, data: 501 }); t.sent[1].success({ code: 200, data: detail() });
    t.sent[2].success({ code: 200, data: { published: false, reviewState: 'DRAFT' } });
    assert.equal(t.sent[3].url, '/api/topic/author-edit-detail', '未确认的200仍刷新');
    t.sent[3].success({ code: 200, data: wrong });
    assert.equal(t.page.data.editIsDraft, true);
    assert.equal(t.page.data.resultSheet.title, '发布结果待确认');
    assert.equal(t.page._publishAttempt.status, 'UNKNOWN');
  }
});



test('保存草稿的回读也必须对应原主题，异号内容相同仍不能发布或清稿', () => {
  const t = loadPage();
  Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  t.page._persistDraftEnvelope();
  t.page._doSubmit();
  t.sent[0].success({ code: 200, data: 501 });
  t.sent[1].success({ code: 200, data: detail({ topic: { id: 999 } }) });
  assert.equal(t.sent.length, 2);
  assert.equal(t.page.data.resultSheet.kind, 'fail');
  assert.match(t.page.data.resultSheet.why, /编号与本次不一致/);
  const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  assert.equal(drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 }).envelope.formData.name, '寒冬');
});

test('UNKNOWN原号回读审核拒绝：明确失败并保留修改，不能误称提交审核或自动重发', () => {
  for (const reload of [false, true]) {
    const t = loadPage();
    Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
    t.page._doSubmit();
    t.sent[0].success({ code: 200, data: 501 });
    t.sent[1].success({ code: 200, data: versioned() });
    t.sent[2].fail({});
    if (reload) {
      t.sent[3].fail({});
      t.page._doSubmit();
    }
    t.sent.at(-1).success({ code: 200, data: versioned({
      isDraft: false, topic: { status: 2, reason: '请补充规则' }
    }) });
    assert.equal(t.page.data.resultSheet.title, '审核未通过');
    assert.equal(t.page.data.resultSheet.kind, 'fail');
    assert.match(t.page.data.resultSheet.why, /请补充规则/);
    assert.equal(t.page._publishAttempt, null, '已核实拒审，不再保留UNKNOWN');
    assert.equal(t.page.data.editIsDraft, false, '拒审实体不能冒充可自动发布的草稿');
    const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
    assert.equal(drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 }).envelope.formData.name, '寒冬');
    assert.equal(t.sent.length, reload ? 5 : 4, '仅刷新原号，不自动重发写请求');
  }
});

test('缺失或异主题发布回执先按原号回读，确认前不清稿且不能凭空认成功', () => {
  for (const receipt of [{ published: true }, { topicId: 999, published: true },
    { topicId: 0, published: true }, { topicId: false, published: true }]) {
    const t = loadPage();
    Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
    t.page._doSubmit();
    t.sent[0].success({ code: 200, data: 501 });
    t.sent[1].success({ code: 200, data: versioned() });
    t.sent[2].success({ code: 200, data: receipt });
    assert.equal(t.sent.length, 4, '未绑定原topicId的回执不得直接结算');
    assert.equal(t.sent[3].url, '/api/topic/author-edit-detail');
    assert.equal(t.sent[3].data.id, 501);
    const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
    assert.equal(drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 }).envelope.publishAttempt.status, 'UNKNOWN');
    t.sent[3].success({ code: 200, data: versioned({ isDraft: false, topic: { status: 1 } }) });
    assert.equal(t.page.data.resultSheet.title, '城市路线已发布');
    assert.equal(drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 }).status, 'missing');
    assert.equal(t.sent.length, 4, '确认只读原主题，不重复publish');
  }
});

test('服务端草稿发布A在途时自动保存B，成功仅结算A而保留B和后续自动保存', () => {
  const t = loadPage();
  Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
  t.page._draftAutosaveReady = true;
  t.page._installDraftAutosave();
  t.page._doSubmit();
  t.sent[0].success({ code: 200, data: 501 });
  t.sent[1].success({ code: 200, data: detail() });
  t.page.setData({ formData: Object.assign({}, t.page.data.formData, { name: '后续B' }) });
  t.sent[2].success({ code: 200, data: { topicId: 501, published: true } });
  const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  let saved = drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 });
  assert.equal(saved.envelope.formData.name, '后续B');
  assert.equal(saved.envelope.publishAttempt, undefined);
  assert.equal(t.page.data.resultSheet.meta, '草稿仍在本机');
  t.page.setData({ formData: Object.assign({}, t.page.data.formData, { name: '后续C' }) });
  saved = drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 });
  assert.equal(saved.envelope.formData.name, '后续C');
  assert.equal(t.sent.length, 3, '不得把B或C自动再发布');
});

test('普通创建和编辑发布A在途时自动保存B，成功不清B也不保留旧UNKNOWN', () => {
  for (const editingTopicId of ['', '501']) {
    const t = loadPage();
    Object.assign(t.page.data, { editingTopicId, editLoaded: true, editIsDraft: false });
    t.page._draftAutosaveReady = true;
    t.page._installDraftAutosave();
    t.page.sendData(payload);
    t.page.setData({ formData: Object.assign({}, t.page.data.formData, { name: '后续B' }) });
    t.sent[0].success({ code: 200, data: { topicId: 501, published: true } });
    const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
    const identity = editingTopicId ? { topicId: '501' } : { draftUuid: 'u-1' };
    const saved = drafts.loadDraft(wx, identity, { memberId: 7 });
    assert.equal(saved.envelope.formData.name, '后续B');
    assert.equal(saved.envelope.publishAttempt, undefined);
    assert.equal(t.page.data.resultSheet.meta, '草稿仍在本机');
    assert.equal(t.page._draftAutosaveReady, true);
    assert.equal(t.sent.length, 1);
  }
});



test('发布已确认但新修改遇存储故障：不假称已本机保存，不清旧稿，保留内存和离开警告', () => {
  for (const kind of ['ordinary', 'server-draft', 'rejected']) {
    const t = loadPage();
    Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: kind !== 'ordinary' });
    t.page._draftAutosaveReady = true;
    t.page._installDraftAutosave();
    if (kind === 'ordinary') t.page.sendData(payload);
    else {
      t.page._doSubmit();
      t.sent[0].success({ code: 200, data: 501 });
      t.sent[1].success({ code: 200, data: versioned() });
    }
    const storedBefore = JSON.stringify(t.stored);
    const originalSet = wx.setStorageSync;
    wx.setStorageSync = () => { throw new Error('storage full'); };
    try {
      t.page.setData({ formData: Object.assign({}, t.page.data.formData, { name: '仅内存B' }) });
      if (kind === 'rejected') {
        t.sent[2].fail({});
        t.sent[3].success({ code: 200, data: versioned({ isDraft: false, topic: { status: 2 } }) });
      } else {
        t.sent.at(-1).success({ code: 200, data: { topicId: 501, published: true } });
        // 带版本的服务端草稿发布回执还要按原版本回读确认已发布。
        if (kind === 'server-draft') t.sent[3].success({ code: 200, data: versioned({ isDraft: false, topic: { status: 1 } }) });
      }
    } finally { wx.setStorageSync = originalSet; }
    assert.equal(t.page.data.formData.name, '仅内存B');
    assert.equal(t.page.data.resultSheet.kind, 'fail');
    assert.match(t.page.data.resultSheet.title, /尚未保存/);
    assert.notEqual(t.page.data.resultSheet.meta, '草稿仍在本机');
    assert.match(t.page.data.submitError, /未能保存到本机/);
    assert.equal(t.page._resultSheetNext, '', '不能自动跳走丢掉仅在内存中的B');
    assert.equal(t.page._draftAutosaveReady, true, '原有goBack保存失败警告仍然生效');
    assert.equal(JSON.stringify(t.stored), storedBefore, '既有A及原号UNKNOWN不被删除或覆盖');
    const writes = t.sent.length;
    assert.equal(t.page._persistDraftEnvelope(), true, '存储恢复后仍可用原自动保存机制保存B');
    const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
    assert.equal(drafts.loadDraft(wx, { topicId: '501' }, { memberId: 7 }).envelope.formData.name, '仅内存B');
    assert.equal(t.sent.length, writes, '重存本机不重复发布');
  }
});

test('普通发布200缺身份或未确认发布状态仍UNKNOWN，更新还必须是原主题', () => {
  const malformed = [undefined, null, {}, true, false, 0, -1, '', 501.5,
    { published: true }, { topicId: 501 }, { topicId: 501, published: false, reviewState: 'DRAFT' },
    { topicId: 501, published: false, reviewState: 'REJECTED' }];
  for (const editingTopicId of ['', '501']) {
    for (const receipt of malformed.concat(editingTopicId ? [502, { topicId: 502, published: true }] : [])) {
      const t = loadPage();
      Object.assign(t.page.data, { editingTopicId, editLoaded: true, editIsDraft: false });
      t.page.sendData(payload);
      t.sent[0].success({ code: 200, data: receipt });
      assert.equal(t.page.data.resultSheet.title, '发布结果待确认', JSON.stringify(receipt));
      assert.equal(t.page._publishAttempt.status, 'UNKNOWN');
      const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
      const saved = drafts.loadDraft(wx, editingTopicId ? { topicId: '501' } : { draftUuid: 'u-1' }, { memberId: 7 });
      assert.equal(saved.envelope.formData.name, '寒冬');
      assert.equal(saved.envelope.publishAttempt.status, 'UNKNOWN');
      t.page.sendData(payload);
      assert.equal(t.sent.length, 1, '不因畸形200重复创建或更新');
    }
  }
});

test('普通发布真实legacy数字和v2明确发布或待审回执仍能结算原稿', () => {
  const receipts = [501, { topicId: 501, published: true },
    { topicId: 501, published: false, reviewState: 'PENDING' },
    { topicId: 501, published: false, reviewState: 'ESCALATED' },
    { topicId: 501, published: false, commercialAwaitingReview: true }];
  for (const editingTopicId of ['', '501']) {
    for (const receipt of receipts) {
      const t = loadPage();
      Object.assign(t.page.data, { editingTopicId, editLoaded: true, editIsDraft: false });
      t.page.sendData(payload);
      t.sent[0].success({ code: 200, data: receipt });
      const pending = receipt && receipt.published === false;
      assert.equal(t.page.data.resultSheet.title, pending ? '已提交审核' : editingTopicId ? '已发布' : '城市路线已发布');
      assert.equal(t.page._publishAttempt, null);
      const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
      assert.equal(drafts.loadDraft(wx, editingTopicId ? { topicId: '501' } : { draftUuid: 'u-1' }, { memberId: 7 }).status, 'missing');
      assert.equal(t.sent.length, 1);
    }
  }
});

test('普通发布迟到回执同样不能结算别的scope、草稿所属账号或baseRevision', () => {
  for (const field of ['operationScope', 'draftMemberId', 'baseRevision']) {
    const t = loadPage();
    Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: false });
    t.page.sendData(payload);
    t.page.data[field] = field === 'operationScope' ? 'MERCHANT' : 'changed';
    const before = JSON.stringify(t.stored);
    const uiBefore = JSON.stringify(t.page.data);
    t.sent[0].success({ code: 200, data: { topicId: 501, published: true } });
    assert.equal(JSON.stringify(t.stored), before);
    assert.equal(JSON.stringify(t.page.data), uiBefore);
  }
});

test('服务端草稿的迟到发布回包不能结算别的账号、scope、主题或编辑版本', () => {
  for (const field of ['member', 'draftMemberId', 'operationScope', 'editingTopicId', 'draftUuid', 'baseRevision', '_editLoadToken']) {
    const t = loadPage();
    Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
    t.page._doSubmit();
    t.sent[0].success({ code: 200, data: 501 });
    t.sent[1].success({ code: 200, data: detail() });
    if (field === 'member') t.switchMember(8);
    else if (field === '_editLoadToken') t.page._editLoadToken = 'new-load';
    else t.page.data[field] = field === 'operationScope' ? 'MERCHANT' : 'changed';
    const before = JSON.stringify(t.stored);
    const uiBefore = JSON.stringify(t.page.data);
    t.sent[2].success({ code: 200, data: { topicId: 501, published: true } });
    assert.equal(JSON.stringify(t.stored), before, field + '变化后不清稿或写到另一个信封');
    assert.equal(JSON.stringify(t.page.data), uiBefore, field + '变化后不改当前UI');
  }
});

test('无法读取历史草稿或尚未确认旧草稿入口时，服务端草稿路径也不能写', () => {
  for (const flag of ['_draftStorageUnavailable', '_draftScopeUnconfirmed']) {
    const t = loadPage(); Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true });
    t.page[flag] = true;
    t.page._doSubmit(); t.page.saveServerDraft();
    assert.equal(t.sent.length, 0);
  }
});

test('同账号商家与个人的新草稿、active指针和UNKNOWN各自恢复，跨入口不消费或删除', () => {
  const t = loadPage();
  const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  Object.assign(t.page.data, { operationScope: 'MERCHANT', draftUuid: 'merchant-draft', formData: { name: '商家原稿', chapters: [] } });
  t.page._publishAttempt = { status: 'UNKNOWN', kind: 'SERVER_DRAFT', topicId: '501' };
  assert.equal(t.page._persistDraftEnvelope(), true);
  const personal = loadPage(t.stored);
  personal.page._newDraftUuid = () => 'personal-draft';
  assert.equal(personal.page._bootstrapNewLocalDraft({}), false);
  assert.equal(personal.page._publishAttempt, undefined);
  personal.page.data.formData.name = '个人原稿';
  personal.page._persistDraftEnvelope();
  assert.equal(drafts.readActiveNewDraft(wx, 7, 'MERCHANT'), 'merchant-draft');
  assert.equal(drafts.readActiveNewDraft(wx, 7, ''), 'personal-draft');
  assert.equal(drafts.loadDraft(wx, { draftUuid: 'merchant-draft' }, { memberId: 7 }).status, 'missing', '其他入口的分桶不会被读取');
  assert.equal(drafts.removeDraft(wx, { draftUuid: 'merchant-draft' }, { memberId: 7 }), false);
  const restored = drafts.loadDraft(wx, { draftUuid: 'merchant-draft' }, { memberId: 7, operationScope: 'MERCHANT' });
  assert.equal(restored.envelope.formData.name, '商家原稿');
  assert.equal(restored.envelope.publishAttempt.topicId, '501');
});

// 2026-10-27 P-44(业主):旧信封缺 scope 不再当场确认 —— 默认归到当前入口、静默恢复。
// 原「确认前不能自动保存/发布、换入口后旧确认无效」随确认弹窗一起退役;UNKNOWN 发布结果仍保留、仍不自动发。
test('旧信封缺少scope:静默恢复到当前入口,不弹窗,UNKNOWN 发布结果照旧保留且不自动发', () => {
  const t = loadPage();
  t.stored.pro_editor_draft_member_7_new_legacy = { memberId: 7, draftUuid: 'legacy',
    formData: { name: '旧原稿', chapters: [] }, publishAttempt: { status: 'UNKNOWN', topicId: '' } };
  t.stored.pro_editor_active_7 = 'legacy';
  const modals = [];
  wx.showModal = options => { modals.push(options); };
  t.page._ensureLocalIds = t.page._materializeCityStoryChapters = t.page.updateAllStatistics = () => {};
  assert.equal(t.page._bootstrapNewLocalDraft({}), true);
  assert.equal(modals.length, 0, '不再弹「确认旧草稿的发布入口」');
  assert.notEqual(t.page._draftScopeUnconfirmed, true);
  assert.equal(t.page.data.formData.name, '旧原稿');
  assert.equal(t.page._publishAttempt.status, 'UNKNOWN', '静默恢复不会擅自解除UNKNOWN');
  t.page.sendData(payload);
  assert.equal(t.sent.length, 0);
});

// 2026-10-27 P-44(业主):没有记录入口的旧草稿默认归到当前入口、静默恢复,不弹「确认旧草稿的发布入口」。
test('P-44 旧草稿没有记录入口:静默恢复到当前入口并立即补记入口,不弹窗', () => {
  const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  const stored = {};
  const seed = loadPage(stored);
  seed.page.data.formData = { name: '旧稿', chapters: [] };
  assert.equal(seed.page._persistDraftEnvelope(), true);
  const key = drafts.draftKeyFor({ draftUuid: 'u-1' }, 7, '');
  delete stored[key].operationScope; // 模拟 operationScope 字段出现之前存下的旧草稿
  const t = loadPage(stored);
  const modal = require('../../utils/modal.js');
  const original = modal.show; let prompts = 0;
  modal.show = () => { prompts += 1; };
  try {
    t.page._newDraftUuid = () => 'u-1';
    assert.equal(t.page._bootstrapNewLocalDraft({}), true, '旧草稿直接恢复');
  } finally { modal.show = original; }
  assert.equal(prompts, 0, '不再弹归属确认');
  assert.equal(t.page.data.formData.name, '旧稿');
  assert.equal(t.page._draftScopeUnconfirmed, undefined);
  assert.equal(stored[key].operationScope, '', '恢复后按当前入口补记');
  const fabuWxml = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../pages/publish/fabu/index.wxml'), 'utf8');
  assert.doesNotMatch(fabuWxml, /旧草稿还没恢复|确认归属|legacyScopePending/);
});

test('W8：发布确定拒绝的迟到回包不覆盖保存后继续编辑的内容', () => {
  const t = loadPage();
  t.page._publishServerDraft(payload);
  t.sent[0].success({ code: 200, data: { topicId: 501 } });
  t.sent[1].success({ code: 200, data: detail({ topic: { configVersion: 3 } }) });
  assert.equal(t.sent[2].url, '/api/topic/publish-draft');
  t.page.data.formData.name = '保存后继续写的新内容';
  t.page._persistDraftEnvelope();
  const applied = t.page.applied.length;
  t.sent[2].success({ code: 400, msg: '媒体仍在审核' });
  assert.equal(t.page.applied.length, applied, '拒绝回包不能换上旧回读内容');
  assert.equal(t.page.data.formData.name, '保存后继续写的新内容');
  assert.notEqual(t.page.data.themeSaveState, 'saved');
  const local = require('../../pages/publish/utils/publish/pro-editor-draft.js')
    .loadDraft(wx, { topicId: '501' }, { memberId: 7 });
  assert.equal(local.envelope.formData.name, '保存后继续写的新内容');
  assert.equal(t.page._publishAttempt, null, '确定拒绝才解除发布未知态');
});

test('W8：发布UNKNOWN回读仍草稿后重进保留原主题与本机内容，仅查询', () => {
  const t = loadPage(); t.page._publishServerDraft(payload);
  t.sent[0].success({ code: 200, data: { topicId: 501 } });
  t.sent[1].success({ code: 200, data: detail() });
  t.sent[2].fail({}); t.sent[3].success({ code: 200, data: detail() });
  assert.equal(t.page.data.themeSaveState, 'unknown');
  assert.equal(t.page._publishAttempt.status, 'UNKNOWN');
  const next = loadPage(t.stored);
  const envelope = require('../../pages/publish/utils/publish/pro-editor-draft.js')
    .loadDraft(wx, { topicId: '501' }, { memberId: 7 }).envelope;
  next.page.data.editingTopicId = '501'; next.page.data.editIsDraft = true; next.page.data.editLoaded = true;
  next.page._publishAttempt = envelope.publishAttempt;
  next.page._publishServerDraft(payload);
  assert.equal(next.sent[0].url, '/api/topic/author-edit-detail');
  next.sent[0].success({ code: 200, data: detail() });
  assert.equal(next.sent.length, 1); assert.equal(next.page._publishAttempt.status, 'UNKNOWN');
});

test('W8：UNKNOWN创建冻结原请求号和输入，后续编辑与重进不改写原意图', () => {
  const t = loadPage();
  t.page._buildSubmitPayload = () => ({ name: t.page.data.formData.name, chapters: [] });
  t.page.saveServerDraft();
  const original = JSON.parse(t.sent[0].data);
  t.sent[0].fail({});
  t.page.data.formData.name = '后来写的新内容'; t.page._persistDraftEnvelope();
  const drafts = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  const envelope = drafts.loadDraft(wx, { draftUuid: 'u-1' }, { memberId: 7 }).envelope;
  assert.equal(envelope.serverDraftAttempt.payload.name, '寒冬');
  assert.equal(envelope.serverDraftAttempt.payload.requestId, original.requestId);
  const next = loadPage(t.stored);
  next.page._serverDraftAttempt = envelope.serverDraftAttempt;
  next.page.data.formData = envelope.formData;
  next.page._buildSubmitPayload = () => ({ name: next.page.data.formData.name, chapters: [] });
  next.page.saveServerDraft();
  assert.deepEqual(JSON.parse(next.sent[0].data), original);
  next.sent[0].success({ code: 200, data: { topicId: 501, recovered: true } });
  assert.equal(next.sent[1].url, '/api/topic/author-edit-detail', '先核原保存，不能直接更新新内容');
});

test('W8：UNKNOWN更新重试只读原主题，负向回读不得解除；正向确认后保留新编辑', () => {
  const t = loadPage();
  Object.assign(t.page.data, { editingTopicId: '501', editIsDraft: true, editLoaded: true });
  t.page._buildSubmitPayload = () => ({ name: t.page.data.formData.name, configVersion: 2, chapters: [] });
  t.page.saveServerDraft(); t.sent[0].fail({});
  t.page.data.formData.name = '新修改'; t.page._persistDraftEnvelope();
  t.page.saveServerDraft(); assert.equal(t.sent[1].url, '/api/topic/author-edit-detail');
  t.sent[1].success({ code: 200, data: { ...detail({ topic: { configVersion: 2 } }), chapters: [] } });
  assert.equal(t.page._serverDraftAttempt.status, 'UNKNOWN');
  t.page.saveServerDraft(); assert.equal(t.sent[2].url, '/api/topic/author-edit-detail');
  t.sent[2].success({ code: 200, data: { ...detail({ topic: { configVersion: 3 } }), chapters: [] } });
  assert.equal(t.page._serverDraftAttempt, null);
  assert.equal(t.page.data.formData.name, '新修改');
  assert.equal(t.sent.length, 3);
});

test('W8：服务端5xx或408响应均保留保存未知态，4xx确定拒绝可修正后保存', () => {
  for (const code of [500, 503, 408, 400, 409, 422]) {
    const t = loadPage(); t.page.saveServerDraft(); t.sent[0].success({ code, msg: 'request outcome' });
    assert.equal(!!t.page._serverDraftAttempt, [500, 503, 408].includes(code), String(code));
    assert.equal(t.page.data.themeSaveState, [500, 503, 408].includes(code) ? 'unknown' : 'failed');
  }
});

test('W8：隐藏后再显示，旧保存/回读/发布回包不得覆盖页面或删除冻结记录', () => {
  for (const phase of ['save', 'readback', 'publish']) {
    const t = loadPage(); t.page._publishServerDraft(payload);
    if (phase !== 'save') t.sent[0].success({ code: 200, data: 501 });
    if (phase === 'publish') t.sent[1].success({ code: 200, data: detail() });
    const old = t.sent.at(-1);
    t.page.onHide(); t.page._hidden = false;
    const before = JSON.stringify({ data: t.page.data, stored: t.stored });
    old.success({ code: 200, data: phase === 'save' ? 501 : phase === 'readback' ? detail()
      : { topicId: 501, published: true } });
    assert.equal(JSON.stringify({ data: t.page.data, stored: t.stored }), before, phase);
  }
});
test('W8：损坏的未知保存信封不抛错、不发新写入', () => {
  const t = loadPage(); t.page._serverDraftAttempt = { status: 'UNKNOWN', topicId: '501' };
  assert.doesNotThrow(() => t.page.saveServerDraft());
  assert.equal(t.sent.length, 0);
  assert.equal(t.page._serverDraftAttempt.status, 'UNKNOWN');
});

test('W8：重开读取到另一版本也不能通过选择云端稿清除原UNKNOWN', () => {
  const t = loadPage();
  Object.assign(t.page.data, { editingTopicId: '501', editLoaded: true, editIsDraft: true, baseRevision: 'old' });
  t.page._publishAttempt = { status: 'UNKNOWN', kind: 'SERVER_DRAFT', topicId: '501' };
  t.page.data.formData.name = '尚未确认的本机内容';
  t.page._persistDraftEnvelope();
  const stored = JSON.stringify(t.stored);
  t.page._ensureLocalIds = () => {}; t.page._materializeCityStoryChapters = () => {}; t.page.updateAllStatistics = () => {};
  t.page._finishEditingDraftBootstrap(detail({ topic: { updateTime: 'new' } }));
  assert.equal(t.page.data.formData.name, '尚未确认的本机内容');
  assert.equal(t.page._publishAttempt.status, 'UNKNOWN');
  assert.equal(t.page.data.themeSaveState, 'unknown');
  assert.equal(JSON.stringify(t.stored), stored, '不得删原冻结记录');
});

test('W8：成功回包缺主题号必须保留原UNKNOWN保存输入', () => {
  for (const receipt of [undefined, null, {}, true, '', 0, -1, 1.5, '9007199254740993']) {
    const t = loadPage();
    t.page.saveServerDraft();
    const original = JSON.parse(JSON.stringify(t.page._serverDraftAttempt));
    t.sent[0].success({ code: 200, data: receipt });
    assert.deepEqual(t.page._serverDraftAttempt, original, String(receipt));
    assert.equal(t.page.data.themeSaveState, 'unknown');
    assert.equal(t.sent.length, 1);
  }
});

test('W8：后来编辑不合法不应阻止读取原UNKNOWN保存', () => {
  const t = loadPage();
  Object.assign(t.page.data, { editingTopicId: '501', editIsDraft: true, editLoaded: true });
  t.page.saveServerDraft(); t.sent[0].fail({});
  t.page.data.formData.name = '';
  t.page._buildSubmitPayload = () => { throw Error('不应重新校验后来编辑'); };
  t.page.saveServerDraft();
  assert.equal(t.sent[1].url, '/api/topic/author-edit-detail');
  assert.equal(t.sent.length, 2);
  assert.equal(t.page.data.formData.name, '');
});

test('N5：保存在途新增未完成规则表单，正向回读也不能清掉本机输入',()=>{
 const t=loadPage(); t.page.saveServerDraft();
 t.page._routeRuleDrafts={a:{edge:{conditions:[],effects:[{op:'GRANT_XP',value:''}]}}};
 t.page._persistDraftEnvelope();
 t.sent[0].success({code:200,data:{topicId:501}});t.sent[1].success({code:200,data:detail()});
 assert.equal(t.page.applied.length,0);
 assert.equal(t.page.data.themeSaveState,'local');
 assert.ok(Object.values(t.stored).some(v=>v && v.editorMeta && v.editorMeta.routeRuleDrafts.a));
});
