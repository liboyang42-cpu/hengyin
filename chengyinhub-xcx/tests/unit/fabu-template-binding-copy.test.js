// 发布页:公共玩法采用后再绑定(复核 A11)+ 模式切换复制保留主题规则(复核 A09)。
//
// 承重不变量:
//  - 公共玩法库的 id 是库 id,不是会员模板 id。选它必须先调 /api/template/adopt-draft,
//    用返回的会员草稿 id 绑节点;采用结果不确定时不绑、不自动重发。
//  - 「我的模板」与公共库 id 可能重号,选中态必须按来源区分。
//  - _buildCopiedDraft → applyAiDraft 往返后,journeyRules/themeRuntimeConfig/journeyStory/completeRuleJson
//    与序章/结局章标记不丢。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const binding = require('../../pages/publish/utils/publish/template-binding.js');

const PAGE = '../../pages/publish/fabu/index.js';

let sent = [];
let storage = {};
let toasts = [];
let actorId = 42;
let sessionRevision = 0;
let authorization = 'fixture-actor-A';
const session = { getRevision: () => sessionRevision };

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  chooseImage: () => {},
  getUserID: () => actorId,
  getSession: () => session,
  getAuthorization: () => authorization,
  getNickname: () => '测试创作者',
  getAvatar: () => '',
  getUserInfo: () => null,
  getToken: () => '',
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
});
global.wx = {
  getStorageSync: (k) => storage[k],
  setStorageSync: (k, v) => { storage[k] = v; },
  removeStorageSync: (k) => { delete storage[k]; },
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast: (o) => { toasts.push(o && o.title); }, showLoading: () => {}, hideLoading: () => {},
  showModal: (options) => { if (options.success) options.success({ confirm: true }); },
  navigateTo: () => {}, redirectTo: () => {},
  createMapContext: () => ({ getCenterLocation: () => {} }),
  pageScrollTo: () => {}, nextTick: (f) => f(),
};

let pageConfig = null;
global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  actorId = 42;
  sessionRevision = 0;
  authorization = 'fixture-actor-A';
  sent = [];
  storage = {};
  toasts = [];
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  delete require.cache[require.resolve('../../utils/loading.js')];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      const parts = k.replace(/\[(\d+)\]/g, '.$1').split('.');
      let o = inst.data;
      for (let i = 0; i < parts.length - 1; i++) o = o[parts[i]];
      o[parts[parts.length - 1]] = patch[k];
    });
    if (cb) cb();
  };
  return inst;
}

const tap = (item, source) => ({ currentTarget: { dataset: { item, source } } });
const libraryRow = { id: 7, title: '公共猜谜', imgUrl: 'https://x/p.png', advancedConfigJson: '' };

function confirmAdoptionSource(begin) {
  const source = sent.slice(begin).find(r => r.url === '/api/template/adoption-source');
  assert.ok(source, '必须先确认当前公共来源版本');
  source.success({ code: 200, data: {
    originalTemplateId: source.data.originalTemplateId, sourceVersion: 'a'.repeat(64),
  } });
  const adopt = sent.slice(begin).find(r => r.url === '/api/template/adopt-draft');
  assert.ok(adopt, '来源确认后才提交原采用命令');
  const command = JSON.parse(adopt.data);
  assert.equal(command.originalTemplateId, source.data.originalTemplateId);
  assert.equal(command.sourceVersion, 'a'.repeat(64));
  assert.ok(command.requestId);
  return { adopt, command };
}

function completedAdoption(command, draftId) {
  return { code: 200, data: { requestId: command.requestId,
    originalTemplateId: command.originalTemplateId, sourceVersion: command.sourceVersion,
    status: 'COMPLETED', draftId } };
}

test('终局表决采用与重进：无资源槽也保存结局章映射，模式副本提示重新选择', () => {
  const p = makePage(); p._templateSupportReason = () => '';
  const template = { id: 88, title: '共同决定', advancedConfigJson: JSON.stringify({ interactionTools: {
    decidesEnding: true, plans: [{ planKey: 'leave', title: '离开现场' }] } }) };
  p.data.nodesForm = { id: 11, _localId: 'n1', name: '表决站' };
  p.data.formData.chapters = [{ name: '行程', nodes: [p.data.nodesForm] },
    { id: 91, name: '离开现场', ending: { fallback: true }, nodes: [] }];
  p.data.formData.journeyStory = JSON.stringify({ ending: { endings: [{ code: 'default', title: '离开现场', chapterId: 91, fallback: true }] } });
  assert.equal(p._bindNodeTemplate(template), true);
  assert.equal(p.data.nodePlanEndingRows.length, 1);
  p.onNodePlanEnding({ currentTarget: { dataset: { index: 0 } }, detail: { value: '91' } });
  p._commitNodeTemplateBinding(p.data.nodesForm);
  const saved = JSON.parse(p._runtimePayload()).templateBindings['11'];
  assert.deepEqual(saved.planEndings, { leave: '91' }); assert.equal(saved.slotDigest, binding.slotDigest([]));
  p._openNodeBinding(p.data.nodesForm); assert.equal(p.data.nodePlanEndingRows[0].targetLabel, '离开现场');
  p.setEditorState = () => {}; p.showEditNodes = () => {};
  p.onEditBoundTemplate = () => { assert.fail('结局选择错误须留在节点采用处'); };
  p.locatePublishIssue({ currentTarget: { dataset: { field: 'templateTool:11:vote:leave', label: '请选择结局章' } } });
  assert.equal(p.data.nodePlanEndingError, '请选择结局章');
  p.data.formData.chapters[0].nodes = [Object.assign({}, p.data.nodesForm)];
  assert.deepEqual(p._nodePlanEndingIssues(), []);
  const draft = p._buildCopiedDraft(2, false), copied = JSON.parse(draft.formData.themeRuntimeConfig);
  assert.equal(Object.hasOwn(copied.templateBindings['client:n1'], 'planEndings'), false);
  assert.match(draft.copyReferenceNotice, /终局表决的结局章/);
  assert.equal(draft.formData.journeyStory, p.data.formData.journeyStory);
  assert.deepEqual(JSON.parse(p._runtimePayload()).templateBindings['11'], saved);
});

function sharedPage() {
  const p = makePage();
  const config = JSON.stringify({ interactionRules: { resourceSlots: [
    { key: 'value_1', kind: 'VALUE', label: '恢复的数值', required: true }
  ] } });
  const source = { id: 31, title: '共用玩法', advancedConfigJson: config,
    rewardRulesJson: '{"reward":1}', merchantGuide: '请到柜台', sensorType: 'steps',
    sensorConfig: '{"goal":10}', storyImg: 'story.png' };
  const current = { _localId: 'n1', name: '第一站', templateId: 31, templateInfo: source };
  p.data.nodesForm = Object.assign({}, current);
  p.data.formData.chapters = [{ name: '第一章', nodes: [current,
    { _localId: 'n2', name: '第二站', templateId: 31 }] }];
  p.data.formData.journeyRules = JSON.stringify({ stateEnabled: true,
    attributes: [{ key: 'counter.focus', name: '专注', enabled: true }] });
  p.data.formData.themeRuntimeConfig = JSON.stringify({ templateBindings: {
    'client:n1': { templateId: 31, values: { value_1: 'counter.focus' }, items: {},
      slotDigest: binding.slotDigest(binding.slotsOf(source)) }
  } });
  p.getStoryDraftList = () => {};
  p._openNodeBinding(p.data.nodesForm);
  return { p, source };
}

test('共用玩法列出节点，另存副本传完整作者字段且成功后仅重绑本站', () => {
  const { p, source } = sharedPage();
  assert.deepEqual(p.data.nodeSharedSites.map(s => s.label), ['第一章 · 第一站', '第一章 · 第二站']);
  p.onCopyBoundTemplate();
  const detail = sent.find(r => r.url === '/api/template/author-myinfo');
  assert.equal(p.data.nodesForm.templateId, 31);
  detail.success({ code: '200', data: source });
  const draft = sent.find(r => r.url === '/api/template/draft');
  const payload = JSON.parse(draft.data);
  assert.equal(Object.hasOwn(payload, 'id'), false);
  for (const key of ['rewardRulesJson', 'merchantGuide', 'sensorType', 'sensorConfig', 'storyImg', 'advancedConfigJson'])
    assert.equal(payload[key], source[key]);
  assert.equal(p.data.nodesForm.templateId, 31);
  draft.success({ code: '200', data: 88 });
  assert.equal(p.data.nodesForm.templateId, 88);
  assert.equal(p.data.formData.chapters[0].nodes[1].templateId, 31);
  assert.deepEqual(p._nodeBindingDraft.values, { value_1: 'counter.focus' });
  assert.equal(p._nodeBindingDraft.templateId, 88);
  assert.equal(p._nodeBindingDraft.slotDigest, binding.slotDigest(binding.slotsOf(source)));
  assert.equal(p.data.nodeSharedSites.length, 1);
});

test('会员内容另存保留服务端原interactionType，不按validationMethod猜玩法语义', () => {
  const { p, source } = sharedPage();
  source.interactionType = 'city_story_card';
  p.onCopyBoundTemplate();
  sent.find(r => r.url === '/api/template/author-myinfo').success({ code: 200, data: source });
  const payload = JSON.parse(sent.find(r => r.url === '/api/template/draft').data);
  assert.equal(payload.interactionType, source.interactionType);
  assert.equal(payload.advancedConfigJson, source.advancedConfigJson);
  assert.equal(Object.hasOwn(payload, 'id'), false);
  assert.equal(p.data.nodesForm.templateId, 31, '写入确认前不改变原绑定');
});

test('另存失败或回包不确定保留原绑定且不自动重试', () => {
  for (const kind of ['failure', 'uncertain']) {
    sent.length = 0;
    const { p, source } = sharedPage();
    p.onCopyBoundTemplate();
    sent.find(r => r.url === '/api/template/author-myinfo').success({ code: '200', data: source });
    const draft = sent.find(r => r.url === '/api/template/draft');
    if (kind === 'failure') draft.success({ code: 500, msg: '保存失败' });
    else draft.fail({}, 0);
    assert.equal(p.data.nodesForm.templateId, 31);
    assert.equal(p._nodeBindingDraft.templateId, 31);
    assert.equal(p.data.formData.chapters[0].nodes[1].templateId, 31);
    assert.equal(sent.filter(r => r.url === '/api/template/draft').length, 1);
    if (kind === 'uncertain') {
      assert.match(p.data.nodeCopyError, /我的玩法/);
      p.onCopyBoundTemplate();
      assert.equal(sent.filter(r => r.url === '/api/template/draft').length, 1);
    }
  }
});

test('另存回读资源槽类型变化时重新校验，不沿用旧数值对应', () => {
  const { p, source } = sharedPage();
  p.onCopyBoundTemplate();
  const updated = Object.assign({}, source, { advancedConfigJson: JSON.stringify({ interactionRules: {
    resourceSlots: [{ key: 'value_1', kind: 'ITEM', label: '支付的物资', required: true }]
  } }) });
  sent.find(r => r.url === '/api/template/author-myinfo').success({ code: '200', data: updated });
  sent.find(r => r.url === '/api/template/draft').success({ code: '200', data: 89 });
  assert.equal(p.data.nodesForm.templateId, 89);
  assert.deepEqual(p._nodeBindingDraft.values, {});
  assert.deepEqual(p._nodeBindingDraft.items, {});
  assert.equal(p._nodeBindingDraft.slotDigest, binding.slotDigest(binding.slotsOf(updated)));
});

test('修改原模板入口明确沿用会员草稿 id 并返回主题编辑器', () => {
  const { p } = sharedPage();
  let url = '';
  const navigateTo = wx.navigateTo;
  wx.navigateTo = options => { url = options.url; };
  p.onEditBoundTemplate();
  wx.navigateTo = navigateTo;
  assert.equal(url, '/pages/publish/temp/index?from=fabu&id=31&scope=my&runtimeEndings=%5B%5D');
  assert.equal(p._editingBoundTemplateId, '31');
});

test('选公共玩法先采用为我的草稿,再用会员模板 id 绑节点', () => {
  const p = makePage();
  p.data.nodesForm = { name: '', templateId: 0 };
  p.selectTemp(tap(libraryRow, 'public'));
  p.confirmTemp();

  sent.find(r=>r.url==='/api/template/adoption-source').success({code:200,data:{originalTemplateId:'7',sourceVersion:'a'.repeat(64)}});
  const adopt = sent.find((r) => r.url === '/api/template/adopt-draft');
  assert.ok(adopt, '必须调用采用接口');
  assert.equal(JSON.parse(adopt.data).originalTemplateId, '7');
  assert.equal(p.data.nodesForm.templateId, 0, '采用返回前不得把库 id 写进节点');

  const command=JSON.parse(adopt.data);
  adopt.success({code:200,data:{requestId:command.requestId,originalTemplateId:'7',sourceVersion:'a'.repeat(64),status:'COMPLETED',draftId:'9001'}});
  assert.equal(p.data.nodesForm.templateId, 0, '会员草稿详情刷新前不得使用公共库投影');
  const detail = sent.find((r) => r.url === '/api/template/adoption-detail');
  assert.equal(detail.data.requestId, command.requestId);
  detail.success({ code: '200', data: { id: 9001, title: '公共猜谜', advancedConfigJson: '{}' } });
  assert.equal(p.data.nodesForm.templateId, 9001, '绑定的是会员草稿 id');
  assert.equal(p.data.nodesForm.templateInfo.title, '公共猜谜');
  assert.equal(p.data.nodeSheetView, 'detail');
});

test('采用结果不确定时不绑定也不自动重发', () => {
  const p = makePage();
  p.data.nodesForm = { name: '', templateId: 0 };
  p.selectTemp(tap(libraryRow, 'public'));
  p.confirmTemp();
  p.confirmTemp(); // 在途重复点
  sent.find(r=>r.url==='/api/template/adoption-source').success({code:200,data:{originalTemplateId:'7',sourceVersion:'a'.repeat(64)}});
  const adopts = sent.filter((r) => r.url === '/api/template/adopt-draft');
  assert.equal(adopts.length, 1, '在途时不得重复采用');
  adopts[0].fail({}, 0);
  assert.equal(p.data.nodesForm.templateId, 0);
  assert.equal(sent.filter((r) => r.url === '/api/template/adopt-draft').length, 1, '失败后不自动重发');
});

test('我的模板直接绑定;与公共库重号时选中态按来源区分', () => {
  const p = makePage();
  p.data.nodesForm = { name: '', templateId: 0 };
  p.selectTemp(tap(libraryRow, 'public'));
  p.selectTemp(tap({ id: 7, title: '我的同号模板' }, 'mine'));
  assert.equal(p.data.selectedTempSource, 'mine', '同 id 不同来源应切换选中，而不是取消');
  p.confirmTemp();
  assert.equal(sent.filter((r) => r.url === '/api/template/adopt-draft').length, 0);
  sent.find((r) => r.url === '/api/template/author-myinfo').success({ code: '200',
    data: { id: 7, title: '我的同号模板', advancedConfigJson: '{}' } });
  assert.equal(p.data.nodesForm.templateId, 7);
  assert.equal(p.data.nodesForm.templateInfo.title, '我的同号模板');
});

test('玩法详情异步回读时切换选择，旧回包不能覆盖新模板', () => {
  const p = makePage();
  p.data.nodesForm = { name: '', templateId: 0 };
  p.selectTemp(tap({ id: 31, title: '旧玩法' }, 'mine'));
  p.confirmTemp();
  const oldRequest = sent.find(r => r.url === '/api/template/author-myinfo');
  p.selectTemp(tap({ id: 32, title: '新玩法' }, 'mine'));
  p.confirmTemp();
  const newRequest = sent.filter(r => r.url === '/api/template/author-myinfo')[1];
  oldRequest.success({ code: '200', data: { id: 31, title: '旧玩法', advancedConfigJson: '{}' } });
  assert.equal(p.data.nodesForm.templateId, 0);
  newRequest.success({ code: '200', data: { id: 32, title: '新玩法', advancedConfigJson: '{}' } });
  assert.equal(p.data.nodesForm.templateId, 32);
});

test('模式切换复制保留主题规则与序章/结局章标记', () => {
  const src = makePage();
  Object.assign(src.data.formData, {
    name: '复制源',
    journeyRules: '{"schemaVersion":1,"stateEnabled":true}',
    themeRuntimeConfig: '{"schemaVersion":1,"capacity":14}',
    journeyStory: '{"ending":{"endings":[]}}',
    completeRuleJson: '{"mode":"ALL"}',
    chapters: [
      { name: '序章', opening: true, nodes: [] },
      { name: '第一章', nodes: [] },
      { name: '结局', ending: { title: '结局一' }, nodes: [] },
    ],
  });
  const draft = src._buildCopiedDraft(2, false);

  const dst = makePage();
  dst.applyAiDraft({ from: 'ai', ...draft });
  const f = dst.data.formData;
  assert.equal(f.journeyRules, '{"schemaVersion":1,"stateEnabled":true}');
  assert.equal(f.themeRuntimeConfig, '{"schemaVersion":1,"capacity":14}');
  assert.equal(f.journeyStory, '{"ending":{"endings":[]}}');
  assert.equal(f.completeRuleJson, '{"mode":"ALL"}');
  assert.equal(f.chapters[0].opening, true);
  assert.deepEqual(f.chapters[2].ending, { title: '结局一' });
});

test('复制绑定使用稳定 client 键，移除源 id 和仅含归零挑战的配置并集中提示', () => {
  const src = makePage();
  const runtime = {
    chapters: [{ index: 0, completion: { type: 'ANY_N', quantity: 1, overflowSlot: 'shared' }, exclusiveGroups: [[11, 12]], extraNodes: [{ nodeId: 13, slot: 'shared' }] }],
    gameplayRuntime: { survival: { zeroHpPolicy: 'CHALLENGE', zeroChallenge: { challengeNodeId: 11, alternativeNodeIds: [12], restoreTo: 1, limit: 2 } } },
    templateBindings: { 11: { slots: { supply: 'item' } }, 12: { slots: { supply: 'item2' } }, 13: { slots: {} }, 99: { slots: {} } },
  };
  src.data.formData.themeRuntimeConfig = JSON.stringify(runtime);
  src.data.formData.chapters = [{ name: '第一章', nodes: [
    { id: 11, _localId: 'local-a', clientNodeKey: 'stable-a' },
    { id: 12, _localId: 'local-b' },
    { id: 13 },
  ] }];
  const copied = src._buildCopiedDraft(2, false);
  const nodes = copied.formData.chapters[0].nodes;
  const saved = JSON.parse(copied.formData.themeRuntimeConfig);
  // 2026-10-12:互斥组与额外节点(共用额度关系)不再丢,换成新草稿的 client 引用,首次保存由服务端换回真实编号
  assert.deepEqual(saved.chapters, [{ index: 0, completion: runtime.chapters[0].completion,
    exclusiveGroups: [['client:stable-a', 'client:local-b']], extraNodes: [{ nodeId: 'client:' + nodes[2].clientNodeKey, slot: 'shared' }] }]);
  assert.equal(Object.hasOwn(saved, 'gameplayRuntime'), false);
  assert.deepEqual(saved.templateBindings['client:stable-a'], runtime.templateBindings[11]);
  assert.deepEqual(saved.templateBindings['client:local-b'], runtime.templateBindings[12]);
  assert.deepEqual(saved.templateBindings['client:' + nodes[2].clientNodeKey], runtime.templateBindings[13]);
  assert.ok(nodes.every(node => !Object.hasOwn(node, 'id') && node.clientNodeKey));
  assert.equal(Object.keys(saved.templateBindings).length, 3);
  assert.equal(copied.copyReferenceNotice, '复制后需要重新选择：归零挑战需重新选择挑战节点、节点玩法资源绑定');
  assert.deepEqual(JSON.parse(src.data.formData.themeRuntimeConfig), runtime);
});

test('复制成新草稿：互斥组与额外节点(含共用额度)换成 client 引用保留，不再要求重选', () => {
  const p = makePage();
  p.data.formData = Object.assign({}, p.data.formData, { productType: 1, tickets: [] });
  p.data.formData.chapters = [{ name: '一', nodes: [{ id: 101, name: '甲', _localId: 'a' }, { id: 102, name: '乙', _localId: 'b' }, { id: 103, name: '丙', _localId: 'c' }] }];
  p.data.formData.themeRuntimeConfig = JSON.stringify({ chapters: [{ index: 0, hpCost: 0, luckCost: 0, grants: [], eggs: [], exclusiveGroups: [[102, 103]], extraNodes: [{ nodeId: 101, slot: 'shared_x' }] }] });
  const copied = p._buildCopiedDraft(1, false);
  const ch = JSON.parse(copied.formData.themeRuntimeConfig).chapters[0];
  assert.deepEqual(ch.exclusiveGroups, [['client:b', 'client:c']]);
  assert.deepEqual(ch.extraNodes, [{ nodeId: 'client:a', slot: 'shared_x' }]);
  assert.doesNotMatch(copied.copyReferenceNotice, /互斥|额外/);
});


test('删除节点：从互斥组/额外节点/运行任务里明确移出并说明，不足两站的互斥组整组去掉；不改指别的节点', () => {
  const p = makePage();
  p.data.formData = Object.assign({}, p.data.formData, { productType: 1, chapters: [{ name: '一', nodes: [{ id: 116 }, { id: 117 }, { id: 118 }] }] });
  p.data.formData.themeRuntimeConfig = JSON.stringify({
    chapters: [{ index: 3, hpCost: 0, luckCost: 0, grants: [], eggs: [], exclusiveGroups: [[116, 117, 118]] },
      { index: 5, hpCost: 0, luckCost: 0, grants: [], eggs: [], exclusiveGroups: [[121, 118]], extraNodes: [{ nodeId: 118, slot: 'shared' }, { nodeId: 123, slot: 'shared' }] }],
    gameplayRuntime: { runtimeScope: 'TEAM', quests: [{ questKey: 'node_118', nodeId: 118, actKey: 'a' }, { questKey: 'node_116', nodeId: 116, actKey: 'a', prerequisites: ['node_118'], facilityKey: 'tpl_node_118' }],
      acts: [{ actKey: 'a', questKeys: ['node_116', 'node_118'] }],
      facilities: [{ facilityKey: 'tpl_node_118', operationNodeId: 118 }, { facilityKey: 'facility_1', operationNodeId: 116 }],
      plans: [{ endingKey: 'plan_118' }, { endingKey: 'plan_keep' }],
      templateTools: { nodes: [{ nodeId: 118, planKeys: ['plan_118'] }, { nodeId: 116, planKeys: [] }] } },
  });
  const refs = p._removeNodeRuleRefs({ id: 118 });
  const c = JSON.parse(p.data.formData.themeRuntimeConfig);
  assert.deepEqual(c.chapters[0].exclusiveGroups, [[116, 117]]);
  assert.equal(Object.hasOwn(c.chapters[1], 'exclusiveGroups'), false, '只剩一站的互斥组整组去掉');
  assert.deepEqual(c.chapters[1].extraNodes, [{ nodeId: 123, slot: 'shared' }], '共用额度里的其他节点不受影响');
  assert.deepEqual(c.gameplayRuntime.quests, [{ questKey: 'node_116', nodeId: 116, actKey: 'a', prerequisites: [] }]);
  assert.deepEqual(c.gameplayRuntime.facilities, [{ facilityKey: 'facility_1', operationNodeId: 116 }], '该节点生成/操作的设施移出，别的保留');
  assert.deepEqual(c.gameplayRuntime.plans, [{ endingKey: 'plan_keep' }]);
  assert.deepEqual(c.gameplayRuntime.templateTools.nodes, [{ nodeId: 116, planKeys: [] }]);
  assert.deepEqual(c.gameplayRuntime.acts[0].questKeys, ['node_116']);
  assert.match(refs.note, /第4章互斥选项、第6章互斥选项、第6章额外节点、该节点玩法生成的表决方案、该节点的设施/);
  assert.equal(refs.before.length, 2);
  assert.equal(refs.gameBefore.facilities.length, 2, '撤销用的原运行结构完整保留');
  assert.equal(p._removeNodeRuleRefs({ id: 999 }), null, '没被引用的节点不改配置');
});

const libraryPage = (start, size) => Array.from({ length: size }, (_, i) => ({
  id: start + i, title: '可采用内容' + (start + i), advancedConfigJson: '{}',
}));

test('完整公共库读取未进home推荐切片的内容，翻完所有页才显示ready且不产生采用写入', () => {
  const p = makePage(); p._refreshSupportMatrix = () => {};
  p.getPublicTempList();
  const first = sent.at(-1);
  assert.equal(first.url, '/api/template/list');
  assert.deepEqual(first.data, { pageNum: 1, pageSize: 100 });
  first.success({ code: 200, data: { rows: libraryPage(1, 100), total: 101 } });
  assert.equal(p.data.publicTempState, 'loading');
  assert.deepEqual(p.data.publicTempList, []);
  const second = sent.at(-1);
  assert.equal(second.url, '/api/template/list');
  assert.deepEqual(second.data, { pageNum: 2, pageSize: 100 });
  second.success({ code: 200, data: { rows: libraryPage(701, 1), total: 101 } });
  assert.equal(p.data.publicTempState, 'ready');
  assert.equal(p.data.publicTempList.length, 101);
  assert.equal(p.data.publicTempList.at(-1).id, 701, '非home推荐项也可被选择');
  assert.equal(sent.some(r => /homeData|adopt-draft|\/draft$/.test(r.url)), false);
});

test('公共库真实空结果与分页失败分开，已取部分不能伪装完整或自动采用', () => {
  const p = makePage(); p._refreshSupportMatrix = () => {};
  p.getPublicTempList();
  sent.at(-1).success({ code: 200, data: { rows: [], total: 0 } });
  assert.equal(p.data.publicTempState, 'ready');
  assert.deepEqual(p.data.publicTempList, []);
  p.getPublicTempList();
  sent.at(-1).success({ code: 200, data: { rows: libraryPage(1, 100), total: 101 } });
  sent.at(-1).fail({});
  assert.equal(p.data.publicTempState, 'error');
  assert.deepEqual(p.data.publicTempList, []);
  assert.equal(sent.some(r => r.url === '/api/template/adopt-draft'), false);
});

test('公共库缺总数、重复页与总数前空页明确失败，不吞成空库或截断结果', () => {
  for (const bad of [
    { rows: [], total: 'bad' }, { rows: [] }, { rows: libraryPage(1, 1), total: 2 },
  ]) {
    const p = makePage(); p._refreshSupportMatrix = () => {};
    p.getPublicTempList();
    sent.at(-1).success({ code: 200, data: bad });
    if (bad.total === 2) sent.at(-1).success({ code: 200, data: { rows: libraryPage(1, 1), total: 2 } });
    assert.equal(p.data.publicTempState, 'error');
    assert.deepEqual(p.data.publicTempList, []);
  }
  const p = makePage(); p._refreshSupportMatrix = () => {};
  p.getPublicTempList();
  sent.at(-1).success({ code: 200, data: { rows: libraryPage(1, 1), total: 2 } });
  sent.at(-1).success({ code: 200, data: { rows: [], total: 2 } });
  assert.equal(p.data.publicTempState, 'error');
  assert.deepEqual(p.data.publicTempList, []);
});

test('公共库回包绑定账号、经营scope和页面代次，旧读取不能覆盖新读取', () => {
  for (const mutate of [
    p => { actorId = 43; }, p => { p.data.operationScope = 'MERCHANT'; },
    p => { p._loginContinuationEpoch = (p._loginContinuationEpoch || 0) + 1; },
    p => { p._unloaded = true; },
  ]) {
    actorId = 42;
    const p = makePage(); p._refreshSupportMatrix = () => {};
    p.getPublicTempList(); const old = sent.at(-1);
    mutate(p);
    old.success({ code: 200, data: { rows: libraryPage(1, 1), total: 1 } });
    assert.deepEqual(p.data.publicTempList, []);
    old.fail({});
    assert.equal(p.data.publicTempState, 'loading', '旧回包连错误态也不能覆盖新归属');
  }
  actorId = 42;
  const p = makePage(); p._refreshSupportMatrix = () => {};
  p.getPublicTempList(); const old = sent.at(-1);
  p.getPublicTempList(); const fresh = sent.at(-1);
  fresh.success({ code: 200, data: { rows: libraryPage(9, 1), total: 1 } });
  old.success({ code: 200, data: { rows: libraryPage(1, 1), total: 1 } });
  assert.deepEqual(p.data.publicTempList.map(r => r.id), [9]);
});

test('采用已确认但个人详情未回读时重复确认不创建第二份草稿', () => {
  const p = makePage(); p.data.nodesForm = { _localId: 'original', name: '', templateId: 0 };
  p.selectTemp(tap(libraryRow, 'public')); p.confirmTemp();
  const { adopt, command } = confirmAdoptionSource(0);
  const receipt = completedAdoption(command, '9001');
  adopt.success(receipt);
  const firstDetail = sent.find(r => r.url === '/api/template/adoption-detail');
  assert.equal(firstDetail.data.requestId, command.requestId);
  assert.equal(p.data.nodesForm.templateId, 0, '详情未确认时保留原绑定');
  p.confirmTemp();
  assert.equal(sent.filter(r => r.url === '/api/template/adopt-draft').length, 1);
  assert.equal(sent.filter(r => r.url === '/api/template/adoption-detail').length, 1);
  firstDetail.fail({});
  p.confirmTemp();
  const recovery = sent.find(r => r.url === '/api/template/adoption-receipt');
  assert.equal(recovery.data.requestId, command.requestId, '失败后只查询原采用身份');
  recovery.success(receipt);
  const detail = sent.filter(r => r.url === '/api/template/adoption-detail').at(-1);
  assert.equal(detail.data.requestId, command.requestId);
  detail.success({ code: 200,
    data: { id: 9001, title: '原内容采用稿', advancedConfigJson: '{}' } });
  assert.equal(p.data.nodesForm.templateId, 9001);
  assert.equal(sent.filter(r => r.url === '/api/template/adopt-draft').length, 1);
  assert.equal(sent.filter(r => r.url === '/api/template/adoption-source').length, 1);
});

test('公共采用写回迟到时换节点不触发个人详情或把新节点绑定成旧内容', () => {
  const p = makePage(); p.data.nodesForm = { _localId: 'original', name: '', templateId: 0 };
  p.selectTemp(tap(libraryRow, 'public')); p.confirmTemp();
  const { adopt, command } = confirmAdoptionSource(0);
  p.data.nodesForm = { _localId: 'next', name: '新节点', templateId: 66 };
  adopt.success(completedAdoption(command, '9001'));
  assert.equal(sent.some(r => r.url === '/api/template/myinfo'), false);
  assert.equal(sent.some(r => r.url === '/api/template/author-myinfo'), false);
  assert.equal(sent.some(r => r.url === '/api/template/adoption-detail'), false);
  assert.equal(p.data.nodesForm.templateId, 66);
  assert.equal(sent.filter(r => r.url === '/api/template/adopt-draft').length, 1);
});

test('个人采用详情回读绑定原节点、账号、主题、scope及页面代次，不改原草稿身份', () => {
  for (const mutate of [
    p => { p.data.nodesForm = { _localId: 'other', templateId: 66 }; },
    p => { p.data.nodesForm._localId = 'changed-in-place'; },
    p => { actorId = 43; }, p => { p.data.operationScope = 'MERCHANT'; },
    p => { p.data.editingTopicId = 'other-topic'; },
    p => { p._loginContinuationEpoch = (p._loginContinuationEpoch || 0) + 1; },
    p => { p._unloaded = true; },
  ]) {
    actorId = 42; storage = {}; const begin = sent.length;
    const p = makePage(); p.data.nodesForm = { _localId: 'original', templateId: 0, name: '' };
    p.selectTemp(tap(libraryRow, 'public')); p.confirmTemp();
    const { adopt, command } = confirmAdoptionSource(begin);
    adopt.success(completedAdoption(command, '9001'));
    const detail = sent.slice(begin).find(r => r.url === '/api/template/adoption-detail');
    assert.equal(detail.data.requestId, command.requestId);
    assert.equal(storage['author:template-adoption:v1:42::7'].draftId, '9001');
    mutate(p); const expected = p.data.nodesForm.templateId;
    detail.success({ code: 200, data: { id: 9001, title: '旧内容', advancedConfigJson: '{}' } });
    assert.equal(p.data.nodesForm.templateId, expected);
    assert.equal(storage['author:template-adoption:v1:42::7'].draftId, '9001', '迟到详情不清理原已确认采用身份');
    assert.equal(sent.slice(begin).filter(r => r.url === '/api/template/adopt-draft').length, 1);
  }
});

test('我的模板补读也受原节点和账号围栏，不仅比较选中模板id', () => {
  for (const mutate of [
    p => { p.data.nodesForm = { _localId: 'other', templateId: 66 }; },
    p => { actorId = 43; },
  ]) {
    actorId = 42; const begin = sent.length;
    const p = makePage(); p.data.nodesForm = { _localId: 'original', templateId: 0, name: '' };
    p.selectTemp(tap({ id: 31, title: '我的模板' }, 'mine')); p.confirmTemp();
    const detail = sent.slice(begin).find(r => r.url === '/api/template/author-myinfo');
    mutate(p); const expected = p.data.nodesForm.templateId;
    detail.success({ code: 200, data: { id: 31, title: '旧内容', advancedConfigJson: '{}' } });
    assert.equal(p.data.nodesForm.templateId, expected);
  }
});

test('换节点后可重读同一个个人模板，旧回包不清掉新读的singleflight', () => {
  const p = makePage(); p.data.nodesForm = { _localId: 'original', templateId: 0, name: '' };
  p._bindNodeTemplate({ id: 31, title: '我的模板' });
  const old = sent.at(-1);
  p.data.nodesForm = { _localId: 'other', templateId: 0, name: '' };
  p._bindNodeTemplate({ id: 31, title: '我的模板' });
  const fresh = sent.at(-1);
  assert.notEqual(fresh, old, '同ID但不同节点不是同一次读取');
  old.success({ code: 200, data: { id: 31, title: '旧节点回读', advancedConfigJson: '{}' } });
  assert.equal(p.data.nodesForm.templateId, 0);
  assert.equal(p._loadingSelectedTemplate, '31', 'singleflight保留精确的十进制原ID');
  fresh.success({ code: 200, data: { id: 31, title: '新节点回读', advancedConfigJson: '{}' } });
  assert.equal(p.data.nodesForm.templateId, 31);
  assert.equal(p.data.nodesForm.templateInfo.title, '新节点回读');
  assert.equal(p._loadingSelectedTemplate, 0);
});

test('同一节点新建现成玩法的EventChannel可跨编辑器导航返回，迟到旧目标不能绑定', () => {
  const navigate = wx.navigateTo;
  try {
    for (const changed of [false, true]) {
      const p = makePage(); p.data.nodesForm = { _localId: 'original', templateId: 0, name: '' };
      p._nodeSupportContext = () => ({});
      let opened;
      wx.navigateTo = options => { opened = options; };
      p.onCreateGame({ currentTarget: { dataset: {} } });
      assert.ok(opened.url.startsWith('/pages/publish/temp/index?from=fabu'));
      p.onHide(); // 使用真实生命周期，子编辑器会在父页仍隐藏时发回事件
      if (changed) p.data.nodesForm = { _localId: 'other', templateId: 66, name: '' };
      opened.events.templateCreated({ id: 9091, title: '既有编辑器新内容', advancedConfigJson: '{}' });
      assert.equal(p.data.nodesForm.templateId, changed ? 66 : 9091);
      p.onShow();
      assert.equal(p.data.nodesForm.templateId, changed ? 66 : 9091);
      opened.complete();
      assert.equal(p._navigating, false);
    }
  } finally { wx.navigateTo = navigate; }
});

test('公共采用与原绑定编辑全程保留超过安全整数的来源和会员草稿字符串ID', () => {
  const sourceId = '9007199254740993', draftId = '9007199254740995';
  const p = makePage(); p.data.nodesForm = { _localId: 'original', templateId: 0, name: '' };
  p.selectTemp(tap({ ...libraryRow, id: sourceId }, 'public')); p.confirmTemp();
  const { adopt, command } = confirmAdoptionSource(0);
  assert.equal(command.originalTemplateId, sourceId);
  adopt.success(completedAdoption(command, draftId));
  const detail = sent.find(r => r.url === '/api/template/adoption-detail');
  assert.equal(detail.data.requestId, command.requestId);
  detail.success({ code: 200, data: { id: draftId, title: '原内容', advancedConfigJson: '{}' } });
  assert.equal(p.data.nodesForm.templateId, draftId);
  assert.equal(p.data.nodesForm.templateInfo.id, draftId);
  assert.equal(storage['author:template-adoption:v1:42::' + sourceId], undefined);
  let opened;
  const navigate = wx.navigateTo;
  try {
    wx.navigateTo = options => { opened = options; };
    p.onEditBoundTemplate();
    assert.ok(opened.url.includes('&id=' + draftId + '&scope=my'));
    assert.equal(p._editingBoundTemplateId, draftId);
    p.data.popChapterNodes = true;
    p.onHide(); p.onShow();
    const originalRead = sent.filter(r => r.url === '/api/template/author-myinfo').at(-1);
    assert.equal(originalRead.data.id, draftId);
    originalRead.success({ code: 200, data: { id: draftId, title: '原稿修改', advancedConfigJson: '{}' } });
    assert.equal(p.data.nodesForm.templateId, draftId, '返回编辑只刷新同一个会员原稿');
    assert.equal(p.data.nodesForm.templateInfo.title, '原稿修改');
    assert.equal(sent.filter(r => r.url === '/api/template/adopt-draft').length, 1);
    assert.equal(sent.some(r => r.url === '/api/template/draft'), false);
  } finally { wx.navigateTo = navigate; }
});

test('编辑器导航回传仍拒绝账号ABA、会话变更和原节点主题scope代次失配', () => {
  const navigate = wx.navigateTo;
  try {
    for (const mutate of [
      () => { actorId = 43; },
      () => { actorId = 43; sessionRevision++; actorId = 42; sessionRevision++; },
      () => { authorization = 'fixture-actor-B'; },
      p => { p.data.nodesForm = { _localId: 'other', templateId: 66 }; },
      p => { p.data.nodesForm._localId = 'other'; },
      p => { p.data.editingTopicId = 'other-topic'; },
      p => { p.data.operationScope = 'MERCHANT'; },
      p => { p.data.draftUuid = 'other-draft'; },
      p => { p._editLoadToken = (p._editLoadToken || 0) + 1; },
      p => { p._nodeTemplateLoadToken = (p._nodeTemplateLoadToken || 0) + 1; },
      p => { p.onUnload(); },
      p => { p.onShow(); p.onHide(); },
    ]) {
      actorId = 42; authorization = 'fixture-actor-A';
      const begin = sent.length;
      const p = makePage(); p.data.nodesForm = { _localId: 'original', templateId: 0, name: '' };
      p._nodeSupportContext = () => ({});
      let opened;
      wx.navigateTo = options => { opened = options; };
      p.onCreateGame({ currentTarget: { dataset: {} } }); p.onHide();
      mutate(p); const expected = p.data.nodesForm.templateId;
      opened.events.templateCreated({ id: '9007199254740993', title: '迟到内容', advancedConfigJson: '{}' });
      assert.equal(p.data.nodesForm.templateId, expected);
      assert.equal(p.data.nodesForm.templateInfo, undefined);
      assert.equal(sent.length, begin, '被拒回传不补读、不采用、不写新草稿');
      opened.complete(); assert.equal(p._navigating, false);
    }
  } finally { wx.navigateTo = navigate; }
});

test('编辑器EventChannel保留精确高位ID并拒绝已丢精度的数值身份', () => {
  const navigate = wx.navigateTo;
  try {
    const p = makePage(); p.data.nodesForm = { _localId: 'original', templateId: 0, name: '' };
    p._nodeSupportContext = () => ({});
    let opened;
    wx.navigateTo = options => { opened = options; };
    p.onCreateGame({ currentTarget: { dataset: {} } }); p.onHide();
    opened.events.templateCreated({ id: 9007199254740992, title: '不精确ID', advancedConfigJson: '{}' });
    assert.equal(p.data.nodesForm.templateId, 0);
    const originalId = '9007199254740993';
    opened.events.templateCreated({ id: originalId, title: '精确原稿', advancedConfigJson: '{}' });
    assert.equal(p.data.nodesForm.templateId, originalId);
    assert.equal(p.data.nodesForm.templateInfo.id, originalId);
    p.onShow(); assert.equal(p.data.nodesForm.templateId, originalId);
    assert.equal(sent.length, 0, 'EventChannel原稿绑定不重新采用或另存');
    opened.complete();
  } finally { wx.navigateTo = navigate; }
});
