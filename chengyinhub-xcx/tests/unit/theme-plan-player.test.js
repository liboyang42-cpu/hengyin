const test = require('node:test');
const assert = require('node:assert/strict');
const plans = require('../../pages/play/utils/theme-plans.js');
const event = dataset => ({ currentTarget: { dataset } });
const input = value => ({ ...event({ index: 0 }), detail: { value } });
const clone = value => JSON.parse(JSON.stringify(value));
const row = { uiKey: 'p1', endingKey: 'continue', proposalKey: 'p1', title: '继续推进', version: 1, actions: { canFund: true, canVote: true, canAccept: true, canExecute: true }, materialCost: [{ itemId: 'tool', quantity: 2 }], ownIntent: { amounts: [{ itemId: 'tool', quantity: 1 }], signatureCurrent: true } };
function page(responder) {
  const requests = [];
  const host = { data: { ...clone(plans.data), themePlanKey: 'p1', themeBag: { scope: 'TEAM', sessionId: 7, activityId: 5, revision: 12, authorityEpoch: 4, planReadback: true,
    availableActions: ['PLAN_INTENT', 'VOTE_CAST', 'ACCEPT_SET', 'PLAN_CONFIRM', 'PLAN_CHANGE', 'PLAN_EXECUTE'], planRows: [clone(row)], world: { round: 2, itemCatalog: [{ id: 'tool', name: '工具包' }] } } },
    refreshes: 0, refreshThemeBackpack() { this.refreshes++; return Promise.resolve(true); },
    setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = this.data; for (const part of parts.slice(0, -1)) target = target[part]; target[parts.at(-1)] = value; } } };
  Object.assign(host, plans.methods(async (url, method, data) => { requests.push({ url, method, data }); return responder ? responder(url, data) : { code: 500, msg: '服务端拒绝' }; }));
  return { host, requests };
}
function receipt(command, extra = {}) { return { code: 200, data: { activityId: command.activityId, requestId: command.requestId, action: command.action, receiptId: 9, revision: 13, outcome: 'APPLIED', result: {}, ...extra } }; }

test('投入只序列化合法正整数，0 清意向，数量上限与 backend amounts 一致', () => {
  const form = { proposalKey: 'p1', version: 1, items: [{ itemId: 'tool', name: '工具包', quantity: '999' }, { itemId: 'other', quantity: '0' }] };
  assert.deepEqual(plans.fundingPayload(form), { proposalKey: 'p1', version: 1, amounts: [{ itemId: 'tool', quantity: 999 }] });
  for (const bad of ['-1', '1.5', '1000', '', 'NaN']) assert.throws(() => plans.fundingPayload({ ...form, items: [{ itemId: 'tool', name: '工具包', quantity: bad }] }), /工具包.*整数/);
});
test('修改/取消投入保持数组和原意向；失败保留输入，成功只发一次冻结身份命令', async () => {
  const failed = page(); failed.host.editThemePlanFunding(); failed.host.inputThemePlanFunding(input('2'));
  assert.ok(Array.isArray(failed.host.data.themePlanForm.items));
  assert.equal(await failed.host.saveThemePlanFunding(), false); assert.equal(failed.host.data.themePlanForm.items[0].quantity, '2');
  assert.equal(failed.host.data.themeBag.planRows[0].ownIntent.amounts[0].quantity, 1);
  failed.host.cancelThemePlanFunding(); assert.equal(failed.host.data.themePlanForm, null);
  const success = page((_, command) => receipt(command)); success.host.editThemePlanFunding(); success.host.inputThemePlanFunding(input('2'));
  assert.equal(await success.host.saveThemePlanFunding(), true); assert.equal(success.host.data.themePlanForm, null);
  assert.equal(success.requests.length, 1); assert.deepEqual(success.requests[0].data.payload, { proposalKey: 'p1', version: 1, amounts: [{ itemId: 'tool', quantity: 2 }] });
  assert.equal(success.requests[0].data.authorityEpoch, 4); assert.equal(success.requests[0].data.expectedRevision, 12);
});
test('非队长或缺新字段只读；服务端禁用不能靠本地库存覆盖', async () => {
  for (const reason of ['CHALLENGE_LIMIT', 'TEAM_COMPLETED', 'COUNTERPART_COMPLETED', 'TASK_ACTIVE']) assert.notEqual(plans.reasonText(reason), reason);
  for (const change of [host => host.data.themeBag.availableActions = [], host => host.data.themeBag.planReadback = false, host => host.data.themeBag.planRows[0].actions.canFund = false]) {
    const { host, requests } = page(); change(host); host.editThemePlanFunding(); assert.equal(host.data.themePlanForm, null); assert.equal(await host.saveThemePlanFunding(), false); assert.equal(requests.length, 0);
  }
});
test('方案 version 更新后旧投入表单禁止发送', async () => {
  const { host, requests } = page(); host.editThemePlanFunding(); host.data.themeBag.planRows[0].version = 2;
  assert.equal(await host.saveThemePlanFunding(), false); assert.match(host.data.themePlanError, /版本/); assert.equal(requests.length, 0);
});
test('首选投票、改票与接受轮严格使用 server round，不数票；接受清单可为空', async () => {
  const { host, requests } = page((_, command) => receipt(command));
  await host.onThemePlanCommand(event({ action: 'VOTE_CAST' })); await host.rejectThemePlanVote();
  host.toggleThemePlanAcceptance(event({ key: 'p1' })); await host.submitThemePlanAcceptance();
  host.toggleThemePlanAcceptance(event({ key: 'p1' })); await host.submitThemePlanAcceptance();
  assert.deepEqual(requests.map(row => [row.data.action, row.data.payload]), [['VOTE_CAST', { round: 2, proposalKey: 'p1' }], ['VOTE_CAST', { round: 2, proposalKey: 'REJECT' }], ['ACCEPT_SET', { round: 2, proposalKeys: ['p1'] }], ['ACCEPT_SET', { round: 2, proposalKeys: [] }]]);
});
test('未知写入立即读原回执，pending 保留锁与输入且不重发', async () => {
  const { host, requests } = page(url => url.endsWith('/command') ? { netFail: true } : { code: 200, data: { outcome: 'PENDING' } });
  host.editThemePlanFunding(); host.inputThemePlanFunding(input('2')); assert.equal(await host.saveThemePlanFunding(), false);
  assert.equal(host.data.themePlanUnknown, true); assert.ok(host._planPending); assert.equal(host.data.themePlanForm.items[0].quantity, '2');
  await host.checkThemePlanReceipt(); await host.saveThemePlanFunding();
  assert.equal(requests.filter(row => row.url.endsWith('/command')).length, 1); assert.equal(requests.filter(row => row.url.endsWith('/receipt')).length, 2);
  assert.equal(requests[1].data.requestId, requests[0].data.requestId);
});
test('纪元冲突展示理由并重读，不使用新纪元自动重试', async () => {
  const { host, requests } = page(() => ({ code: 500, msg: '执行权纪元已变化，请重新读取', data: { reasonCode: 'GAME_AUTHORITY_CONFLICT' } }));
  host.editThemePlanFunding(); assert.equal(await host.saveThemePlanFunding(), false); assert.equal(host.refreshes, 1); assert.equal(requests.length, 1);
  assert.match(host.data.themePlanError, /纪元/); assert.equal(host.data.themePlanForm.items[0].quantity, '1');
});
test('replayed 只查询原回执；操作结局只采用匹配 APPLIED 回执', async () => {
  let command;
  const { host, requests } = page((url, body) => {
    if (url.endsWith('/command')) { command = body; return receipt(body, { replayed: true, result: { endingKey: 'wrong', title: '不能先展示' } }); }
    return receipt(command, { result: { result: { endingKey: 'safe_end', terminal: true, title: '同行结局', summary: '实际结果' } } });
  });
  host.editThemePlanFunding(); assert.equal(await host.saveThemePlanFunding(), true);
  assert.equal(requests.length, 2); assert.equal(host.data.themePlanResult.endingKey, 'safe_end'); assert.equal(host.data.themePlanResult.terminal, true);
});

test('旧服只有可见 PLAN 工具时展示需求与节点但不猜资格/余额/表决结果', () => {
  const view = { scope: 'TEAM', teamId: 1, availableActions: ['PLAN_PROPOSE', 'PLAN_EXECUTE'], contents: [{ id: 'tool', kind: 'ITEM', name: '工具包', quantity: 99 }],
    interactionTools: [{ kind: 'PLAN', endingKey: 'continue', title: '继续推进', summary: '讨论后操作', operationNodeId: 11, enabled: true, materialCost: [{ itemId: 'tool', quantity: 2 }] }],
    world: { stage: 'DRAFT', round: 0, proposals: [] } };
  const shown = plans.present(view, [{ nodeId: 11, name: '操作站' }]);
  assert.equal(shown.planReadback, false); assert.equal(shown.planRows[0].operationNodeName, '操作站');
  assert.equal(shown.planRows[0].reason, '信息尚未更新'); assert.deepEqual(shown.planRows[0].commandButtons, []);
  assert.deepEqual(shown.planMaterials, []); assert.deepEqual(shown.planGlobalActions, []);
});
test('新回读每种 policy/phase 仅采用 server actions 和可用物资，不计算承诺总数', () => {
  for (const policy of ['MAJORITY', 'UNANIMOUS', 'MAJORITY_ACCEPTANCE']) {
    const result = plans.present({ teamId: 1, availableActions: ['PLAN_INTENT', 'VOTE_CAST', 'PLAN_EXECUTE'],
      world: { votePolicy: policy, votePhase: 'VOTING', selected: 'p1', plans: [{ proposalKey: 'p1', endingKey: 'continue', title: '继续推进', materialCost: [{ itemId: 'tool', quantity: 3 }], conditionsMet: false, actions: { canFund: false, canVote: false, canAccept: false, canExecute: false, disabledReason: 'RULE_BLOCKED' } }],
        ownMaterials: [{ itemId: 'tool', available: 8, locked: 2 }], itemCatalog: [{ id: 'tool', name: '工具包' }], proposals: [{ ...row, confirmed: { 1: [{ itemId: 'tool', quantity: 1 }], 2: [{ itemId: 'tool', quantity: 2 }] } }] } }, []);
    assert.match(result.planRows[0].conditionText, /未满足/); assert.deepEqual(result.planRows[0].commandButtons, []);
    assert.deepEqual(result.planMaterials, [{ itemId: 'tool', available: 8, locked: 2, name: '工具包' }]);
    assert.equal(result.planRows[0].confirmedText, '队伍 1：工具包 ×1；队伍 2：工具包 ×2');
    assert.ok(result.planPolicyText !== '信息尚未更新');
  }
});
test('已选方案只走真实 PLAN 操作，开始无 costOption，取消为空 payload', async () => {
  const { host, requests } = page((_, command) => receipt(command));
  Object.assign(host.data.themeBag.planRows[0], { operation: 'OPERATION_START', enabled: true });
  host.data.themeBag.availableActions = ['OPERATION_START', 'OPERATION_CANCEL'];
  assert.equal(await host.onThemePlanCommand(event({ action: 'OPERATION_START' })), true);
  assert.equal(await host.onThemePlanCommand(event({ action: 'OPERATION_CANCEL' })), true);
  assert.deepEqual(requests.map(row => row.data.payload), [{ operationKind: 'PLAN', operationKey: 'continue' }, {}]);
});
test('实际 operationNodeId 来自路线，未开始不打开；未知节点显示原因', () => {
  const { host } = page(); Object.assign(host.data.themeBag.planRows[0], { operationNodeId: 11 });
  assert.equal(host.openThemePlanOperation(), false);
  host.data.themeBag.planRows[0].operationStarted = true; host._nodeById = () => null;
  assert.equal(host.openThemePlanOperation(), false); assert.match(host.data.themePlanError, /节点资料/);
  const node = { nodeId: 11, name: '操作站' }; host._nodeById = id => id === 11 && node;
  assert.equal(host.openThemePlanOperation(), true); assert.deepEqual(host.data.advancedPlay.node, node);
});

const backendWorld = require('./fixtures/plan-vote-world.json');
test('Java §11 等值 fixture：接受轮/本队票和签名按回读展示，换队长不继承授权', () => {
  const view = { teamId: 1, availableActions: ['ACCEPT_SET'], world: clone(backendWorld) };
  view.world.proposals = [{ proposalKey: 'p1', endingKey: 'leave', teamId: 2, status: 'OPEN', version: 1, title: '离开', summary: '安全离开', materialCost: [{ itemId: 'tool', quantity: 2 }], confirmed: {}, ownConfirmed: false, ownIntent: { amounts: [{ itemId: 'tool', quantity: 2 }], signatureCurrent: true } }];
  view.world.itemCatalog = [{ id: 'tool', name: '工具包', slotCost: 1 }, { id: 'clue', name: '线索', slotCost: 1 }];
  let shown = plans.present(view, [{ nodeId: 99, name: '操作站' }]);
  assert.equal(shown.planPhaseText, '接受方案轮'); assert.equal(shown.planCanAccept, true);
  assert.equal(shown.planRows[0].uiKey, 'p1'); assert.equal(shown.planRows[0].intentText, '工具包 ×2');
  assert.match(shown.planBallotText, /本队首选：本轮不选任何方案（当前队长已签署）/);
  assert.match(shown.planBallotText, /本队接受：不接受任何方案/);
  assert.deepEqual(view.world.votes, { 1: 'REJECT', 2: 'REJECT' });
  view.world.ownVote.ownSignatureCurrent = false; view.world.ownAcceptance.ownSignatureCurrent = false;
  view.world.proposals[0].ownIntent.signatureCurrent = false;
  view.availableActions = []; view.world.plans[0].actions = { canFund: false, canVote: false, canAccept: false, canExecute: false, disabledReason: 'NOT_CAPTAIN' };
  shown = plans.present(view, []);
  assert.match(shown.planBallotText, /队长已变更，须重新签署/);
  assert.match(shown.planRows[0].signatureText, /旧意向签名失效/);
  assert.equal(shown.planCanAccept, false); assert.equal(shown.planRows[0].canEditFunding, false); assert.deepEqual(shown.planRows[0].commandButtons, []);
});
test('Java plans 的 canonical proposalKey 不被历史提案/已撤回版本重写', () => {
  const world = clone(backendWorld);
  world.proposals = [{ proposalKey: 'old', endingKey: 'leave', status: 'WITHDRAWN', version: 99, ownIntent: { amounts: [], signatureCurrent: false } }, { proposalKey: 'p1', endingKey: 'leave', version: 1, confirmed: {} }];
  const shown = plans.present({ world, availableActions: ['ACCEPT_SET'] }, []);
  assert.equal(shown.planRows.length, 1); assert.equal(shown.planRows[0].proposalKey, 'p1'); assert.equal(shown.planRows[0].version, 1); assert.equal(shown.planRows[0].ownIntent, undefined);
});

test('共识轮与首选票使用各自命令，服务端 canVote=false 的方案不能投票', async () => {
  for (const [policy, phase, action] of [['MAJORITY','VOTING','VOTE_CAST'], ['UNANIMOUS','VOTING','VOTE_CAST'], ['MAJORITY_ACCEPTANCE','CONSENSUS','CONSENSUS_CAST']]) {
    const { host, requests } = page((_, command) => receipt(command));
    const world = clone(backendWorld); world.votePolicy = policy; world.votePhase = phase; world.round = 2;
    world.plans[0].actions = { canFund:false,canVote:true,canAccept:false,canExecute:false,disabledReason:null };
    host.data.themeBag.world = world; host.data.themeBag.availableActions = [action];
    const shown = plans.present(host.data.themeBag, []); host.data.themeBag.planRows = shown.planRows;
    await host.onThemePlanCommand(event({ action }));
    assert.equal(requests[0].data.action, action); assert.deepEqual(requests[0].data.payload, { round: host.data.themeBag.world.round, proposalKey: 'p1' });
    host.data.themeBag.planRows[0].actions.canVote = false; await host.onThemePlanCommand(event({ action })); assert.equal(requests.length,1);
  }
});

test('选中方案确认托管/执行采用 server round，取消不写，纪元变化不沿用旧确认', async () => {
  let confirm;
  global.wx = { showModal(options) { confirm = options.success; } };
  const { host, requests } = page((_, command) => receipt(command, { result: { result: { endingKey: 'leave', terminal: true, title: '安全离开' } } }));
  await host.onThemePlanCommand(event({ action: 'PLAN_CONFIRM' }));
  assert.deepEqual(requests[0].data.payload, { round: 2, proposalKey: 'p1' });
  await host.onThemePlanCommand(event({ action: 'PLAN_EXECUTE' })); confirm({ confirm: false }); assert.equal(requests.length,1);
  await host.onThemePlanCommand(event({ action: 'PLAN_EXECUTE' })); host.data.themeBag.authorityEpoch = 5; confirm({ confirm: true }); assert.equal(requests.length,1); assert.match(host.data.themePlanError,/状态已变化/);
  await host.onThemePlanCommand(event({ action: 'PLAN_EXECUTE' })); confirm({ confirm: true }); await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.length,2); assert.equal(requests[1].data.action,'PLAN_EXECUTE'); assert.deepEqual(requests[1].data.payload,{round:2,proposalKey:'p1'}); assert.equal(host.data.themePlanResult.title,'安全离开');
});
test('原回执 action 不匹配时不展示结局、不解锁、不重复写', async () => {
  let command;
  const { host, requests } = page((url, body) => {
    if (url.endsWith('/command')) { command = body; return {netFail:true}; }
    return receipt(command,{ action:'PLAN_EXECUTE',result:{result:{endingKey:'wrong',title:'不可展示'}}});
  });
  host.editThemePlanFunding(); await host.saveThemePlanFunding(); assert.equal(host.data.themePlanUnknown,true); assert.equal(host.data.themePlanResult,null); assert.ok(host._planPending); assert.match(host.data.themePlanError,/异常/);
  await host.checkThemePlanReceipt(); assert.equal(requests.filter(row=>row.method==='POST').length,1);
});
test('读取本队已签接受清单，轮询保留未提交选择，换场/换轮不沿用旧选择', () => {
  const backpack = require('../../pages/play/utils/theme-backpack.js');
  const { host } = page(); host._stopThemePoll = ()=>{}; host._startThemePoll = ()=>{}; host.data.nodes=[];
  const methods = backpack.methods(()=>{});
  Object.assign(host, { _applyThemeView: methods._applyThemeView, _applyZeroHpView: methods._applyZeroHpView, _startZeroHpTimer: methods._startZeroHpTimer, _stopZeroHpTimer: methods._stopZeroHpTimer });
  const world = clone(backendWorld); world.round=2; world.ownAcceptance.planKeys=['p1'];
  const view={...host.data.themeBag,world}; host._applyThemeView(view);
  assert.deepEqual(host.data.themePlanAcceptKeys,['p1']);
  host.toggleThemePlanAcceptance(event({key:'p1'})); host._applyThemeView(view); assert.deepEqual(host.data.themePlanAcceptKeys,[]);
  host._applyThemeView({...view,sessionId:8}); assert.deepEqual(host.data.themePlanAcceptKeys,['p1']); assert.equal(host._themePlanAcceptanceDirty,false);
  host.data.themePlanAcceptKeys=[]; host._themePlanAcceptanceDirty=true;
  host._applyThemeView({...view,sessionId:8,world:{...world,round:3}}); assert.deepEqual(host.data.themePlanAcceptKeys,['p1']);
});
test('方案锁定说明：锁定物资仍计入负重；改案只释放方案锁与投票，不承诺释放交易锁', async () => {
  const fs = require('node:fs'); const path = require('node:path');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/theme-plan-list.wxml'), 'utf8');
  assert.match(wxml, /锁定的物资（交易锁或方案锁）仍在背包里，照常计入负重/);
  const shown = [];
  global.getCurrentPages = () => [];
  global.wx = { ...(global.wx || {}), showModal: options => shown.push(options) };
  const { host, requests } = page();
  assert.equal(await host.onThemePlanCommand(event({ action: 'PLAN_CHANGE' })), false);
  assert.equal(requests.length, 0);
  assert.equal(shown.length, 1);
  assert.equal(shown[0].content, '改案只释放方案锁定的物资和本轮投票、签名；交易锁定的物资不会释放。请确认后确认。');
  assert.doesNotMatch(shown[0].content, /全部|所有占用/);
});
