const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
global.wx = { showToast() {}, getStorageSync: () => '', setStorageSync() {} };
global.getApp = () => ({ globalData: {} });
const gameplay = require('../../pages/play/utils/theme-gameplay.js');
const backpack = require('../../pages/play/utils/theme-backpack.js');

const quest = { questKey: 'q1', nodeId: 41, title: '采样',
  costOptions: [{ label: '口粮', resourceCosts: [{ itemId: 'food', quantity: 1 }], affordable: true }, { label: '体力', hpCost: 2, affordable: true }] };
const base = { scope: 'TEAM', sessionId: 17, activityId: 5, revision: 12, authorityEpoch: 4, status: 'RUNNING', teamId: 9,
  contents: [{ id: 'food', kind: 'ITEM', name: '口粮', quantity: 1 }], quests: [quest] };

function page(view) {
  const requests = [];
  const instance = {
    data: { ...JSON.parse(JSON.stringify(backpack.data)), nodes: [], themeBag: backpack.present(view, []) },
    setData(patch) { Object.assign(this.data, patch); },
    refreshThemeBackpack() { return Promise.resolve(true); },
    refreshRouteState() { return Promise.resolve(true); },
  };
  Object.assign(instance, backpack.methods((url, method, data) => { requests.push(data); return Promise.resolve({ code: 200, data: {
    activityId: data.activityId, requestId: data.requestId, action: data.action, receiptId: 1, revision: 13, outcome: 'APPLIED', result: {} } }); }));
  return { instance, requests };
}

test('X003 任务卡三态：开始后显示预留中，结算成功显示已扣，取消显示已释放', async () => {
  const idle = gameplay.actionRows({ ...base, availableActions: ['QUEST_START'] }, []);
  assert.equal(idle[0].reservationText, '', '未开始时没有预留');
  const active = { ...base, availableActions: ['QUEST_RESOLVE', 'QUEST_CANCEL'], gameplay: { activeQuest: 'q1', activeQuestCostOption: 1 } };
  const rows = gameplay.actionRows(active, []);
  assert.equal(rows[0].reservationText, '预留中：生命值 2（成功才扣，取消即释放）');
  assert.deepEqual(gameplay.reservationReceipt(rows, { operation: 'QUEST_RESOLVE', contentId: 'q1' }), { key: 'q1', state: 'CHARGED', text: '采样 · 已扣：生命值 2' });
  assert.deepEqual(gameplay.reservationReceipt(rows, { operation: 'QUEST_CANCEL', contentId: 'q1' }), { key: 'q1', state: 'RELEASED', text: '采样 · 已释放：生命值 2，未扣除' });
  assert.equal(gameplay.reservationReceipt(rows, { operation: 'FACILITY_USE', contentId: 'q1' }), null);
  // 固定费用同样显示预留
  const fixed = gameplay.actionRows({ ...base, quests: [{ questKey: 'q2', nodeId: 42, title: '巡逻', costs: { actionCost: 1 } }], availableActions: ['QUEST_RESOLVE'], gameplay: { activeQuest: 'q2' } }, []);
  assert.equal(fixed[0].reservationText, '预留中：行动次数 1（成功才扣，取消即释放）');
  // 已结束的局不显示预留
  assert.equal(gameplay.actionRows({ ...active, status: 'COMPLETED' }, [])[0].reservationText, '');

  const { instance, requests } = page(active);
  await instance._themeGameplayCommand({ operation: 'QUEST_CANCEL', contentId: 'q1' });
  assert.equal(requests[0].action, 'QUEST_CANCEL');
  assert.equal(instance.data.themeReservationReceipt.state, 'RELEASED');
});

test('X003 预留与回执文案已接入任务页', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /item\.reservationText/);
  assert.match(wxml, /themeReservationReceipt\.text/);
});

test('X005 结果卡说明效果作用于谁：参与者/全场只含已到达者，受益者不扣资源', () => {
  const { targetScopeText } = require('../../pages/play/utils/theme-gameplay.js');
  assert.match(targetScopeText('PARTICIPANTS', { scope: 'TEAM' }), /已到达.*未到达者不受益.*受益者不扣资源/);
  assert.match(targetScopeText('WORLD', { scope: 'TEAM' }), /全场已到达成员.*封顶/);
  assert.equal(targetScopeText('OWNER', { scope: 'TEAM' }), '效果只作用于本队');
  assert.equal(targetScopeText('OWNER', { scope: 'PLAYER' }), '效果只作用于本人');
  assert.equal(targetScopeText(undefined, {}), '');
  const wxml = require('node:fs').readFileSync(require('node:path').resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /item\.targetScopeText/);
});
