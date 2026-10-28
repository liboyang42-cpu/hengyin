const { test } = require('node:test');
const assert = require('node:assert/strict');
const support = require('../../pages/publish/utils/publish/gameplay-support-matrix.js');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js');
// 从 GameplaySupportMatrix.java 静态表复制的 AjaxResult.view（32 主玩法、10 附加项）。
const response = require('./fixtures/gameplay-support-matrix.json');
const matrix = response.data;
const clone = value => JSON.parse(JSON.stringify(value));
const tpl = (advanced, method = 0, id = 1) => ({ id, title: '测试玩法', validationMethod: method, advancedConfigJson: JSON.stringify(advanced) });
const qa = { qa: { enabled: true, mode: 'TYPE', maxTries: 0, questions: [{ question: '题面', answer: '答案' }] } };
const cost = { interactionRules: { hpCost: 1 } };
const shared = { runtime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED' }, settled: true };
let sent, notices, definition;
let app;
global.getApp = () => app;
global.wx = {
  getStorageSync: () => undefined, setStorageSync() {}, removeStorageSync() {},
  getSystemInfoSync: () => ({ windowWidth: 375 }), getBackgroundAudioManager: () => ({ stop() {} }),
  showToast: o => notices.push(o.title), showLoading() {}, hideLoading() {}, showModal() {},
  createMapContext: () => ({}), nextTick: f => f(), navigateTo() {}, pageScrollTo() {}
};
global.Page = cfg => { definition = cfg; };
const getMatrix = p => Object.prototype.hasOwnProperty.call(p, '_supportMatrix') ? p._supportMatrix : p.data.supportMatrix;
const setMatrix = (p, value) => { if (Object.prototype.hasOwnProperty.call(p, '_supportMatrix')) p._supportMatrix = value; else p.data.supportMatrix = value; };
function page(kind) {
  sent = []; notices = [];
  app = { globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserID: () => 42,
    getNickname: () => '', getAvatar: () => '', getAuthorization: () => 'test',
    getRequestErrorMessage: (r, fallback) => r.msg || fallback, sendRequest: req => sent.push(req), tips() {} };
  const path = '../../pages/publish/' + kind + '/index.js';
  delete require.cache[require.resolve(path)]; require(path);
  const p = Object.assign({}, definition, { data: clone(definition.data) });
  p.setData = function(patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data;
      for (const part of parts.slice(0, -1)) at = at[part];
      at[parts.at(-1)] = value;
    }
    if (callback) callback();
  };
  p._refreshOutcomeContract = () => ({ items: [], errors: [] }); p._syncCheckDerived = () => {};
  p.refreshPreviewState = () => {}; p.hasModule = () => false;
  setMatrix(p, matrix);
  return p;
}
const tap = key => ({ currentTarget: { dataset: { key } } });

test('endpoint fixture 与当前目录 key/section/mode 一致，完整保留策略与条件字段', () => {
  assert.equal(matrix.types.length, 32); assert.equal(matrix.addons.length, 10);
  for (const row of matrix.types) {
    const game = catalog.findGame(row.key); assert.ok(game, row.key);
    assert.equal(game.section || '', row.section); assert.equal(game.mode, row.mode);
    assert.deepEqual(Object.keys(row.tools).sort(), ['costs','costOptions','limitsScope','targetScope','trade','vote','decidesEnding','zeroChallenge','resourceSlots'].sort());
  }
  assert.deepEqual(matrix.addons.filter(row => catalog.MODIFIERS.some(item => item.key === row.key)).map(row => row.key).sort(), catalog.MODIFIERS.map(row => row.key).sort());
  assert.deepEqual(matrix.zeroHpPolicies.generic, ['BLOCK_HP_COST', 'CHALLENGE', 'BLOCK_PAID', 'THEME_FAIL', 'WAIT', 'STEPS']);
  assert.equal(matrix.outcomeRules.version, 1);
  assert.equal(matrix.outcomeRules.nextStepId, 'SETTLED_FEEDBACK');
  assert.equal(matrix.outcomeRules.failParticipants, 'UNSUPPORTED');
});

test('真实 endpoint 的主玩法/工具策略双向限制，旧独立玩法仍可使用', () => {
  for (const row of matrix.types) {
    for (const tool of ['cost','uses','trade','vote']) {
      assert.equal(!!support.toolPolicy(matrix, row.key, tool, {}, shared), !row.completionProof, row.key + '/' + tool);
    }
    assert.equal(!!support.choice(matrix, row.key, cost), !row.completionProof, row.key);
    assert.equal(support.choice(matrix, row.key, {}), '', '不限制独立旧玩法');
  }
  assert.equal(support.validate(matrix, tpl({ estimate: { enabled: true } })), '');
  assert.match(support.validate(matrix, tpl({ estimate: { enabled: true }, ...cost })), /无法确认是否完成/);
  assert.equal(support.validate(matrix, tpl(qa, 1), shared), '');
  assert.match(support.validate(matrix, tpl({ ...qa, qa: { ...qa.qa, maxTries: 3 }, ...cost }, 1)), /maxTries/);
  assert.match(support.validate(matrix, tpl({ sort: { enabled: true, maxAttempts: 1 }, ...cost })), /maxAttempts/);
  assert.match(support.validate(matrix, tpl({ scan: { enabled: true }, ...cost }, 1)), /须选择扫码验证/);
  assert.equal(support.validate(matrix, tpl({ ...qa, branch: { enabled: true }, random: { enabled: true }, ...cost }, 1)), '');
});

test('WORLD_SHARED_ONLY 不是无条件允许；未知主题局只显示条件，已知个人局拒绝跨队', () => {
  const tools = { interactionTools: { allowTrade: true } };
  const personal = { runtime: { runtimeScope: 'PERSONAL' }, settled: true };
  assert.equal(support.validate(matrix, tpl({ ...qa, ...tools }, 1)), '');
  assert.equal(support.condition(matrix, 'qaText', 'trade'), matrix.constraints.WORLD_SHARED_ONLY);
  assert.equal(support.toolPolicy(matrix, 'qaText', 'trade', {}, personal), matrix.constraints.WORLD_SHARED_ONLY);
  assert.equal(support.validate(matrix, tpl({ ...qa, ...tools }, 1), shared), '');
  assert.equal(support.validate(matrix, tpl({ ...qa, ...tools }, 1), personal), matrix.constraints.WORLD_SHARED_ONLY);
  assert.equal(support.validate(matrix, tpl({ ...qa, interactionRules: { scope: 'WORLD' } }, 1), personal), matrix.constraints.OWNER_OR_TEAM);
  assert.equal(support.validate(matrix, tpl({ ...qa, interactionRules: { targetScope: 'PARTICIPANTS' } }, 1), personal), matrix.constraints.OWNER_OR_TEAM);
  assert.match(support.validate(matrix, tpl({ ...qa, ...cost, interactionTools: { plans: [{title:'方案'}] } }, 1), shared), /不能再设置/);
  const ctx = support.context({ gameplayRuntime: { runtimeScope:'PLAYER', quests:[{nodeId:9,scope:'WORLD'}] } }, {id:9});
  assert.equal(support.validate(matrix, tpl(qa,1),ctx),matrix.constraints.OWNER_OR_TEAM);
  assert.match(support.validate(matrix, tpl(qa,1), {...ctx, challenge:true}), /归零挑战不能再设置/);
});

test('每个 app 会话只读取一次；旧接口缺失、错误和畸形响应均不禁用', async () => {
  let request, count = 0;
  const a = { sendRequest: r => { request = r; count++; } };
  const first = support.load(a), second = support.load(a);
  assert.equal(first, second); assert.equal(count, 1);
  assert.equal(request.method, 'GET'); assert.equal(request.url, '/api/play/theme/support-matrix');
  request.success(response); const loaded = await first;
  assert.deepEqual(Object.assign({}, loaded, { constraints: matrix.constraints }), matrix);
  for (const text of Object.values(loaded.constraints)) assert.doesNotMatch(text, /[A-Za-z]/, '作者页约束文案不应含工程代码');
  for (const result of [{code:404}, {code:200,data:{}}, null]) {
    const b = { sendRequest: r => result ? r.success(result) : r.fail({},404) };
    const fallback = await support.load(b); assert.equal(fallback,null);
    assert.equal(support.toolPolicy(fallback,'estimate','cost',{}),'');
    assert.equal(support.validate(fallback,tpl({estimate:{enabled:true},...cost})), '');
  }
});

test('主题和模板支持信息读取失败可见且不阻断；重试重读并清除提示', async () => {
  for (const kind of ['fabu', 'temp']) {
    const p = page(kind); setMatrix(p, null);
    p._syncRuntimeStats = () => {};
    const first = p._loadSupportMatrix();
    const request = sent.find(r => r.url === '/api/play/theme/support-matrix');
    assert.equal(request.silentError, undefined);
    assert.equal(request.autoErrorToast, false);
    request.fail({}, 404); await first;
    assert.match(p.data.supportMatrixLoadError, /未加载，请重试/);
    assert.equal(p.data.supportMatrixLoading, false);
    assert.equal(support.validate(getMatrix(p), tpl({ estimate: { enabled: true }, ...cost })), '');
    const retry = p.retrySupportMatrix();
    p.retrySupportMatrix();
    const requests = sent.filter(r => r.url === '/api/play/theme/support-matrix');
    assert.equal(requests.length, 2, '失败缓存被清除，进行中的重试不重复请求');
    requests[1].success(response); await retry;
    assert.equal(p.data.supportMatrixLoadError, '');
    assert.deepEqual(Object.assign({}, getMatrix(p), { constraints: matrix.constraints }), matrix);
    assert.equal(p.data.supportMatrixLoading, false);
  }
});

test('模板详情加载显示旧组合原因且不改写；添加/切主玩法被拦，保存不会写 API', () => {
  const p = page('temp'), original = tpl({ estimate: { enabled: true }, ...cost });
  p.fillFormWithTemplateData(original);
  const loaded = clone(p.data.advanced);
  assert.match(p.data.supportError, /无法确认是否完成/); assert.equal(p.data.advanced.interactionRules.hpCost,1);
  p.openGameSheet(); assert.match(p.data.supportReasons.uses,/无法确认是否完成/);
  assert.equal(p.data.r5Editor.toolOptions.some(item => item.key === 'trade'), false);
  p.openToolSheet(tap('trade')); assert.equal(p.data.toolSheetOpen,false);
  p.data.gameKey='qaText'; p.pickGame(tap('qaShot')); assert.equal(p.data.gameKey,'qaText');
  assert.deepEqual(p.data.advanced,loaded);
  p.saveDraft(); assert.equal(sent.filter(r=>r.url==='/api/template/draft').length,0);
  assert.match(p.data.errors.advanced,/无法确认是否完成/);
});

test('工具子浮层保存不通过矩阵时保留输入；去掉旧工具后可继续编辑旧主玩法', () => {
  const p = page('temp'); p.data.advanced = { estimate:{enabled:true}, ...cost }; p.data.gameKey='estimate';
  p.data.toolKey='trade'; p.data.toolSheetOpen=true; p.data.toolDraft={allowTrade:true};
  p.saveTool(); assert.match(p.data.toolError.error,/无法确认是否完成/);
  assert.equal(p.data.toolSheetOpen,true); assert.equal(p.data.toolDraft.allowTrade,true);
  p.removeTool(tap('cost')); assert.equal(p.data.supportError,'');
  assert.equal(p.data.advanced.estimate.enabled,true);
});

test('缺接口时页面仍展示原玩法，加载仅发一次且不改变任何配置', async () => {
  const p = page('temp'); setMatrix(p, null);
  p.fillFormWithTemplateData(tpl({estimate:{enabled:true},...cost})); const before=clone(p.data.advanced);
  p._loadSupportMatrix(); p._loadSupportMatrix(); assert.equal(sent.length,1);
  sent[0].fail({},404); await Promise.resolve(); await Promise.resolve();
  assert.equal(p.data.supportError,''); assert.deepEqual(p.data.advanced,before);
  assert.equal(p.data.supportReasons.trade,undefined);
});

test('站点选用不兼容模板保留原绑定与草稿；列表标原因，发布检查定位本站', () => {
  const p=page('fabu'), bad=tpl({estimate:{enabled:true},...cost},0,8), good=tpl(qa,1,7);
  p.data.nodesForm={id:11,name:'站点',templateId:7,templateInfo:good};
  p.data.formData.chapters=[{nodes:[p.data.nodesForm]}];
  p.data.formData.themeRuntimeConfig=JSON.stringify({gameplayRuntime:{runtimeScope:'TEAM',worldMode:'WORLD_SHARED',quests:[{nodeId:11}]}});
  p.data.publicTempList=[bad]; const draft=clone(p.data.formData);
  p._refreshSupportMatrix(); assert.match(p.data.publicSupportReasons[8],/无法确认是否完成/);
  p.selectTemp({currentTarget:{dataset:{item:bad,source:'public'}}});
  assert.equal(p.data.selectedTempId,null); p._bindNodeTemplate(bad); assert.equal(p.data.nodesForm.templateId,7);
  assert.deepEqual(p.data.formData,draft); assert.equal(sent.length,0);
  p._templateDetailsById={7:bad}; const issues=p._supportPublishIssues();
  assert.equal(issues[0].field,'templateTool:11:'); assert.match(issues[0].label,/节点 11/);
  assert.ok(p.buildPublishCheck().blocking.some(issue=>issue.field==='templateTool:11:' && /无法确认是否完成/.test(issue.label)));
});

test('站点矩阵不拦无通用工具的旧主题；公共采用回读后再按真实配置拦绑定', () => {
  const p=page('fabu'); p.data.nodesForm={id:11,name:'原站点',templateId:7};
  p.data.formData.themeRuntimeConfig=''; const legacy=tpl({estimate:{enabled:true}},0,8);
  p._bindNodeTemplate(legacy); assert.equal(p.data.nodesForm.templateId,8);
  p.data.nodesForm.templateId=7; p.data.selectedTempInfo=tpl(qa,1,2); p.data.selectedTempId=2;p.data.selectedTempSource='public';
  p.getStoryDraftList=()=>{};p.confirmTemp();
  // 采用先读来源版本，带原请求号提交，再按原请求读回采用草稿详情。
  sent.find(r=>r.url==='/api/template/adoption-source').success({code:200,data:{originalTemplateId:'2',sourceVersion:'a'.repeat(64)}});
  const command=JSON.parse(sent.find(r=>r.url==='/api/template/adopt-draft').data);
  sent.at(-1).success({code:200,data:{requestId:command.requestId,originalTemplateId:'2',sourceVersion:'a'.repeat(64),status:'COMPLETED',draftId:'99'}});
  assert.equal(sent.at(-1).url,'/api/template/adoption-detail');
  sent.at(-1).success({code:200,data:tpl({estimate:{enabled:true},...cost},0,99)});
  assert.equal(p.data.nodesForm.templateId,7); assert.equal(sent.filter(r=>r.url==='/api/template/adopt-draft').length,1);
  assert.equal(notices.some(text=>text.includes('并绑定')),false);
  // 采用已落成草稿但不兼容本站：原因改为常驻在节点卡上的采用提示(templateAdoptionError)，不再是一闪而过的 toast。
  assert.match(p.data.templateAdoptionError,/已存为草稿；/);
});

test('旧主题纯效果模板在所有站点选择列表可用；通用主题只限制实际结算站点', () => {
  const p = page('fabu'), effect = tpl({ estimate: { enabled: true }, rootEffects: [] }, 0, 8);
  p.data.nodesForm = { id: 11, name: '站点' };
  p.data.publicTempList = [effect]; p.data.storyDraftList = [effect]; p.data.tempList = [effect];
  p.data.formData.themeRuntimeConfig = '';
  p._refreshSupportMatrix();
  for (const key of ['publicSupportReasons', 'draftSupportReasons', 'mineSupportReasons']) {
    assert.equal(p.data[key][8], '', key);
  }
  assert.equal(p.data.storySupportReasons.estimate, '');
  assert.equal(p.data.nodeSupportReason, '');
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: {
    runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', quests: [{ nodeId: 12 }]
  } });
  p._refreshSupportMatrix();
  assert.equal(p.data.publicSupportReasons[8], '', '同一主题的非任务站点不参与通用结算');
  p.data.nodesForm.id = 12;
  p._refreshSupportMatrix();
  assert.equal(p.data.publicSupportReasons[8], '这种玩法暂时无法确认是否完成');
  assert.equal(p.data.storySupportReasons.profile, '这种玩法暂时无法确认是否完成');
});

test('旧主题原有效果引用 VALUE 槽时列表、选用与发布检查口径一致', () => {
  const p = page('fabu'), effect = tpl({
    profile: { enabled: true, questions: [{ key: 'choice', kind: 'pick', options: [
      { key: 'A', label: '继续', effects: [{ var: 'value_1', op: 'INC', value: 2 }] }
    ] }] },
    interactionRules: { resourceSlots: [{ key: 'value_1', kind: 'VALUE', label: '恢复的数值', required: true }] }
  }, 0, 8);
  p.data.nodesForm = { id: 11, name: '站点' };
  p.data.formData.themeRuntimeConfig = '';
  p.data.publicTempList = [effect];
  p._refreshSupportMatrix();
  assert.equal(p.data.publicSupportReasons[8], '');
  assert.equal(p._templateSupportReason(effect), '');
  p.selectTemp({ currentTarget: { dataset: { item: effect, source: 'mine' } } });
  assert.equal(p.data.selectedTempId, 8);
  assert.equal(p._bindNodeTemplate(effect), true);
  p.data.formData.chapters = [{ nodes: [clone(p.data.nodesForm)] }];
  assert.deepEqual(p._supportPublishIssues(), []);
  assert.equal(p.data.formData.themeRuntimeConfig, '', '支持检查不创建通用运行配置');
  for (const field of ['hpCost', 'limit', 'targetScope']) {
    const advanced = JSON.parse(effect.advancedConfigJson);
    advanced.interactionRules[field] = field === 'targetScope' ? 'WORLD' : 1;
    assert.equal(p._templateSupportReason(tpl(advanced)), '这种玩法暂时无法确认是否完成', field);
  }
  const denied = clone(matrix); denied.types.find(row => row.key === 'profile').effectSlotSupport = false;
  setMatrix(p, denied); p._refreshSupportMatrix();
  assert.equal(p.data.publicSupportReasons[8], '这种玩法暂不支持所需数值资源');
  assert.equal(p._templateSupportReason(effect), '这种玩法暂不支持所需数值资源');
  assert.equal(p._supportPublishIssues()[0].label, '节点 11（站点）：这种玩法暂不支持所需数值资源');
  const scoped = clone(matrix); scoped.types.find(row => row.key === 'profile').effectSlotScopes = ['WORLD'];
  setMatrix(p, scoped);
  assert.equal(p._templateSupportReason(effect), '这种玩法暂不支持所需数值资源');
  const old = clone(matrix); old.types.concat(old.addons).forEach(row => {
    delete row.effectSlotSupport; delete row.effectSlotScopes;
  });
  setMatrix(p, old); p._refreshSupportMatrix();
  assert.equal(p.data.publicSupportReasons[8], '', '旧矩阵缺字段时仍交给服务端发布校验');
  assert.deepEqual(p._supportPublishIssues(), []);
});

test('站点结算上下文需要真实通用范围及任务或设施引用，表决与挑战独立保留', () => {
  const p = page('fabu'); p.data.nodesForm = { id: 11 };
  p.data.publicTempList = [tpl({ profile: { enabled: true } }, 0, 8)];
  for (const runtimeScope of [undefined, 'PERSONAL', 'PLAYER', 'TEAM']) {
    for (const rule of [{ quests: [{ nodeId: 11 }] }, { facilities: [{ operationNodeId: 11 }] }]) {
      p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: { runtimeScope, ...rule } });
      p._refreshSupportMatrix();
      const settled = ['PLAYER', 'TEAM'].includes(runtimeScope);
      assert.equal(p.data.supportNodeSettled, settled, runtimeScope + '/' + Object.keys(rule)[0]);
      assert.equal(p.data.publicSupportReasons[8], settled ? '这种玩法暂时无法确认是否完成' : '');
    }
  }
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: {
    runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', plans: [{ operationNodeId: 11 }]
  } });
  p._refreshSupportMatrix();
  assert.equal(p.data.supportNodeSettled, false, '仅表决站点不是任务或设施');
  assert.equal(p._nodeSupportContext().vote, true);
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: {
    runtimeScope: 'PLAYER', plans: [{ operationNodeId: 11 }]
  } });
  assert.equal(p._templateSupportReason(tpl(qa, 1)), matrix.constraints.WORLD_SHARED_ONLY);
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: {
    survival: { zeroChallenge: { challengeNodeId: 11, alternativeNodeIds: [12] } }
  } });
  p._refreshSupportMatrix(); assert.equal(p.data.supportNodeChallenge, true);
  assert.equal(p._templateSupportReason(tpl({ scan: { enabled: true } }, 4)), '这种玩法不能用作归零挑战');
  p.data.nodesForm.id = 12;
  p._refreshSupportMatrix(); assert.equal(p.data.supportNodeChallenge, true);
  p.data.nodesForm.id = 13;
  p._refreshSupportMatrix(); assert.equal(p.data.supportNodeChallenge, false);
});

test('归零挑战目录按服务端类型筛选，拒绝扫码/拍照/旧题；传感器必须有高级客观题备选', () => {
  const nodes=[{id:1,template:tpl({steps:{enabled:true}})}, {id:2,template:tpl(qa,1)},
    {id:3,template:tpl({qa:{enabled:true,mode:'SHOT'}})}, {id:4,template:tpl({scan:{enabled:true}},4)},
    {id:5,template:tpl({},1)}, {id:6,template:tpl({typeIn:{enabled:true}})},
    {id:7,template:tpl({...qa,...cost},1)}];
  assert.deepEqual(support.challengeChoices(matrix,nodes).primary.map(n=>n.id),[1,2,6]);
  assert.deepEqual(support.challengeChoices(matrix,nodes).alternatives.map(n=>n.id),[2]);
  assert.match(support.challengeError(matrix,nodes,{challengeNodeId:1,alternativeNodeIds:[]}),/客观题备选/);
  assert.equal(support.challengeError(matrix,nodes,{challengeNodeId:1,alternativeNodeIds:[2]}),'');
  assert.equal(support.challengeError(matrix,nodes,{challengeNodeId:6,alternativeNodeIds:[]}),'', 'typeIn 有服务端字符串判定');
  assert.match(support.challengeError(matrix,nodes,{challengeNodeId:4,alternativeNodeIds:[]}),/不能用作归零挑战/);
  assert.deepEqual(support.challengeChoices(null,nodes).primary,nodes);
});

test('归零挑战新面板选中筛选后的真实节点，旧无效选择报错保留且可移除无效备选', () => {
  const p=page('fabu');p.data.editingTopicId='1';
  p.data.formData.journeyRules=JSON.stringify({schemaVersion:1,hp:{enabled:true,init:5,max:5}});
  p.data.formData.chapters=[{nodes:[{id:1,name:'扫码',templateInfo:tpl({scan:{enabled:true}},4)},
    {id:2,name:'恢复',templateInfo:tpl({reaction:{enabled:true}})}, {id:3,name:'问题',templateInfo:tpl(qa,1)}]}];
  const challenge={version:2,gameplayKey:'reaction',challengeNodeId:1,alternativeNodeIds:[],requiredCompletions:1,withinSeconds:60,restoreTo:1,limit:3,thenPolicy:'THEME_FAIL'};
  p.data.formData.themeRuntimeConfig=JSON.stringify({gameplayRuntime:{survival:{zeroHpPolicy:'CHALLENGE',zeroChallenge:challenge}}});
  const original=p.data.formData.themeRuntimeConfig; p.openZeroHpEditor();
  assert.deepEqual(p.data.runtimeChallengeNodes.filter(n=>!n.reason).map(n=>n.id),[2,3]); assert.match(p.data.runtimeZeroEditor.error,/节点 1/);
  assert.equal(p.data.formData.themeRuntimeConfig,original); assert.equal(p.data.runtimeZeroEditor.draft.challenge.challengeNodeId,1);
  const event=(dataset={},value)=>({currentTarget:{dataset},detail:{value}});
  p.pickZeroHpChallengeNode(event({},p.data.runtimeChallengeNodes.findIndex(row=>row.id===2))); assert.equal(p.data.runtimeZeroEditor.draft.challenge.challengeNodeId,2);
  assert.equal(p.data.runtimeZeroEditor.draft.challenge.gameplayKey,'reaction');
  assert.match(p.data.runtimeZeroEditor.error,/客观题备选/); assert.equal(p.saveZeroHpEditor(),false);
  p.toggleZeroHpAlternative(event({id:1})); assert.deepEqual(p.data.runtimeZeroEditor.draft.challenge.alternativeNodeIds,[]);
  p.toggleZeroHpAlternative(event({id:3})); assert.equal(p.data.runtimeZeroEditor.error,''); assert.equal(p.saveZeroHpEditor(),true);
  const runtime=p._runtimeConfig(); runtime.gameplayRuntime.survival.zeroChallenge.alternativeNodeIds.push(1); p._saveRuntime(runtime); p.openZeroHpEditor();
  assert.ok(p.data.runtimeAlternativeNodes.some(n=>n.id===1 && n.selected && n.reason));
  assert.match(p.data.runtimeZeroEditor.error,/节点 1/);
  p.toggleZeroHpAlternative(event({id:1})); assert.equal(p.data.runtimeZeroEditor.error,''); assert.equal(p.saveZeroHpEditor(),true);
});


test('矩阵尚未读回时通用组合不能抢先保存，缺接口回退后输入仍在且可保存', async () => {
  const p=page('temp'); setMatrix(p, null);
  p.fillFormWithTemplateData(tpl(cost,1)); const before=clone(p.data.advanced);
  p._loadSupportMatrix(); p.saveDraft(); assert.equal(sent.filter(r=>r.url==='/api/template/draft').length,0);
  assert.match(p.data.errors.advanced,/正在读取/);
  sent.find(r=>r.url==='/api/play/theme/support-matrix').fail({},404); await Promise.resolve(); await Promise.resolve();
  assert.deepEqual(p.data.advanced,before); p.saveDraft(); assert.equal(sent.filter(r=>r.url==='/api/template/draft').length,1);
});


test('三主题恢复题 fixture 跟随后端真实高级判题配置，不能以旧答案假装可用', () => {
  const reference=require('../../../chengyinhub-admin/src/test/resources/gameplay-reskin/reference.json');
  const skins=require('./fixtures/gameplay-reskin-roundtrip.json');
  const config=reference.templates.find(row=>row.id===505).config;
  for(const skin of skins.themes) {
    const template=skin.templates.find(row=>row.id===505);
    assert.deepEqual(JSON.parse(template.advancedConfigJson),config);
    assert.equal(support.validate(matrix,template,{challenge:true}),'');
  }
});

test('C1 effectSlotSupport/effectSlotScopes 与费用桥分开；仅原有效果槽不要求通用完成证明', () => {
  const slots = { interactionRules: { resourceSlots: [{ key: 'value_1', kind: 'VALUE', label: '恢复的数值', required: true }] } };
  for (const row of matrix.types.concat(matrix.addons)) {
    assert.equal(row.effectSlotSupport, true); assert.deepEqual(row.effectSlotScopes, row.category === 'PROOF' ? ['OWNER','WORLD','PARTICIPANTS'] : ['OWNER']);
  }
  for (const section of ['profile', 'check', 'branch', 'pricePair', 'hiddenObject']) {
    const advanced = { [section]: { enabled: true }, ...slots, effects: [{ var: 'value_1', op: 'INC', value: 2 }] };
    assert.equal(support.validate(matrix, tpl(advanced)), '', section);
    const key = matrix.types.find(row => row.section === section).key;
    assert.equal(support.choice(matrix, key, advanced), '');
    assert.equal(support.generic(advanced), false, '没有生成通用任务/费用设施');
    assert.match(support.validate(matrix, tpl(advanced), { settled: true }), /无法确认是否完成/);
    for (const field of ['hpCost', 'limit', 'targetScope']) {
      const mixed = clone(advanced); mixed.interactionRules[field] = field === 'targetScope' ? 'WORLD' : 1;
      assert.match(support.validate(matrix, tpl(mixed)), /无法确认是否完成/);
    }
  }
  const denied = clone(matrix); denied.types.find(row => row.section === 'profile').effectSlotSupport = false;
  assert.match(support.validate(denied, tpl({ profile: { enabled: true }, ...slots })), /数值资源/);
  const old = clone(matrix); old.types.concat(old.addons).forEach(row => { delete row.effectSlotSupport; delete row.effectSlotScopes; });
  assert.equal(support.validate(old, tpl({ profile: { enabled: true }, ...slots })), '', '旧矩阵缺字段时交给服务端校验');
});

test('C1 效果能力 false 同样拦截兼有费用的旧模板，费用槽自身不误用效果能力', () => {
  const denied = clone(matrix); denied.types.find(row => row.key === 'qaText').effectSlotSupport = false;
  const rules = { resourceSlots: [{ key: 'value_1', kind: 'VALUE', label: '费用的数值', required: true }], statCosts: [{ statKey: 'value_1', amount: 1 }] };
  const feeOnly = { ...qa, interactionRules: rules };
  assert.equal(support.validate(denied, tpl(feeOnly)), '');
  assert.match(support.validate(denied, tpl({ ...feeOnly, effects: [{ var: 'value_1', op: 'INC', value: 1 }] })), /数值资源/);
});


test('context 只有启用通用模式并匹配任务或设施才要求完成桥；仅方案节点独立检查共享模式', () => {
  const original = tpl({profile:{enabled:true},interactionRules:{resourceSlots:[{key:'value_1',kind:'VALUE',label:'数值',required:true}]} });
  for (const runtimeScope of [undefined, 'PERSONAL', 'PLAYER', 'TEAM']) {
    const game = { runtimeScope, worldMode:'WORLD_SHARED', quests:[{nodeId:9}], facilities:[{operationNodeId:10}] };
    const enabled = ['PLAYER','TEAM'].includes(runtimeScope);
    for (const id of [9,10,11]) {
      const ctx = support.context({gameplayRuntime:game},{id});
      assert.equal(ctx.settled,enabled && id !== 11);
      assert.equal(!!support.validate(matrix,original,ctx),enabled && id !== 11);
    }
  }
  for (const worldMode of ['TEAM_INDEPENDENT','WORLD_SHARED']) {
    const ctx = support.context({gameplayRuntime:{runtimeScope:'TEAM',worldMode,plans:[{operationNodeId:9}]}},{id:9});
    assert.equal(ctx.settled,false); assert.equal(ctx.vote,true);
    assert.equal(support.validate(matrix,original,ctx),worldMode === 'WORLD_SHARED' ? '' : matrix.constraints.WORLD_SHARED_ONLY);
  }
});

// F-R2U-01(用户要求 2026-10-11):支持范围字典读取失败属于加载失败,一律留白;保存时仍以服务端结果为准。
test('F-R2U-01 玩法支持范围读取失败在三处编辑器均留白', () => {
  const fsx = require('node:fs');
  const px = require('node:path');
  for (const rel of ['pages/publish/temp/index.wxml', 'pages/publish/fabu/topic-detail-sheet.wxml', 'pages/publish/fabu/runtime-structure-editor.wxml']) {
    const wxml = fsx.readFileSync(px.join(__dirname, '../..', rel), 'utf8');
    const tags = wxml.match(/<cy-inline-error[^>]*supportMatrixLoadError[^>]*>/g) || [];
    assert.ok(tags.length > 0, rel);
    tags.forEach(tag => assert.match(tag, /\bload-failure\b/, rel));
  }
});

// PRIVATE-GROUP-game-support-copy:主题详情半屏两句说明,支持状态来自支持矩阵,反馈/工单不改变它。
test('fabu 主题详情半屏说明支持状态来源,反馈/工单是独立渠道', () => {
  const fsx = require('node:fs');
  const px = require('node:path');
  const wxml = fsx.readFileSync(px.join(__dirname, '../../pages/publish/fabu/topic-detail-sheet.wxml'), 'utf8');
  const card = wxml.slice(wxml.indexOf('id="fieldRuntimeState"'), wxml.indexOf('id="fieldGameRules"'));
  assert.match(card, /支持状态以玩法支持矩阵为准。反馈或工单是独立渠道，提交后不会改变支持状态。/);
  const row = card.match(/<view class="node-card__row">[^\n]*玩法支持[^\n]*\n/)[0];
  assert.doesNotMatch(row, /bind(?:tap|change)|catchtap|<cy-switch|<input/, '只改文案，不加开关或动作');
});
