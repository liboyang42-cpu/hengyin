const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const gameplay = require('../../pages/play/utils/theme-gameplay.js');
const backpack = require('../../pages/play/utils/theme-backpack.js');
const rewards = require('../../pages/play/utils/theme-rewards.js');
const fixture = require('./fixtures/theme-tool-status.json');
const copy = value => JSON.parse(JSON.stringify(value));
global.wx = { showToast() {} };

const reasons = {
  LIMIT_REACHED: '次数已用完', INSUFFICIENT_RESOURCES: '资源不足', NOT_ARRIVED: '尚未到达',
  PROOF_PENDING: '待核验', NOT_CAPTAIN: '仅队长可操作', SESSION_ENDED: '本局已结束',
  CHALLENGE_LIMIT: '挑战次数已用完', TEAM_COMPLETED: '本队已完成', COUNTERPART_COMPLETED: '对方已完成',
  RULE_BLOCKED: '前置条件未满足', TASK_ACTIVE: '请先完成或取消当前任务', SESSION_PAUSED: '本局已暂停',
  WORLD_LOCKED: '当前表决已锁定',
};
for (const source of fixture.reasons) test('T07 禁用 ' + source.disabledReason + ' 使用行级状态及中文原因', () => {
  const rows = gameplay.actionRows({ ...copy(fixture.team), interactionTools: [source] }, []);
  const row = rows.find(row => row.kind === 'FACILITY');
  assert.equal(row.enabled, false); // 全局 FACILITY_USE 可用不代表每个工具可用。
  assert.equal(row.disabledReason, source.disabledReason);
  assert.ok(row.disabledText.startsWith(reasons[source.disabledReason]));
  assert.equal(row.remainingCount, source.remainingCount);
  assert.equal(row.count, source.count);
  assert.equal(row.limit, source.limit);
});

test('OWNER 本人/本队与 WORLD 全场次数只回显 remainingCount，缺字段不推算', () => {
  assert.equal(gameplay.actionRows(fixture.personal, [])[0].remainingText, '剩 1 次（本人）');
  const team = gameplay.actionRows(fixture.team, []);
  assert.equal(team[1].remainingText, '剩 1 次（全场）');
  assert.equal(team[2].remainingText, '剩 2 次（本队）');
  assert.equal(gameplay.toolStatus({ limit: 9, count: 2, countScope: 'OWNER' }, fixture.team).remainingText, '');
  assert.equal(gameplay.toolStatus({ limit: 9, count: 2, remainingCount: 3, countScope: 'OWNER' }, fixture.team).remainingText, '剩 3 次（本队）');
  assert.equal(gameplay.toolStatus({ remainingCount: 0, countScope: 'OWNER' }, fixture.team).remainingText, '剩 0 次（本队）');
});

test('费用缺口用公开目录及主题名称；冻结选项只显示已选组的服务端缺口', () => {
  const source = copy(fixture.reasons.find(row => row.disabledReason === 'INSUFFICIENT_RESOURCES'));
  assert.equal(gameplay.toolStatus(source, fixture.team).disabledText, '资源不足 · 物资：缺少口粮 2 · 缺少体力 2');
  source.costOptions.push({ label: '备用', affordable: false, shortfall: [{ type: 'ITEM', key: 'spare', missing: 1 }] });
  assert.equal(gameplay.toolStatus(source, fixture.team, 1).disabledText, '资源不足 · 备用：缺少备用物资 1');
  source.shortfall = [{ type: 'ITEM', key: 'spare', missing: 4 }];
  assert.equal(gameplay.toolStatus(source, fixture.team).disabledText, '资源不足 · 缺少备用物资 4');
});

test('未知原因显示原始服务器消息；旧服工具仍可按原能力打开且不虚构次数或核验', () => {
  assert.equal(gameplay.toolStatus({ disabledReason: 'NEW_REASON', disabledMessage: '服务器原文' }, {}).disabledText, '服务器原文');
  assert.equal(gameplay.toolStatus({ disabledReason: 'UNKNOWN' }, {}).disabledText, 'UNKNOWN');
  const row = gameplay.actionRows({ tools: [{ operationKey: 'f' }], allowedActions: [{ operation: 'FACILITY_USE', contentId: 'f' }] }, [])[0];
  assert.equal(row.enabled, true); assert.equal(row.trustedPass, false); assert.equal(row.remainingText, '');
});

test('公开目录命名未持有的费用、奖励预览、待领与结束补领，不把目录当库存', async () => {
  for (const view of [copy(fixture.team), copy(fixture.personal)]) {
    assert.equal(gameplay.valueName('spare', view), '备用物资');
    assert.equal(gameplay.amounts({ resourceCosts: [{ itemId: 'spare', quantity: 2 }] }, view)[0], '备用物资 ×2');
    const pack = { previewVersion: 'server-preview', overflow: false, rewardOwners: [{ ownerId: 1, capacity: 6, finalUsed: 2, overflow: false, grants: { spare: 1 }, rewardSources: [{ source: 'event:supply', itemId: 'spare', quantity: 1 }] }] };
    const preview = rewards.preview(view.scope === 'TEAM' ? { settlement: pack } : pack, view);
    assert.equal(preview.owners[0].sources[0].name, '备用物资');
    assert.equal(preview.owners[0].rewards[0].name, '备用物资');
    const bag = backpack.present(view, []);
    assert.equal(bag.pendingRows[0].name, '备用物资'); assert.equal(bag.items.length, 0);
    const closed = { ...view, status: 'COMPLETED', availableActions: ['ITEM_RECEIVE'], allowedActions: [{ operation: 'RECEIVE', contentId: 'event:supply' }] };
    const page = { data: { themeBag: backpack.present(closed, []) }, setData(patch) { Object.assign(this.data, patch); } };
    Object.assign(page, backpack.methods(() => { throw new Error('展示不可写入'); }));
    await page.openThemePendingClaim('event:supply');
    assert.equal(page.data.themePendingClaim.reward.name, '备用物资');
    assert.equal(page.data.themePendingClaim.owned.length, 0);
  }
  assert.equal(gameplay.valueName('food', { contents: [{ id: 'food', name: '旧服名称' }] }), '旧服名称');
  assert.equal(gameplay.valueName('unknown', {}), '物资');
});

test('回读替换恢复行状态：重进、可信通过、重启实例均不沿用手机次数或通过', () => {
  const page = { data: { themeBag: backpack.present(fixture.team, []), nodes: [] }, setData(patch) { Object.assign(this.data, patch); } };
  Object.assign(page, backpack.methods(() => { throw new Error('不发写请求'); }));
  page._applyThemeView(copy(fixture.team));
  assert.equal(page.data.themeBag.actionRows[0].trustedPass, true);
  const pending = { ...copy(fixture.team), quests: [fixture.questPending] };
  page._applyThemeView(pending);
  assert.equal(page.data.themeBag.actionRows[0].trustedPass, false);
  assert.equal(page.data.themeBag.actionRows[0].enabled, false);
  assert.equal(page.data.themeBag.actionRows[0].openable, true);
  const fresh = { ...copy(fixture.team), quests: [fixture.questRestarted], gameplay: {} };
  page._applyThemeView(fresh);
  assert.equal(page.data.themeBag.actionRows[0].trustedPass, false);
  assert.equal(page.data.themeBag.actionRows[0].operation, 'QUEST_START');
  assert.deepEqual(page.data.themeBag.actionRows, backpack.present(fresh, []).actionRows);
});

test('待核验的题目可重开但不能结算；其他禁用原因不开放该入口', async () => {
  const view = { ...copy(fixture.team), quests: [fixture.questPending] };
  const page = { data: { themeBag: backpack.present(view, []) }, setData(patch) { Object.assign(this.data, patch); }, _nodeById(id) { return { nodeId: id }; } };
  const writes = [];
  Object.assign(page, backpack.methods((...args) => { writes.push(args); return Promise.resolve({ code: 500 }); }));
  page.startGame = node => page.setData({advancedPlay:{show:true,node}});
  assert.equal(await page.onThemeGameplayAction({ currentTarget: { dataset: { index: 0, open: true } } }), true);
  assert.equal(page.data.advancedPlay.node.nodeId, 11); assert.equal(writes.length, 0);
  page.data.themeBag = backpack.present({ ...view, quests: [{ ...fixture.questPending, disabledReason: 'SESSION_ENDED' }] }, []);
  assert.equal(await page.onThemeGameplayAction({ currentTarget: { dataset: { index: 0 } } }), false);
  assert.equal(writes.length, 0);
});

test('任务行整行禁用、原因/次数/核验徽标及按压反馈接入，图标用 cy-icon', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(source, /tb-row {{item.enabled \|\| item.openable \? '' : 'is-disabled'}}/);
  assert.match(source, /item\.disabledText/); assert.match(source, /item\.remainingText/);
  assert.match(source, /tb-pass" wx:if="{{item.trustedPass}}"><cy-icon name="check"/);
  assert.match(source, /bindtap="onThemeGameplayAction" aria-disabled=/);
  const taskRow = source.split('\n').find(line => line.includes('wx:for="{{themeBag.actionRows}}"'));
  assert.doesNotMatch(taskRow, />{{item.disabledReason}}<\/text>/);
});

test('工具重进恢复当前实例核验：未通过只重开题面，通过后才使用，缺口/次数不沿用本地', () => {
  const tool = { ...fixture.team.interactionTools[1], operationNodeId: 12, operationKey:'check', enabled:false, disabledReason:'PROOF_PENDING', trustedPass:false };
  const view = { ...copy(fixture.team), quests:[], activeOperation:{operationKey:'check',nodeId:12,verified:true}, interactionTools:[tool] };
  const waiting = gameplay.actionRows(view,[])[0];
  assert.equal(waiting.enabled,false);assert.equal(waiting.openable,true);assert.equal(waiting.operation,'OPEN_OPERATION');assert.equal(waiting.trustedPass,false);
  const verified = gameplay.actionRows({...view,activeOperation:{...view.activeOperation,verified:false},interactionTools:[{...tool,enabled:true,disabledReason:null,trustedPass:true}]},[])[0];
  assert.equal(verified.enabled,true);assert.equal(verified.operation,'FACILITY_USE');assert.equal(verified.trustedPass,true);
});

test('团队挑战回读两名队员的 6/10、12/10，恢复由服务器 awaitingChallenge=false 关闭挑战而不推进路线', () => {
  const page = { data:{themeBag:backpack.present(fixture.team,[]),nodes:[],playKit:{show:true,kit:{type:'ballshake'}},advancedPlay:{show:true,node:{nodeId:11}}},
    _themeChallengeNodeId:11,_themeHidden:true,setData(patch){for(const [key,value] of Object.entries(patch)){const parts=key.split('.');let at=this.data;for(const p of parts.slice(0,-1))at=at[p];at[parts.at(-1)]=value;}} };
  Object.assign(page,backpack.methods(()=>{throw new Error('刷新不可裁定成功或发路线命令');}));
  const challenge={instanceId:'one',nodeId:11,goal:10,progress:6,restoreTo:4};
  page._applyThemeView({...copy(fixture.team),awaitingChallenge:true,hp:0,challenge});
  assert.equal(page.data.playKit.kit.challenge.progress,6);assert.equal(page.data.themeBag.challengeProgressText,'6/10');
  page._applyThemeView({...copy(fixture.team),awaitingChallenge:false,hp:4,challenge:{...challenge,progress:12}});
  assert.equal(page.data.themeBag.hp,4);assert.equal(page.data.themeBag.challengeProgressText,'12/10');
  assert.equal(page.data.playKit.show,false);assert.equal(page.data.advancedPlay.show,false);assert.equal(page._themeChallengeNodeId,null);
});
