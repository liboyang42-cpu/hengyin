const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { harness, view, DRAFT } = require('./simple-ai-v2-guards.test.js');
const WXML = path.join(__dirname, '../../pages/publish/simple/index.wxml');
let pageConfig;
function makePage() { const h = harness(); pageConfig = h.page; return h.page; }

test('简单 AI 入口加载并展示玩家的剩余额度', () => {
  const h = harness(); const page = h.page;
  assert.equal(page.data.mode, 2);
  const quota = h.take('/quota'); assert.equal(quota.method, 'POST');
  h.finish(quota, { code: 200, data: { limited: true, remaining: 2 } });
  assert.equal(page.data.aiQuotaLoaded, true); assert.equal(page.data.aiRemaining, 2);
});
test('成功生成后用响应中的额度刷新提示', () => {
  const h = harness({ entry: { mode: '1' } }); h.ready();
  h.finish(h.prepare(), { code: 200, data: view('SUCCEEDED', { productType: 1, replayed: false, resultAvailable: true,
    result: { traceId: 'trace-1', remainingQuota: 2, draft: { title: '夜游', storyline: '沿街探索', nodes: [{ merchantName: '第一站', task: '观察招牌' }] } } }) });
  assert.equal(h.page.data.mode, 1); assert.equal(h.page.data.aiPlan.title, '夜游'); assert.equal(h.page.data.aiRemaining, 2);
});
test('AI 返回超过三站的草稿时保持 fail-closed，不进入后续编辑', () => {
  const h = harness(); h.ready();
  h.finish(h.prepare(), { code: 200, data: view('SUCCEEDED', { replayed: false, resultAvailable: true,
    result: { traceId: 'many', remainingQuota: 2, draft: { title: '四站路线', nodes: Array.from({ length: 4 }, (_, i) => ({ merchantName: '站' + i })) } } }) });
  assert.equal(h.page.data.aiPlan, null); assert.match(h.page.data.aiError, /不超过 3 个节点/); assert.equal(h.page.data.aiRemaining, 3);
});
test('AI 重试失败时保留已经生成的草稿与节点', () => {
  const h = harness(); h.ready(); const previousPlan = { title: '老城夜游', storyline: '沿街探索' }, previousNodes = [{ name: '第一站', confirmed: true }];
  h.page.setData({ aiPlan: previousPlan, aiPlanNodes: previousNodes });
  const request = h.prepare(); request.fail({ errMsg: 'request:fail timeout' }); request.complete();
  assert.deepEqual(h.page.data.aiPlan, previousPlan); assert.deepEqual(h.page.data.aiPlanNodes, previousNodes);
  assert.match(h.page.data.aiError, /网络暂时不可用/); assert.equal(h.page.data.aiGenerating, false);
});

test('AI 进度与错误使用同一互斥状态链，不得在 complete 前同屏', () => {
  const wxml = fs.readFileSync(WXML, 'utf8');
  assert.match(wxml,
    /<cy-progress-status wx:if="\{\{aiGenerating\}\}"[\s\S]*?<cy-inline-error wx:elif="\{\{aiError\}\}"/,
    'success/fail 写入 aiError 后到 complete 清 loading 的间隙，只能保留进度态');
});

// —— 死分支回归闸:简易版曾有一整套「暗色手动编辑」子树,挂在永远为真的 wx:if="{{aiMode}}"
// 的 wx:else 上,任何用户都点不到。下面两条断言防止它被重新接回页面。 ——
test('页面不再暴露已禁用的模式切换 / 就地建玩法能力', () => {
  makePage();

  assert.equal(pageConfig.data.aiMode, undefined, 'aiMode 是恒真开关，不应重新出现');
  assert.equal(pageConfig.data.gameEditor, undefined, 'gameEditor 状态属于已删除的暗色子树');
  ['onPickMode', 'onToggleMode', 'openGameEditor', 'closeGameEditor',
    'onGameField', 'selectGameMethod', 'selectGameAnswer', 'saveInlineGame',
  ].forEach((name) => {
    assert.equal(pageConfig[name], undefined, name + ' 只被已删除的死子树调用，不应重新出现');
  });
});

test('置灰的主 CTA 必须自己给理由:disabledtap 与 canEnterAiEditor 同判据', () => {
  const h = harness(); h.ready();
  h.finish(h.prepare(), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
  const page = h.page;
  page.data.aiPlan = { title: '有标题', storyline: '' };
  page.data.aiPlanNodes = [{ name: '一站', confirmed: false, longitude: '', latitude: '' }];
  // 前提:这就是按钮置灰的那一态
  page.refreshEnterEditorState();
  assert.equal(page.data.canEnterAiEditor, false, '节点未确认时按钮应当置灰');

  page.enterAiEditor();
  assert.match(page.data.aiError, /请逐个在地图中确认地点/,
    '置灰态下 enterAiEditor 必须给得出理由 —— disabledtap 接的就是它');

  const wxml = fs.readFileSync(WXML, 'utf8');
  assert.match(wxml, /disabled="\{\{!canEnterAiEditor\}\}"[^>]*bind:disabledtap="enterAiEditor"/,
    'cy-btn 会吞掉禁用态的 tap,不接 disabledtap 这句理由永远出不来');
});

test('wxml 只保留可达的简单 AI 链路,不含暗色手动编辑子树', () => {
  const wxml = fs.readFileSync(WXML, 'utf8');

  // 可达链路必须完整:输入 → 生成 → 逐点确认 POI → 进 fabu
  ['bindinput="onAiIdeaInput"', 'bindtap="generateAiPlan"',
    'bindtap="confirmAiNodePoi"', 'bindtap="enterAiEditor"',
  ].forEach((marker) => assert.ok(wxml.includes(marker), '可达链路缺失:' + marker));

  // 死子树的特征串一个都不许回来
  ['cy-page--dark', 'onToggleMode', 'onPickMode', 'openGameEditor',
    'saveInlineGame', 'selectGameMethod', 'gameEditor.',
  ].forEach((marker) => assert.ok(!wxml.includes(marker), '死子树残留:' + marker));

  // 根节点不再挂恒真条件
  assert.ok(!/wx:if="\{\{aiMode\}\}"/.test(wxml), '根节点不应再有恒真的 wx:if="{{aiMode}}"');
});

// —— 审查 #14 回归闸:旧「快速发布」链路(节点编辑 → 预览 → 存草稿 → 直接发布)在 wxml
//    上一个绑定都没有,而它的反馈通道本身是坏的 —— tip() 只写 data.toast,wxml 从不渲染它。
//    整块删掉是为了不让下一个人「顺手修一个进不去的页面」。真要重新接线:先换成
//    cy-toast / cy-inline-error 通道,再谈接线。 —— 2026-09-19
test('简易版不带发布链路:零绑定的孤儿方法不得复活', () => {
  const page = makePage();
  ['onPublish', 'onSaveDraft', '_doPublish', 'validate', 'buildPayload', 'onPreview',
    'onClosePreview', 'onClubChange', 'loadMyClubs', 'applyPreset', 'cloneStations',
    'onPickPreset', 'onPickCustom', 'redrawMap', 'onPickStationLoc', 'onPickMeetLoc',
    'onMarkerTap', 'onDeleteStation', 'onAddStation', 'onPickTemplate', 'tip',
    '_offerDraftResume', '_restoreDraft', 'onResultSheetClose',
  ].forEach((name) => {
    assert.equal(page[name], undefined, name + ' 是零绑定孤儿，不该回到页面上');
  });
  assert.equal(page.data.toast, undefined, 'tip() 的渲染目标 wxml 从不消费');
  assert.equal(page.data.stations, undefined, '节点编辑属于已退役的发布链路');
  assert.equal(page.data.resultSheet, undefined, '成功面板只剩死生产者，一并退役');

  assert.doesNotMatch(fs.readFileSync(WXML, 'utf8'), /cy-result-sheet/,
    '本页不发发布请求，成功面板没有生产者');
  const js = fs.readFileSync(path.join(__dirname, '../../pages/publish/simple/index.js'), 'utf8');
  assert.doesNotMatch(js, /\/api\/topic\/create/, '发布只在 fabu,简易页不得重新直提');
});

// P-43:保留失败卡/描述/明确失败后重试；普通500本身不能越过V2原意图回读。
test('P-43 AI 明确失败:描述保留,失败卡重试用同一段描述再生成', () => {
  const h = harness(); h.ready(); const page = h.page;
  const dispatched = [], send = h.app.sendRequest;
  h.app.sendRequest = request => { dispatched.push(request); return send(request); };
  page.data.aiIdea = '外滩夜游三站';
  const first = h.prepare(), original = Object.assign({}, page._aiPending);
  first.success({ code: 500, msg: 'AI 响应格式异常,请重试' });
  first.complete();
  assert.match(page.data.aiError, /原起草结果还没确认/);
  assert.equal(page.data.aiDraftPending, true);
  assert.deepEqual(page._aiPending, original);
  assert.equal(page.data.aiIdea, '外滩夜游三站', '描述不能丢');
  page.retryAiPlan();
  const read = h.take('/request');
  assert.deepEqual(read.data, { requestId: original.requestId });
  assert.equal(dispatched.filter(r => r.url.endsWith('/prepare')).length, 1);
  assert.equal(dispatched.filter(r => r.url.endsWith('/execute')).length, 1);
  h.finish(read, { code: 200, data: view('FAILED') });
  assert.equal(page.data.aiDraftPending, false);
  page.retryAiPlan();
  const drafts = dispatched.filter(r => r.url.endsWith('/prepare'));
  assert.equal(drafts.length, 2);
  assert.equal(JSON.parse(drafts[1].data).idea, '外滩夜游三站');
  assert.notEqual(JSON.parse(drafts[1].data).prepareKey, original.prepareKey);
  const wxml = fs.readFileSync(WXML, 'utf8');
  assert.match(wxml, /<cy-inline-error wx:elif="\{\{aiError\}\}"[^>]*action="\{\{aiIdea \? \(aiDraftPending \? '查看结果' : '重试'\) : ''\}\}" bind:action="retryAiPlan"/);
});
