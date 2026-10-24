const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const gameplay = require('../../pages/play/utils/theme-gameplay.js');
const backpack = require('../../pages/play/utils/theme-backpack.js');

const toasts = [];
global.wx = { showToast(options) { toasts.push(options.title); } };

function page(view, responder) {
  const requests = [];
  const instance = {
    data: { ...JSON.parse(JSON.stringify(backpack.data)), topicId: 9, activityId: 5, nodes: [{ nodeId: 41, name: '挑战' }], themeBag: backpack.present(view, []) },
    setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.split('.'); let at = this.data; for (const part of parts.slice(0, -1)) at = at[part]; at[parts.at(-1)] = value; } },
    _sessionParams() { return { activityId: 5 }; },
    _loadPlayerGameModule() { return Promise.resolve(true); },
    _nodeById(id) { return this.data.nodes.find(row => row.nodeId === id); },
    refreshRouteState() { return Promise.resolve(true); },
  };
  Object.assign(instance, backpack.methods((url, method, data) => { requests.push({ url, method, data }); return Promise.resolve(responder(url, data)); }));
  return { instance, requests };
}

test('主题名称回退与自定义数值、物品效果显示', () => {
  assert.deepEqual(gameplay.labels({}), { hp: '生命值', luck: '幸运值', action: '行动次数' });
  const view = { hpName: '体力', luckName: '机遇', actionName: '回合', attributes: [{ key: 'counter.energy', label: '能量' }],
    contents: [{ id: 'supply', kind: 'ITEM', name: '补给', statKey: 'counter.energy', delta: 2, quantity: 1 }], capacity: 4 };
  assert.deepEqual(gameplay.amounts({ hpCost: 2, luckCost: 1, actionCost: 3, statCosts: [{ statKey: 'counter.energy', amount: 4 }] }, view), ['体力 2', '机遇 1', '回合 3', '能量 4']);
  assert.equal(backpack.present(view, []).items[0].statLabel, '能量');
});

test('W2 缺口数组用视图名称呈现，空数组与旧服务端缺字段均不在客户端计算', () => {
  const view = { hpName: '体力', luckName: '机遇', actionName: '回合', hp: 99, luck: 99, actions: 0,
    attributes: [{ key: 'counter.x', label: '能量', value: 99 }], contents: [{ id: 'food', name: '补给', quantity: 1 }] };
  const rows = gameplay.options([
    { label: '物资', resourceCosts: [{ itemId: 'food', quantity: 3 }], affordable: false,
      shortfall: [{ type: 'ITEM', key: 'food', missing: 2 }, { type: 'STAT', key: 'sys.hp', missing: 2 },
        { type: 'STAT', key: 'sys.luck', missing: 1 }, { type: 'STAT', key: 'counter.x', missing: 2 },
        { type: 'STAT', key: 'sys.actions', missing: 2 }] },
    { label: '数值', hpCost: 2, affordable: true, shortfall: [] },
    { label: '旧服', hpCost: 2, affordable: false },
  ], view);
  assert.equal(rows[0].amountText, '补给 ×3');
  assert.equal(rows[0].shortfallText, '缺少补给 2 · 缺少体力 2 · 缺少机遇 1 · 缺少能量 2 · 缺少回合 2');
  assert.equal(rows[1].affordable, true);
  assert.equal(rows[2].shortfallText, '费用不足');
  assert.equal(gameplay.valueName('counter.unknown', view), '数值');
});

for (const scope of ['PLAYER', 'TEAM']) {
  for (const consumed of [false, true]) {
    test(scope + ' 恢复回执用主题数值名显示无效果/消费策略/剩余次数', async () => {
      const view = { scope: scope === 'TEAM' ? 'TEAM' : 'PERSONAL', runtimeScope: scope, sessionId: 7, version: 8,
        activityId: 5, revision: 12, authorityEpoch: 4, status: 'RUNNING', hpName: '体温' };
      const result = { recoveryKey: 'rest', hpDelta: 0, consumed, remaining: consumed ? 1 : 2 };
      const { instance } = page(view, (_url, command) => ({ code: 200, data: scope === 'TEAM'
        ? { activityId: command.activityId, requestId: command.requestId, action: command.action,
          receiptId: 1, revision: 13, outcome: 'APPLIED', result: { result } }
        : { ...view, version: 9, settlement: result } }));
      instance.refreshThemeBackpack = () => Promise.resolve(true);
      assert.equal(await instance._themeGameplayCommand({ operation: 'RECOVER', contentId: 'rest' }), true);
      assert.equal(instance.data.themeRecoveryText, '体温已满，未产生效果，' + (consumed ? '已消耗一次，剩余 1 次' : '未消耗，剩余 2 次'));
    });
  }
}

test('费用只用服务端可负担与缺口，选择透传；开始后冻结服务端选项', async () => {
  const view = { sessionId: 7, version: 8, status: 'ACTIVE', hpName: '体力', quests: [{ questKey: 'q', nodeId: 3 }],
    allowedActions: [{ operation: 'QUEST_START', contentId: 'q', costs: { actionCost: 1 }, costOptions: [
      { label: '第一种', hpCost: 2, affordable: false, shortfall: [{ type: 'STAT', key: 'sys.hp', missing: 1 }] },
      { label: '第二种', luckCost: 3, affordable: true },
    ] }] };
  const { instance, requests } = page(view, () => ({ code: 200, data: { ...view, version: 9 } }));
  assert.equal(instance.data.themeBag.actionRows[0].costOptions[0].shortfallText, '缺少体力 1');
  assert.equal(instance.data.themeBag.actionRows[0].costOptions[1].amountText, '行动次数 1 · 幸运值 3');
  await instance.onThemeGameplayAction({ currentTarget: { dataset: { index: 0 } } });
  instance.selectThemeCostOption({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(instance.data.themeCostChoice.selected, -1);
  instance.selectThemeCostOption({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(await instance.confirmThemeCostChoice(), true);
  assert.equal(requests.find(row => row.method === 'POST').data.costOption, 1);
  const frozen = backpack.present({ ...view, activeQuest: 'q', activeQuestCostOption: 1, allowedActions: [{ operation: 'QUEST_RESOLVE', contentId: 'q' }] }, []);
  assert.equal(frozen.actionRows[0].frozenIndex, 1);
  assert.equal(frozen.actionRows[0].operation, 'QUEST_RESOLVE');
  const inProgress = backpack.present({ ...view, activeQuest: 'q', activeQuestCostOption: 1, allowedActions: [{ operation: 'QUEST_CANCEL', contentId: 'q' }] }, []);
  assert.equal(inProgress.actionRows[0].operation, 'OPEN_QUEST');
  assert.equal(inProgress.actionRows[0].cancelable, true);
});

test('归零挑战保留免费操作，显示服务端团队进度、免费重试和次数上限', () => {
  const base = { sessionId: 7, version: 8, awaitingChallenge: true, hpName: '体力',
    challenge: { nodeId: 41, alternativeNodeIds: [42], progress: 4, goal: 9, used: 1, limit: 3, restoreTo: 5 },
    quests: [{ questKey: 'paid', nodeId: 3 }, { questKey: 'free', nodeId: 4 }],
    allowedActions: [{ operation: 'QUEST_START', contentId: 'paid', costs: { hpCost: 1 } }, { operation: 'QUEST_START', contentId: 'free', costs: {} }] };
  const bag = backpack.present(base, [{ nodeId: 41, name: '主挑战' }, { nodeId: 42, name: '问答备选' }]);
  assert.equal(bag.challengeProgressText, '4/9');
  assert.equal(backpack.present({ ...base, scope: 'TEAM' }, []).challengeProgressLabel, '全队进度');
  assert.equal(bag.challengeRestoreText, '恢复到 5');
  assert.equal(backpack.present({ ...base, challenge: { ...base.challenge, restoreTo: undefined } }, []).challengeRestoreText, '恢复');
  assert.deepEqual(bag.challengeNodes.map(row => row.name), ['主挑战', '问答备选']);
  assert.equal(bag.challengeRetryText, '失败后可免费重试');
  assert.equal(bag.actionRows[0].enabled, false);
  assert.equal(bag.actionRows[1].enabled, true);
  assert.match(backpack.present({ ...base, challenge: { ...base.challenge, used: 3, disabledReason: 'CHALLENGE_LIMIT' } }, []).challengeRetryText, /已达上限/);
  const { instance } = page(base, () => ({ code: 200, data: base }));
  instance.openThemeChallenge({ currentTarget: { dataset: { id: 41 } } });
  assert.equal(instance.data.advancedPlay.node.nodeId, 41);
});

test('团队费用命令携带权限纪元；领取与已满未消耗回执分开呈现', async () => {
  const team = { scope: 'TEAM', sessionId: 17, activityId: 5, revision: 12, authorityEpoch: 4, teamId:17, status: 'RUNNING',
    availableActions: ['FACILITY_USE', 'ITEM_RECEIVE'], interactionTools: [{ kind: 'FACILITY', operationKey: 'f', title: '补给站',
      costOptions: [{ label: '补给', hpCost: 1, affordable: true }] }] };
  const { instance, requests } = page(team, (url, command) => url.endsWith('/preview')
    ? { code: 200, data: require('./fixtures/theme-reward-preview.json').teamFacility }
    : ({ code: 200, data: { activityId: command.activityId,
    requestId: command.requestId, action: command.action, receiptId: 1, revision: 13, outcome: 'APPLIED', result: { result: { noEffect: true } } } }));
  await instance.onThemeGameplayAction({ currentTarget: { dataset: { index: 0 } } });
  instance.selectThemeCostOption({ currentTarget: { dataset: { index: 0 } } });
  await instance.confirmThemeCostChoice();
  await instance.confirmThemeRewardPreview();
  const commands = requests.filter(row => row.url.endsWith('/command'));
  assert.equal(commands[0].data.action, 'FACILITY_USE');
  assert.equal(commands[0].data.payload.costOption, 0);
  assert.equal(commands[0].data.authorityEpoch, 4);
  assert.ok(toasts.includes('已满，未消耗'));
  await instance._themeCommand('RECEIVE', 'item-a');
  assert.equal(requests.at(-1).data.action, 'ITEM_RECEIVE');
  assert.deepEqual(requests.at(-1).data.payload, { contentId: 'item-a' });
});

test('工具操作题仅按工具上的 operationNodeId 启动，未核验时可打开题目', async () => {
  const view = { scope: 'TEAM', sessionId: 17, activityId: 5, revision: 12, authorityEpoch: 4, status: 'RUNNING',
    availableActions: ['FACILITY_USE', 'OPERATION_START', 'OPERATION_CANCEL'],
    interactionTools: [{ kind: 'FACILITY', operationKey: 'operation', operationNodeId: 41, nodeId: 41, title: '工具任务' },
      { kind: 'FACILITY', operationKey: 'instant', nodeId: 42, title: '直接工具' }] };
  const { instance, requests } = page(view, (url, command) => ({ code: 200, data: { activityId: command.activityId,
    requestId: command.requestId, action: command.action, receiptId: 1, revision: 13, outcome: 'APPLIED', result: {} } }));
  assert.equal(instance.data.themeBag.actionRows[0].operation, 'OPERATION_START');
  assert.equal(instance.data.themeBag.actionRows[1].operation, 'FACILITY_USE');
  await instance.onThemeGameplayAction({ currentTarget: { dataset: { index: 0 } } });
  assert.deepEqual(requests[0].data.payload, { operationKind: 'FACILITY', operationKey: 'operation' });
  instance._applyThemeView({ ...view, activeOperation: { operationKey: 'operation', nodeId: 41, verified: false } });
  assert.equal(instance.data.themeBag.actionRows[0].operation, 'OPEN_OPERATION');
  await instance.onThemeGameplayAction({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(instance.data.advancedPlay.node.nodeId, 41);
});

test('个人局待领奖励走 RECEIVE，设施 noEffect 是成功而非失败', async () => {
  const view = { sessionId: 7, version: 8, status: 'ACTIVE', pending: { reward: { name: '奖励', quantity: 1 } } };
  const { instance, requests } = page(view, url => ({ code: 200, data: url.endsWith('gameplay-action')
    ? { ...view, settlement: { noEffect: true } } : view }));
  assert.equal(instance.data.themeBag.pendingRows[0].name, '奖励');
  await instance._themeCommand('RECEIVE', 'reward');
  assert.equal(requests[0].url, '/api/play/theme/action');
  assert.equal(requests[0].data.operation, 'RECEIVE');
  await instance._themeGameplayCommand({ operation: 'FACILITY_USE', contentId: 'f' });
  assert.ok(toasts.includes('已满，未消耗'));
  assert.equal(instance.data.themeBagError, '');
});

test('团队命令回执身份不匹配时保持待核对，同一 requestId 重试', async () => {
  const team = { scope: 'TEAM', sessionId: 17, activityId: 5, revision: 12, authorityEpoch: 4, status: 'RUNNING', availableActions: ['ITEM_RECEIVE'] };
  let calls = 0;
  const { instance, requests } = page(team, (url, command) => ({ code: 200, data: { activityId: 5,
    requestId: ++calls === 1 ? 'other-request' : command.requestId, action: command.action,
    receiptId: 1, revision: 13, outcome: 'APPLIED', result: {} } }));
  assert.equal(await instance._themeCommand('RECEIVE', 'reward'), false);
  assert.equal(instance.data.themeBagUnknown, true);
  assert.equal(await instance.retryThemeBagAction(), true);
  assert.equal(requests[0].data.requestId, requests[1].data.requestId);
});

test('玩家页呈现费用选择与挑战入口，并保留点击反馈', () => {
  const root = path.join(__dirname, '../../pages/play');
  const wxml = fs.readFileSync(path.join(root, 'theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /themeCostChoice\.row\.costOptions/);
  assert.match(wxml, /bindtap="openThemeChallenge"/);
  assert.match(wxml, /challengeProgressLabel/);
  assert.doesNotMatch(wxml, /item\.stat === 'HP' \? '生命值'/);
});
