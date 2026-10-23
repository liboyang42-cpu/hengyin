// 任务资源回执（task-resource-receipt）：开始任务不预留、不扣费；只有 QUEST_RESOLVE 真实结算（未被拦截）才按结算输出显示已扣数额。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const gameplay = require('../../pages/play/utils/theme-gameplay.js');
const backpack = require('../../pages/play/utils/theme-backpack.js');

const view = { hpName: '体力', contents: [{ id: 'food', name: '补给', kind: 'ITEM', quantity: 2 }], attributes: [{ key: 'counter.cold', label: '体温' }] };
const WXML = fs.readFileSync(path.join(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');

test('进行中的任务卡说明不扣费用，已选费用只是所选方式', () => {
  const rows = gameplay.actionRows({ scope: 'TEAM', availableActions: ['QUEST_RESOLVE', 'QUEST_CANCEL'],
    gameplay: { activeQuest: 'q1', activeQuestCostOption: 0 },
    quests: [{ questKey: 'q1', nodeId: 11, costOptions: [{ label: '补给', resourceCosts: [{ itemId: 'food', quantity: 1 }], affordable: true }] }, { questKey: 'q2', nodeId: 12 }],
    ...view }, []);
  assert.equal(rows[0].frozenText, '补给 · 补给 ×1');
  assert.equal(rows[0].chargeNote, '任务进行中不扣费用，结算时才按实际扣除');
  assert.equal(rows[1].chargeNote, '');
  assert.match(WXML, /wx:if="\{\{item\.chargeNote\}\}">\{\{item\.chargeNote\}\}/);
  assert.doesNotMatch(WXML, /已预留|已释放/);
});

test('取消后说明未扣除任何费用；开始任务不产生扣费文案', () => {
  assert.equal(gameplay.questReceiptText('QUEST_CANCEL', { cancelledQuest: 'q1' }, view), '任务已取消，未扣除任何费用');
  assert.equal(gameplay.questReceiptText('QUEST_START', { activeQuest: 'q1', costOption: 0 }, view), '');
});

test('只按真实结算输出列出已扣数额，不从费用标签或 APPLIED 推断', () => {
  const settled = { settlementKey: 'quest:q1', resourceCosts: { food: 1 }, statDeltas: { 'sys.hp': -2, 'counter.cold': -1 }, grants: {} };
  assert.equal(gameplay.questReceiptText('QUEST_RESOLVE', settled, view), '任务已结算，本次实际扣除：体力 2 · 体温 1 · 补给 ×1');
  // 被拦截的结算（结局未落定、费用拦截、竞争失败）不显示任何扣费
  assert.equal(gameplay.questReceiptText('QUEST_RESOLVE', { attemptId: 'a', outcome: 'X', settled: false }, view), '');
  assert.equal(gameplay.questReceiptText('QUEST_RESOLVE', { ...settled, status: 'BLOCKED_COST' }, view), '');
  assert.equal(gameplay.questReceiptText('QUEST_RESOLVE', { ...settled, competitionLost: true }, view), '');
  // 没有结算输出（只有 APPLIED 或 costLabel）时不推断
  assert.equal(gameplay.questReceiptText('QUEST_RESOLVE', { outcome: 'APPLIED', costLabel: '补给 ×1' }, view), '');
  assert.equal(gameplay.questReceiptText('QUEST_RESOLVE', { settlementKey: 'quest:q1', resourceCosts: {}, statDeltas: {} }, view), '');
});

test('队伍结算成功后回执来自服务端 result，并写入可读回执行', async () => {
  global.wx = { showToast() {} };
  const toasts = [];
  global.wx.showToast = options => toasts.push(options.title);
  const bag = { scope: 'TEAM', sessionId: 50, activityId: 5, teamId: 90, version: 1, revision: 3, authorityEpoch: 1, ...view };
  const instance = {
    data: { ...JSON.parse(JSON.stringify(backpack.data)), activityId: 5, themeBag: backpack.present(bag, []) },
    setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.split('.'); let at = this.data; for (const part of parts.slice(0, -1)) at = at[part]; at[parts.at(-1)] = value; } },
    _sessionParams() { return { activityId: 5 }; }, _loadPlayerGameModule() { return Promise.resolve(true); },
    refreshThemeBackpack() { return Promise.resolve(true); }, refreshRouteState() { return Promise.resolve(true); },
  };
  let sent;
  Object.assign(instance, backpack.methods((url, method, data) => { sent = data; return Promise.resolve({ code: 200, data: {
    activityId: 5, requestId: data.requestId, action: data.action, receiptId: 9, revision: 4, outcome: 'APPLIED', result: { cancelledQuest: 'q1' } } }); }));
  assert.equal(await instance._themeGameplayCommand({ operation: 'QUEST_CANCEL', contentId: 'q1' }), true);
  assert.equal(sent.action, 'QUEST_CANCEL');
  assert.equal(instance.data.themeQuestReceiptText, '任务已取消，未扣除任何费用');
  assert.ok(toasts.includes('任务已取消，未扣除任何费用'));
});
