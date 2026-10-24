const test = require('node:test');
const assert = require('node:assert/strict');
const trade = require('../../pages/play/utils/theme-trade.js');
const bag = require('../../pages/play/utils/theme-backpack.js');
global.wx = { showToast() {} };

// GameplayCooperationEngineTest: nodeTradeRequiresBothTeamsAtTheSameEnabledNodeAndExplainsReadback.
const view = { scope: 'TEAM', activityId: 5, sessionId: 7, teamId: 1, revision: 12, authorityEpoch: 4,
  availableActions: ['TRADE_OPEN', 'TRADE_CONFIRM', 'TRADE_CANCEL'],
  contents: [{ id: 'wood', kind: 'ITEM', name: '木料', quantity: 1 }],
  world: { tradeTargets: [{ teamId: 2, disabled: false, nodeId: 11 }, { teamId: 3, disabled: true, disabledReason: '对方尚未到达同一可交易节点' }],
    trades: [{ tradeKey: 't1', fromTeamId: 1, toTeamId: 2, give: [{ itemId: 'wood', quantity: 1 }], take: [], version: 1, status: 'OPEN', confirmedTeams: [], disabled: false, nodeId: 11 }], locked: { wood: 1 } } };
test('恢复提示区分个人和团队支付，显式 consumed 优先于 noEffect', () => {
  assert.equal(trade.recoveryText({ noEffect: true, consumed: false, ownerId: 7 }), '已满，未消耗');
  assert.equal(trade.recoveryText({ noEffect: true, consumed: true, paidByOwnerId: 7 }), '未产生效果，仅本人支付一次');
  assert.equal(trade.recoveryText({ consumed: true, paidByOwnerId: 7 }), '工具已生效，仅本人支付一次');
  assert.equal(trade.recoveryText({ noEffect: true, consumed: true }), '未产生效果，已消耗一次');
  assert.equal(trade.recoveryText({ noEffect: true, consumed: false, paidByTeamId: 1 }), '已满，未消耗');
  assert.equal(trade.recoveryText({ noEffect: true, consumed: true, paidByTeamId: 1 }), '未产生效果，仅队伍 1 支付一次');
});
test('恢复量与剩余次数来自恢复回执，不编造支付者或使用固定数值名', () => {
  assert.equal(trade.recoveryText({ recoveryKey: 'rest', hpDelta: 2, consumed: true, remaining: 0 }, '体温'), '恢复体温 2，已消耗一次，剩余 0 次');
  assert.equal(trade.recoveryText({ recoveryKey: 'rest', hpDelta: 1, consumed: true, remaining: 1 }), '恢复生命值 1，已消耗一次，剩余 1 次');
});
function page(input = view, responder) {
  const requests = [];
  const instance = { data: { ...JSON.parse(JSON.stringify(bag.data)), nodes: [{ nodeId: 11, name: '交换站' }], themeBag: bag.present(JSON.parse(JSON.stringify(input)), [{ nodeId: 11, name: '交换站' }]) },
    setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data; for (const part of parts.slice(0, -1)) at = at[part]; at[parts.at(-1)] = value; } },
    refreshThemeBackpack() { return Promise.resolve(true); } };
  Object.assign(instance, trade.methods(async (url, method, data) => { requests.push({ url, method, data }); return responder ? responder(url, data) : { code: 500, msg: '失败' }; }));
  return { instance, requests };
}
const event = dataset => ({ currentTarget: { dataset } });
function receipt(command, result = {}) { return { code: 200, data: { activityId: command.activityId, requestId: command.requestId, action: command.action, receiptId: 9, revision: 13, outcome: 'APPLIED', result } }; }
function fill(instance) { instance.selectThemeTradeTarget(event({ id: 2 })); instance.inputThemeTradeQuantity({ ...event({ index: 0, side: 'give' }), detail: { value: '2' } }); }
function preview(data) { return { code: 200, data: { previewOnly: true, expectedRevision: 12, authorityEpoch: 4, result: { ...data.payload, fromTeamId: 1, version: 1, status: 'OPEN', confirmedTeams: [], preview: true, fromUsed: 4, toUsed: 4, capacity: 6 } } }; }

test('W1 交易读回只采用 disabled 和 nodeId，站名来自路线，不按本地到达猜资格', () => {
  const rows = trade.present(view, [{ nodeId: 11, name: '交换站' }]);
  assert.equal(rows.tradeTargets[0].stationName, '交换站');
  assert.equal(rows.tradeTargets[1].enabled, false);
  assert.equal(rows.tradeTargets[1].reason, '对方尚未到达同一可交易节点');
  assert.equal(rows.tradeRows[0].stationName, '交换站');
  assert.equal(rows.tradeLockedText, '木料 ×1');
  const unknown = trade.present({ ...view, world: { tradeTargets: [{ teamId: 2, nodeId: 11 }] } }, []);
  assert.equal(unknown.tradeTargets[0].enabled, false);
  assert.equal(unknown.tradeTargets[0].stationName, '节点资料尚未加载');
});
test('仅当前队长操作，成员只读，不可用目标不创建表单也不发送请求', async () => {
  const { instance, requests } = page({ ...view, availableActions: [] });
  fill(instance); assert.equal(instance.data.themeTradeForm, null);
  assert.equal(await instance.onThemeTradeAction(event({ key: 't1', version: 1, action: 'TRADE_CONFIRM' })), false);
  assert.equal(requests.length, 0);
  const leader = page(); leader.instance.selectThemeTradeTarget(event({ id: 3 })); assert.equal(leader.instance.data.themeTradeForm, null);
});
test('§10 正式公开目录可选对方独有物资，只用 completed 标记对方完成；旧服不猜', () => {
  const incoming = { ...view, naturallyCompleted: true, world: { ...view.world,
    itemCatalog: [{ id: 'wood', name: '木料', slotCost: 2 }, { id: 'cloth', name: '布料', slotCost: 1 }],
    tradeTargets: [{ teamId: 2, completed: false, disabled: false, nodeId: 11 }, { teamId: 3, completed: true, disabled: true, disabledReason: '交易队伍已完成最后章节' }] } };
  const { instance } = page(incoming); fill(instance);
  assert.deepEqual(instance.data.themeTradeForm.items.map(row => row.id), ['wood', 'cloth']);
  assert.equal(instance.data.themeBag.tradeTargets[0].completed, false);
  assert.equal(instance.data.themeBag.tradeTargets[1].completed, true);
  instance.inputThemeTradeQuantity({ ...event({ index: 1, side: 'take' }), detail: { value: '1' } });
  assert.deepEqual(trade.payload(instance.data.themeTradeForm).take, [{ itemId: 'cloth', quantity: 1 }]);
  const old = page({ ...view, world: { ...view.world, tradeTargets: [{ teamId: 2, disabled: true, disabledReason: '交易队伍已完成最后章节' }] } });
  assert.equal(old.instance.data.themeBag.tradeTargets[0].completed, false);
  assert.equal(old.instance.data.themeBag.tradeTargets[0].reason, '交易队伍已完成最后章节');
  const empty = page({ ...view, world: { ...view.world, itemCatalog: [] } });
  fill(empty.instance); assert.deepEqual(empty.instance.data.themeTradeForm.items, []);
});
test('交换清单整数边界，零项省略，不客户端检查库存余额', () => {
  assert.deepEqual(trade.payload({ tradeKey: 't', toTeamId: 2, items: [{ id: 'wood', name: '木料', give: '999', take: '0' }] }), { tradeKey: 't', toTeamId: 2, give: [{ itemId: 'wood', quantity: 999 }], take: [] });
  for (const quantity of ['-1', '1.5', '1000', '', 'NaN']) assert.throws(() => trade.payload({ items: [{ id: 'wood', name: '木料', give: quantity, take: '0' }] }), /木料.*整数/);
  assert.throws(() => trade.payload({ items: [{ id: 'wood', give: '0', take: '0' }] }), /至少一项/);
});
test('服务端纯预览 → 发起一次写入，冻结 revision 和纪元，输入变化废弃预览', async () => {
  const { instance, requests } = page(view, (url, data) => url.endsWith('/preview') ? preview(data) : receipt(data));
  fill(instance); assert.equal(await instance.submitThemeTrade(), false); assert.equal(requests.length, 0);
  assert.equal(await instance.previewThemeTrade(), true);
  assert.equal(instance.data.themeTradePreview.fromUsed, 4);
  assert.equal(await instance.submitThemeTrade(), true);
  assert.equal(requests.filter(row => row.url.endsWith('/command')).length, 1);
  const command = requests[1].data;
  assert.equal(command.authorityEpoch, 4); assert.equal(command.expectedRevision, 12);
  assert.equal(command.action, 'TRADE_OPEN'); assert.equal(command.payload.toTeamId, 2);
  assert.deepEqual(command.payload.give, [{ itemId: 'wood', quantity: 2 }]);
  assert.equal(instance.data.themeTradeForm, null);
  fill(instance); await instance.previewThemeTrade();
  instance.inputThemeTradeQuantity({ ...event({ index: 0, side: 'take' }), detail: { value: '1' } });
  assert.equal(instance.data.themeTradePreview, null);
});
test('预览失败保持输入并显示服务端理由，投影变化必须重新预览', async () => {
  const { instance } = page(view, () => ({ code: 500, msg: '交易后超出背包容量' }));
  fill(instance); assert.equal(await instance.previewThemeTrade(), false);
  assert.equal(instance.data.themeTradeForm.items[0].give, '2'); assert.equal(instance.data.themeTradeError, '交易后超出背包容量');
  const good = page(view, (url, data) => preview(data)); fill(good.instance); await good.instance.previewThemeTrade();
  good.instance.data.themeBag.revision++;
  assert.equal(await good.instance.submitThemeTrade(), false); assert.equal(good.requests.length, 1);
});
test('队长仅确认读回的同一交易 key/version，停用拒确认但可取消释放锁定', async () => {
  const { instance, requests } = page(view, (url, data) => receipt(data));
  assert.equal(await instance.onThemeTradeAction(event({ key: 't1', version: 0, action: 'TRADE_CONFIRM' })), false);
  assert.equal(requests.length, 0);
  await instance.onThemeTradeAction(event({ key: 't1', version: 1, action: 'TRADE_CONFIRM' }));
  assert.deepEqual(requests[0].data.payload, { tradeKey: 't1', version: 1 });
  instance.data.themeBag.tradeRows[0].disabled = true;
  assert.equal(await instance.onThemeTradeAction(event({ key: 't1', version: 1, action: 'TRADE_CONFIRM' })), false);
  assert.equal(await instance.onThemeTradeAction(event({ key: 't1', version: 1, action: 'TRADE_CANCEL' })), true);
  assert.deepEqual(requests[1].data.payload, { tradeKey: 't1', version: 1 });
});
test('预览返回不属于当前清单或不含最终负重时禁止发起', async () => {
  for (const change of [{ toTeamId: 3 }, { fromTeamId: 3 }, { give: [] }, { fromUsed: undefined }]) {
    const { instance, requests } = page(view, (url, data) => { const res = preview(data); Object.assign(res.data.result, change); return res; });
    fill(instance); assert.equal(await instance.previewThemeTrade(), false); assert.equal(await instance.submitThemeTrade(), false);
    assert.equal(requests.length, 1); assert.ok(instance.data.themeTradeForm);
  }
});
test('预览比本地投影更新时先读取投影，同步后才可发起', async () => {
  const { instance } = page(view, (url, data) => {
    if (!url.endsWith('/preview')) return receipt(data);
    const res = preview(data); res.data.expectedRevision = 13; return res;
  });
  fill(instance);
  assert.equal(await instance.previewThemeTrade(), false);
  instance.refreshThemeBackpack = async () => { instance.data.themeBag.revision = 13; };
  assert.equal(await instance.previewThemeTrade(), true);
  assert.equal(await instance.submitThemeTrade(), true);
});
test('不确定写入保留输入与 requestId；核对只 GET，未确认时不重发', async () => {
  let frozen;
  const { instance, requests } = page(view, (url, data) => {
    if (url.endsWith('/preview')) return preview(data);
    if (url.endsWith('/command')) { frozen = data; return { netFail: true }; }
    return receipt(frozen);
  });
  fill(instance); await instance.previewThemeTrade();
  assert.equal(await instance.submitThemeTrade(), false);
  assert.equal(instance.data.themeTradeUnknown, true); assert.equal(instance.data.themeTradeForm.items[0].give, '2');
  assert.equal(await instance.submitThemeTrade(), false);
  instance.closeThemeTradeForm(); assert.ok(instance.data.themeTradeForm);
  assert.equal(await instance.checkThemeTradeReceipt(), true);
  assert.equal(requests.filter(row => row.url.endsWith('/command')).length, 1);
  assert.equal(requests.at(-1).method, 'GET'); assert.equal(requests.at(-1).data.requestId, frozen.requestId);
});
test('核对回执失败或不匹配不解除锁定，业务拒绝保留表单但允许重做预览', async () => {
  const unknown = page(view, (url, data) => url.endsWith('/preview') ? preview(data) : url.endsWith('/command') ? { netFail: true } : { code: 500, msg: '稍后查询' });
  fill(unknown.instance); await unknown.instance.previewThemeTrade(); await unknown.instance.submitThemeTrade();
  await unknown.instance.checkThemeTradeReceipt(); assert.ok(unknown.instance._tradePending);
  const denied = page(view, (url, data) => url.endsWith('/preview') ? preview(data) : { code: 500, msg: '对方尚未到达同一可交易节点' });
  fill(denied.instance); await denied.instance.previewThemeTrade(); await denied.instance.submitThemeTrade();
  assert.equal(denied.instance._tradePending, null); assert.ok(denied.instance.data.themeTradeForm); assert.equal(denied.instance.data.themeTradePreview, null);
});
test('W1 恢复回执：noEffect 不等于未消耗；参与者共享恢复只显示一次支付', () => {
  assert.equal(trade.recoveryText({ facilityKey: 'recover', noEffect: true, count: 0 }), '已满，未消耗');
  assert.equal(trade.recoveryText({ facilityKey: 'recover', noEffect: true, count: 1, paidByTeamId: 1 }), '未产生效果，仅队伍 1 支付一次');
  assert.equal(trade.recoveryText({ facilityResult: { facilityKey: 'recover', count: 2, paidByTeamId: 1, targets: [1, 2] } }), '工具已生效，仅队伍 1 支付一次');
});
test('背包命令将共享恢复收费结果保留为可读回执，不显示未消耗', async () => {
  const instance = { data: { ...JSON.parse(JSON.stringify(bag.data)), themeBag: view }, setData(patch) { Object.assign(this.data, patch); } };
  Object.assign(instance, bag.methods(async (url, commandMethod, command) => receipt(command, { result: { facilityKey: 'recover', paidByTeamId: 1, targets: [1, 2], count: 1, noEffect: true } })));
  instance.refreshThemeBackpack = async () => true;
  assert.equal(await instance._themeGameplayCommand({ operation: 'FACILITY_USE', contentId: 'recover' }), true);
  assert.equal(instance.data.themeRecoveryText, '未产生效果，仅队伍 1 支付一次');
});

test('交易经公共客户端送原纪元，权威冲突保留表单、读回一次、不自动重发', async () => {
  const { instance, requests } = page(view, (url, data) => url.endsWith('/preview') ? preview(data)
    : { code: 500, msg: '本局权威版本已更新，请刷新', data: { reasonCode: 'GAME_AUTHORITY_CONFLICT' } });
  let reads = 0;
  instance.refreshThemeBackpack = async () => { reads += 1; return true; };
  fill(instance); await instance.previewThemeTrade(); await instance.submitThemeTrade();
  assert.equal(requests.find(call => call.url.endsWith('/command')).data.authorityEpoch, 4);
  assert.equal(reads, 1); assert.equal(requests.filter(call => call.url.endsWith('/command')).length, 1);
  assert.ok(instance.data.themeTradeForm); assert.equal(instance._tradePending, null);
  assert.match(instance.data.themeTradeError, /权威版本/);
});

test('合并背包保留交易锁和预览失效，并保留奖励 sheet 与团队章节无命令语义', async () => {
  const { instance, requests } = page();
  Object.assign(instance, bag.methods(async () => { throw new Error('交易待确认时不应发其他请求'); }));
  instance._tradePending = { requestId: 'frozen-trade' };
  assert.equal(await instance._themePersonalCommand('USE', 'wood'), false);
  assert.equal(await instance._themeGameplayCommand({ operation: 'FACILITY_USE', contentId: 'f' }), false);
  assert.equal(await instance._readThemeRewardPreview({ operation: 'FACILITY_USE', contentId: 'f' }), false);
  assert.equal(await instance._themeChapterCommand('CHAPTER_END', '0'), true);
  assert.equal(requests.length, 0);
  instance.data.themeRewardPreview = { input: { contentId: 'f' } };
  instance.data.themeTradePreview = { revision: 12, authorityEpoch: 4, payload: { toTeamId: 2 } };
  instance._applyThemeView({ ...view, revision: 13 });
  assert.equal(instance.data.themeTradePreview, null);
  assert.equal(instance.data.themeRewardPreview.input.contentId, 'f');
  assert.equal(instance.data.themeBag.tradeTargets[0].stationName, '交换站');
  assert.equal(instance.data.themeBag.tradeRows[0].giveText, '木料 ×1');
});

test('交易改版保留目标、数量、预览、队长确认取消与未知回执入口，沿用玩家明细和固定底栏', () => {
  const fs = require('node:fs');
  const wxml = fs.readFileSync(require.resolve('../../pages/play/theme-backpack.wxml'), 'utf8');
  for (const handler of ['selectThemeTradeTarget', 'inputThemeTradeQuantity', 'previewThemeTrade', 'submitThemeTrade', 'onThemeTradeAction', 'checkThemeTradeReceipt', 'closeThemeTradeForm']) assert.ok(wxml.includes(handler));
  assert.match(wxml, /!themeTradeForm/);
  assert.match(wxml, /show="\{\{!!themeTradeForm\}\}"[\s\S]*slot="footer"/);
  for (const field of ['stationName', 'giveText', 'takeText', 'fromUsed', 'toUsed', 'capacity', 'disabledReason', 'confirmed', 'canConfirm', 'canCancel']) assert.ok(wxml.includes(field));
  assert.doesNotMatch(wxml, /#[0-9a-fA-F]{3,8}/);
});

test('交易数量用微信数组下标路径更新，保留全部目录名称及另一侧数量', () => {
  const input = { ...view, world: { ...view.world, itemCatalog: [{ id: 'wood', name: '木料' }, { id: 'cloth', name: '布料' }] } };
  const { instance } = page(input);
  instance.selectThemeTradeTarget(event({ id: 2 }));
  const setData = instance.setData;
  const paths = [];
  instance.setData = function(patch) { paths.push(...Object.keys(patch)); setData.call(this, patch); };
  instance.inputThemeTradeQuantity({ ...event({ index: 0, side: 'give' }), detail: { value: '2' } });
  instance.inputThemeTradeQuantity({ ...event({ index: 1, side: 'take' }), detail: { value: '1' } });
  assert.ok(paths.includes('themeTradeForm.items[0].give'));
  assert.ok(paths.includes('themeTradeForm.items[1].take'));
  assert.deepEqual(instance.data.themeTradeForm.items, [{ id: 'wood', name: '木料', give: '2', take: '0' }, { id: 'cloth', name: '布料', give: '0', take: '1' }]);
  assert.deepEqual(trade.payload(instance.data.themeTradeForm).take, [{ itemId: 'cloth', quantity: 1 }]);
});
