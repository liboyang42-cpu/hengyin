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

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  chooseImage: () => {},
  getUserID: () => 42,
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
