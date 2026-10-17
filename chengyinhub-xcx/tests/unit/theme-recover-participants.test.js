const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const bag = require('../../pages/play/utils/theme-backpack.js');
const gameplay = require('../../pages/play/utils/theme-gameplay.js');
const fixture = require('./fixtures/theme-recover-participants.json');
const copy = value => JSON.parse(JSON.stringify(value));
const click = dataset => ({ currentTarget: { dataset } });
global.wx = { showToast() {} };
function page(view, responder) {
  const calls = [];
  const p = { data: { ...copy(bag.data), topicId: 9, nodes: [], themeBag: bag.present(copy(view), []) },
    setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.split('.'); let at = this.data; for (const part of parts.slice(0, -1)) at = at[part]; at[parts.at(-1)] = value; } },
    _sessionParams() { return {}; }, _loadPlayerGameModule() { return Promise.resolve(true); } };
  Object.assign(p, bag.methods((url, method, data) => { calls.push({ url, method, data: copy(data) }); return Promise.resolve(responder(url, data)); }));
  p.refreshThemeBackpack = () => Promise.resolve(true);
  return { p, calls };
}
function receipt(command, result) { return { code: 200, data: { activityId: command.activityId, requestId: command.requestId, action: command.action,
  receiptId: 9, revision: 13, outcome: 'APPLIED', result } }; }

test('U01 个人/团队恢复行采用服务器名称、可用性、次数和费用，无本地推算', () => {
  for (const view of [fixture.personal, fixture.team]) {
    const rows = gameplay.actionRows(view, []).filter(row => row.kind === 'RECOVERY');
    assert.equal(rows[0].title, '休息补充'); assert.equal(rows[0].operation, 'RECOVER'); assert.equal(rows[0].enabled, true);
    assert.equal(rows[0].remainingText, view.scope === 'TEAM' ? '剩 2 次（本队）' : '剩 2 次（本人）');
    assert.equal(rows[1].enabled, false); assert.equal(rows[1].disabledText, '次数已用完');
    const nested = copy(view); nested.recoveries[0].actionSummary = { enabled: false, disabledReason: 'INSUFFICIENT_RESOURCES', costs: { hpCost: 2 },
      shortfall: [{ type: 'STAT', key: 'sys.hp', missing: 1 }] };
    const row = gameplay.actionRows(nested, []).find(row => row.kind === 'RECOVERY');
    assert.equal(row.enabled, false); assert.equal(row.remainingText, ''); assert.equal(row.costText, '体力 2'); assert.equal(row.disabledText, '资源不足 · 缺少体力 1');
  }
});

for (const scope of ['PLAYER', 'TEAM']) test('RECOVER ' + scope + ' 精确参数，不附 costOption/participants', async () => {
  const view = scope === 'PLAYER' ? fixture.personal : fixture.team;
  const { p, calls } = page(view, (url, command) => scope === 'PLAYER' ? { code: 200, data: { ...copy(view), version: 9, settlement: fixture.recoveryResults[2] } } : receipt(command, fixture.recoveryResults[2]));
  const index = p.data.themeBag.actionRows.findIndex(row => row.kind === 'RECOVERY' && row.enabled);
  assert.equal(await p.onThemeGameplayAction(click({ index })), true);
  const command = calls[0].data;
  if (scope === 'PLAYER') { assert.equal(calls[0].url, '/api/play/theme/gameplay-action'); assert.equal(command.operation, 'RECOVER'); assert.equal(command.contentId, 'rest'); }
  else { assert.equal(calls[0].url, '/api/game/session/command'); assert.equal(command.action, 'RECOVER'); assert.deepEqual(command.payload, { recoveryKey: 'rest' }); }
  assert.equal(p.data.themeRecoveryText, '恢复体力 2，已消耗一次，剩余 1 次');
  assert.equal(command.costOption, undefined); assert.equal(command.participants, undefined); assert.equal(p.data.themeCostChoice, null);
});

for (const consumed of [false, true]) for (const scope of ['PLAYER', 'TEAM']) test('RECOVER 满值策略 ' + consumed + ' / ' + scope + ' 使用 W2 文案', async () => {
  const view = scope === 'PLAYER' ? fixture.personal : fixture.team;
  const result = fixture.recoveryResults[consumed ? 1 : 0];
  const { p } = page(view, (url, command) => scope === 'PLAYER' ? { code: 200, data: { ...copy(view), version: 9, settlement: result } } : receipt(command, result));
  await p._themeGameplayCommand({ operation: 'RECOVER', contentId: 'rest' });
  assert.equal(p.data.themeRecoveryText, consumed ? '体力已满，未产生效果，已消耗一次，剩余 1 次' : '体力已满，未产生效果，未消耗，剩余 2 次');
});

test('恢复重进只读取最新服务器行；禁用/结束不写', async () => {
  const view = copy(fixture.personal); const { p, calls } = page(view, () => { throw Error('must not write'); });
  p._applyThemeView({ ...view, recoveries: view.recoveries.map(row => ({ ...row, enabled: false, disabledReason: 'LIMIT_REACHED', remainingCount: 0 })) });
  assert.equal(p.data.themeBag.actionRows[0].remainingText, '剩 0 次（本人）');
  assert.equal(await p.onThemeGameplayAction(click({ index: 0 })), false); assert.equal(calls.length, 0);
  assert.equal(gameplay.actionRows({ ...view, status: 'COMPLETED' }, [])[0].enabled, false);
});

test('PARTICIPANTS 本队预选不可取消，其他队伍逐项选择，禁用目录不可选', async () => {
  const { p, calls } = page(fixture.team, () => ({ code: 200, data: copy(fixture.preview) }));
  await p.onThemeGameplayAction(click({ index: 0 }));
  p.selectThemeCostOption(click({ index: 0 })); await p.confirmThemeCostChoice();
  assert.deepEqual(p.data.themeRewardPreview.participantRows.map(row => row.selected), [true, false, false]);
  assert.equal(p.data.themeRewardPreview.participantRows[0].required, true);
  assert.deepEqual(calls[0].data.payload.participants, [1]);
  assert.equal(await p.selectThemeParticipant(click({ id: 1 })), false);
  assert.equal(await p.selectThemeParticipant(click({ id: 3 })), false); assert.equal(calls.length, 1);
  await p.selectThemeParticipant(click({ id: 2 })); assert.deepEqual(calls[1].data.payload.participants, [1, 2]);
  assert.equal(await p._themeGameplayCommand({ operation: 'FACILITY_USE', contentId: 'shelter', participants: [2] }), false); assert.equal(calls.length, 2);
});

test('目录缺少付费本队不能编造本队条目、预览或提交', async () => {
  const view = copy(fixture.team); view.interactionTools[0].participantCatalog.shift();
  const { p, calls } = page(view, () => { throw Error('must not write'); });
  await p._prepareThemeGameplayAction({ operation: 'FACILITY_USE', contentId: 'shelter' }, p.data.themeBag.actionRows[0]);
  assert.match(p.data.themeRewardPreview.error, /缺少.*本队/); assert.equal(calls.length, 0);
  await p.selectThemeParticipant(click({ id: 2 })); assert.match(p.data.themeRewardPreview.error, /本队必须参与/); assert.equal(calls.length, 0);
  assert.equal(await p.confirmThemeRewardPreview(), false);
});

test('参与名单改变重新读取报价，预览和 FACILITY 正式命令沿用同一份名单/费用/版本', async () => {
  const { p, calls } = page(fixture.team, (url, command) => url.endsWith('/preview') ? { code: 200, data: copy(command.payload.participants && command.payload.participants.length === 2 ? fixture.previewBoth : fixture.preview) } : receipt(command, {}));
  await p._prepareThemeGameplayAction({ operation: 'FACILITY_USE', contentId: 'shelter', costOption: 0 }, p.data.themeBag.actionRows[0]);
  assert.deepEqual(calls[0].data.payload, { facilityKey: 'shelter', costOption: 0, participants: [1] });
  await p.selectThemeParticipant(click({ id: 2 }));
  assert.deepEqual(calls[1].data.payload.participants, [1, 2]);
  assert.equal(p.data.themeRewardPreview.pack.previewVersion, 'participants-2');
  assert.deepEqual(p.data.themeRewardPreview.pack.owners.map(row => row.finalUsed), [2, 3]);
  assert.equal(await p.confirmThemeRewardPreview(), true);
  const command = calls.at(-1).data;
  assert.equal(command.action, 'FACILITY_USE'); assert.deepEqual(command.payload, { facilityKey: 'shelter', costOption: 0, discardRewards: [], previewVersion: 'participants-2', participants: [1, 2] });
  assert.equal(command.expectedRevision, 12); assert.equal(command.authorityEpoch, 4);
});

test('快速更换名单忽略旧报价；必选本队不能取消，其他队伍可移除并重读', async () => {
  const pending = []; const { p, calls } = page(fixture.team, () => new Promise(resolve => pending.push(resolve)));
  const first = p._prepareThemeGameplayAction({ operation: 'FACILITY_USE', contentId: 'shelter' }, p.data.themeBag.actionRows[0]);
  const second = p.selectThemeParticipant(click({ id: 2 }));
  const latest = copy(fixture.previewBoth); latest.result.previewVersion = 'new-list';
  pending[1]({ code: 200, data: latest }); await second; pending[0]({ code: 200, data: copy(fixture.preview) }); await first;
  assert.equal(p.data.themeRewardPreview.pack.previewVersion, 'new-list'); assert.deepEqual(p.data.themeRewardPreview.input.participants, [1, 2]);
  assert.equal(await p.selectThemeParticipant(click({ id: 1 })), false); assert.equal(calls.length, 2);
  const third = p.selectThemeParticipant(click({ id: 2 })); pending[2]({ code: 200, data: copy(fixture.preview) }); await third;
  assert.deepEqual(calls[2].data.payload.participants, [1]); assert.equal(p.data.themeRewardPreview.pack.previewVersion, 'participants-1');
});

for (const missing of ['participantCatalog', 'payerRequired']) test('旧服务器缺 ' + missing + ' 不造队伍选择或写命令', async () => {
  const view = copy(fixture.team); delete view.interactionTools[0][missing];
  const { p, calls } = page(view, () => { throw Error('must not write'); });
  await p._prepareThemeGameplayAction({ operation: 'FACILITY_USE', contentId: 'shelter' }, p.data.themeBag.actionRows[0]);
  assert.equal(p.data.themeRewardPreview.participantRows, undefined); assert.match(p.data.themeRewardPreview.error, /目录尚未加载/);
  assert.equal(await p._themeGameplayCommand({ operation: 'FACILITY_USE', contentId: 'shelter', participants: [1] }), false); assert.equal(calls.length, 0);
});

for (const targetScope of ['OWNER', 'WORLD']) test(targetScope + ' 预览/正式命令不附 participants', async () => {
  const view = copy(fixture.team); view.interactionTools[0].targetScope = targetScope;
  const { p, calls } = page(view, (url, command) => url.endsWith('/preview') ? { code: 200, data: copy(fixture.preview) } : receipt(command, {}));
  await p._readThemeRewardPreview({ operation: 'FACILITY_USE', contentId: 'shelter', participants: [1, 2] });
  assert.equal(await p.confirmThemeRewardPreview(), true); assert.equal(calls.length, 2);
  assert.ok(calls.every(row => !Object.hasOwn(row.data.payload, 'participants')));
});

test('目录仅接受真实 eligible 整数队伍，拒绝重复/未知/字符串', () => {
  const view = bag.present(copy(fixture.team), []);
  for (const ids of [[1, 1], [1, 99], ['1'], [1, 3]]) assert.match(gameplay.participantChoice(view, { operation: 'FACILITY_USE', contentId: 'shelter', participants: ids }).error, /不可用/);
  assert.match(gameplay.participantChoice(view, { operation: 'FACILITY_USE', contentId: 'shelter' }).error, /请选择/);
});

test('参与队伍列表沿用背包分组/徽标/按压反馈，恢复费用为只读展示', () => {
  const wxml = fs.readFileSync(require.resolve('../../pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /themeRewardPreview.participantRows/);
  assert.match(wxml, /bindtap="selectThemeParticipant"[^>]+hover-class="cy-pressed"[^>]+hover-stay-time="80"/);
  assert.match(wxml, /item.kind === 'RECOVERY' && item.costText/);
});

test('参与报价过期保持原名单重读，不能自动再次正式结算', async () => {
  let previews = 0;
  const { p, calls } = page(fixture.team, (url, command) => {
    if (url.endsWith('/preview')) { const quote = copy(fixture.previewBoth); quote.result.previewVersion = ++previews === 1 ? 'old-quote' : 'new-quote'; return { code: 200, data: quote }; }
    const failed = receipt(command, { reason: '奖励预览已过期' }); failed.data.outcome = 'FAILED'; return failed;
  });
  await p._readThemeRewardPreview({ operation: 'FACILITY_USE', contentId: 'shelter', costOption: 0, participants: [1, 2] });
  assert.equal(await p.confirmThemeRewardPreview(), false);
  assert.equal(p.data.themeRewardPreview.pack.previewVersion, 'new-quote'); assert.deepEqual(p.data.themeRewardPreview.input.participants, [1, 2]);
  assert.deepEqual(calls.map(row => row.url), ['/api/game/session/shared/preview', '/api/game/session/command', '/api/game/session/shared/preview']);
  assert.equal(calls[1].data.payload.previewVersion, 'old-quote'); assert.deepEqual(calls[2].data.payload.participants, [1, 2]);
});

test('PLAN 即使 targetScope=PARTICIPANTS 也不进入设施选择器', () => {
  const view = copy(fixture.team); view.interactionTools.push({ kind: 'PLAN', endingKey: 'ending', operationKey: 'ending', targetScope: 'PARTICIPANTS' });
  const projected = bag.present(view, []);
  assert.ok(!projected.actionRows.some(row => row.key === 'ending'));
  assert.deepEqual(gameplay.participantChoice(projected, { operation: 'PLAN_EXECUTE', contentId: 'ending', participants: [1, 2] }), {});
});

// GenericRouteRuntimeServiceTest.participantCatalogUsesOnlyCommandScopeTeamsAndTrustedCurrentArrival.
test('W2 serializer 目录原样命名/资格，不借 tradeTargets 或本地队伍补全', async () => {
  const view = copy(fixture.team); view.teamId = 90; view.interactionTools[0].participantCatalog = copy(fixture.serializerCatalog);
  view.world.tradeTargets = [{ teamId: 999, enabled: true }];
  const quote = copy(fixture.preview); quote.result.rewardOwners[0].ownerId = 90;
  const { p, calls } = page(view, () => ({ code: 200, data: quote }));
  await p._prepareThemeGameplayAction({ operation: 'FACILITY_USE', contentId: 'shelter' }, p.data.themeBag.actionRows[0]);
  const rows = p.data.themeRewardPreview.participantRows;
  assert.deepEqual(rows.map(row => row.teamId), [90, 91, 92, 93]); assert.deepEqual(calls[0].data.payload.participants, [90]);
  assert.equal(rows[0].label, '真实队伍90'); assert.equal(rows[2].reason, '尚未到达'); assert.equal(rows[3].reason, '该队已完成');
  assert.equal(await p.selectThemeParticipant(click({ id: 92 })), false); assert.equal(calls.length, 1);
});

test('同名任务、设施和恢复行有独立渲染标识，命令 key 不变', () => {
  const view = copy(fixture.team);
  view.quests = [{ questKey: 'rest', enabled: true, title: '任务' }];
  view.interactionTools[0].operationKey = 'rest'; delete view.interactionTools[0].facilityKey;
  const rows = gameplay.actionRows(view, []).filter(row => row.key === 'rest');
  assert.equal(rows.length, 3); assert.equal(new Set(rows.map(row => row.rowKey)).size, 3);
  assert.ok(rows.every(row => row.key === 'rest'));
  assert.match(fs.readFileSync(require.resolve('../../pages/play/theme-backpack.wxml'), 'utf8'), /wx:for="\{\{themeBag.actionRows\}\}" wx:key="rowKey"/);
});

test('团队 session view 投影保留恢复和参与目录，重进只采用最新回读', () => {
  const { p, calls } = page(fixture.personal, () => { throw Error('must not write'); });
  const player = copy(fixture.team); delete player.scope;
  player.recoveries[0] = { ...player.recoveries[0], enabled: false, remainingCount: 0, disabledReason: 'LIMIT_REACHED' };
  player.interactionTools[0].participantCatalog[1].eligible = false;
  player.interactionTools[0].participantCatalog[1].disabledReason = 'NOT_ARRIVED';
  p._applyThemeTeamView(player, { sessionId: 7, activityId: 5, revision: 13, status: 'RUNNING', availableActions: ['RECOVER', 'FACILITY_USE'] });
  const recovery = p.data.themeBag.actionRows.find(row => row.kind === 'RECOVERY');
  assert.equal(recovery.enabled, false); assert.equal(recovery.remainingText, '剩 0 次（本队）');
  const choice = gameplay.participantChoice(p.data.themeBag, { operation: 'FACILITY_USE', contentId: 'shelter', participants: [1] });
  assert.equal(choice.rows[1].eligible, false); assert.equal(choice.rows[1].reason, '尚未到达'); assert.equal(calls.length, 0);
});
