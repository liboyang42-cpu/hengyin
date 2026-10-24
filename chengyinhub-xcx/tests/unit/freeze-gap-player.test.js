const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
global.wx = { getStorageSync() {}, showToast() {} };
global.getApp = () => ({ globalData: {} });
const bag = require('../../pages/play/utils/theme-backpack');
const trade = require('../../pages/play/utils/theme-trade');
const read = file => fs.readFileSync(path.join(__dirname, '../../pages/play', file), 'utf8');
const fuel = { id: 'fuel', kind: 'ITEM', name: '燃料', quantity: 3, slotCost: 2 };
function view(patch = {}) {
  return { scope: 'TEAM', activityId: 5, sessionId: 7, teamId: 1, revision: 12, authorityEpoch: 4,
    status: 'RUNNING', used: 14, capacity: 14, allowDiscard: true, contents: [fuel], events: [],
    availableActions: ['TRADE_OPEN', 'ITEM_RECEIVE'],
    world: { itemCatalog: [fuel], tradeTargets: [{ teamId: 2, disabled: false, nodeId: 11 }] }, ...patch };
}
function page(incoming, responder = async () => ({ code: 500 })) {
  const requests = [];
  const req = async (url, method, data) => { requests.push({ url, method, data }); return responder(url, data); };
  const p = { data: { ...JSON.parse(JSON.stringify(bag.data)), nodes: [], themeBag: bag.present(incoming, []) }, _themeHidden: true,
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data;
        for (const part of parts.slice(0, -1)) at = at[part];
        at[parts.at(-1)] = value;
      }
    } };
  Object.assign(p, bag.methods(req), trade.methods(req));
  return { p, requests };
}
const input = { operation: 'QUEST_COMPLETE', contentId: 'q1' };
// 这些数字是服务端响应夹具；本文件验证回读/阻断，不代替服务端占格算法测试。
function rewardResponse(finalUsed, overflow) {
  return { code: 200, data: { expectedRevision: 12, authorityEpoch: 4, result: {
    previewVersion: 'reward-v1', overflow,
    rewardOwners: [{ ownerId: 1, grants: { fuel: 2 }, finalUsed, capacity: 14, overflow }] } } };
}

test('背包和待领丢弃保留每件占格及服务端 used，丢弃只发送数量且不在本地猜占用', async () => {
  const incoming = view({ status: 'COMPLETED', pending: { 'quest:q1': { itemId: 'fuel', name: '燃料', quantity: 2, slots: 4 } } });
  const { p } = page(incoming);
  assert.equal(p.data.themeBag.items[0].slotCost, 2);
  assert.equal(p.data.themeBag.used, 14);
  assert.equal(await p.openThemePendingClaim('quest:q1'), true);
  assert.equal(p.data.themePendingClaim.reward.slots, 4);
  assert.equal(p.data.themePendingClaim.owned[0].slotCost, 2);
  p.changeThemeOwnedDiscard({ currentTarget: { dataset: { id: 'fuel' } }, detail: { value: '2' } });
  assert.equal(p.data.themeBag.used, 14);
  assert.equal(p.data.themeBag.items[0].quantity, 3);
  let sent;
  p._themeCommand = async (...args) => { sent = args; return true; };
  assert.equal(await p.confirmThemePendingClaim(), true);
  assert.deepEqual(sent, ['RECEIVE', 'quest:q1', [{ itemId: 'fuel', quantity: 2 }]]);
  const denied = page({ ...incoming, allowDiscard: false }).p;
  await denied.openThemePendingClaim('quest:q1');
  denied.changeThemeOwnedDiscard({ currentTarget: { dataset: { id: 'fuel' } }, detail: { value: '1' } });
  assert.equal(await denied.confirmThemePendingClaim(), false);
});

test('奖励超包阻断领取，放弃奖励重读 finalUsed 后才可确认，容量来自服务端', async () => {
  const { p, requests } = page(view(), (_url, command) => rewardResponse(command.payload.discardRewards?.length ? 14 : 18, !command.payload.discardRewards?.length));
  assert.equal(await p._readThemeRewardPreview(input), true);
  assert.deepEqual([p.data.themeRewardPreview.pack.owners[0].finalUsed, p.data.themeRewardPreview.pack.owners[0].capacity], [18, 14]);
  let confirmed;
  p._themeGameplayCommand = async command => { confirmed = command; return true; };
  assert.equal(await p.confirmThemeRewardPreview(), false);
  assert.equal(confirmed, undefined);
  assert.equal(await p.changeThemeRewardDiscard({ currentTarget: { dataset: { id: 'fuel' } }, detail: { value: '2' } }), true);
  assert.deepEqual(requests[1].data.payload.discardRewards, [{ itemId: 'fuel', quantity: 2 }]);
  assert.equal(p.data.themeRewardPreview.pack.owners[0].finalUsed, 14);
  assert.equal(p.data.themeBag.used, 14);
  assert.equal(await p.confirmThemeRewardPreview(), true);
  assert.deepEqual(confirmed, { ...input, discardRewards: [{ itemId: 'fuel', quantity: 2 }], previewVersion: 'reward-v1',
    expectedVersion: undefined, expectedRevision: 12, authorityEpoch: 4 });
});

test('交易保留物品目录占格，只展示双边服务端负重，超包失败不能提交', async () => {
  const { p, requests } = page(view(), (_url, command) => ({ code: 200, data: {
    previewOnly: true, expectedRevision: 12, authorityEpoch: 4,
    result: { ...command.payload, fromTeamId: 1, preview: true, fromUsed: 10, toUsed: 14, capacity: 14 } } }));
  p.selectThemeTradeTarget({ currentTarget: { dataset: { id: 2 } } });
  assert.equal(p.data.themeBag.world.itemCatalog[0].slotCost, 2);
  p.inputThemeTradeQuantity({ currentTarget: { dataset: { index: 0, side: 'give' } }, detail: { value: '2' } });
  assert.equal(await p.previewThemeTrade(), true);
  assert.deepEqual([p.data.themeTradePreview.fromUsed, p.data.themeTradePreview.toUsed, p.data.themeTradePreview.capacity], [10, 14, 14]);
  assert.deepEqual(requests[0].data.payload.give, [{ itemId: 'fuel', quantity: 2 }]);
  const denied = page(view(), async () => ({ code: 500, msg: '交易后超出背包容量' }));
  denied.p.selectThemeTradeTarget({ currentTarget: { dataset: { id: 2 } } });
  denied.p.inputThemeTradeQuantity({ currentTarget: { dataset: { index: 0, side: 'give' } }, detail: { value: '2' } });
  assert.equal(await denied.p.previewThemeTrade(), false);
  assert.equal(denied.p.data.themeTradeError, '交易后超出背包容量');
  assert.equal(await denied.p.submitThemeTrade(), false);
  assert.equal(denied.requests.length, 1);
  const wxml = read('theme-backpack.wxml');
  for (const field of ['themeBag.used', 'themePendingClaim.reward.slots', 'themeTradePreview.fromUsed', 'themeTradePreview.toUsed', 'owner.finalUsed']) assert.ok(new RegExp('\\{\\{[^}]*' + field.replaceAll('.', '\\.') + '[^}]*\\}\\}').test(wxml), field);
});

test('线索卡关页重开后重新回读：名称、范围、正文与图片原样保留，空回读不残留旧卡', async () => {
  const procedure = '一、隔离抽热支路 ISO；二、接回热旁路 BYP；三、释放启动供能 START。';
  let incoming = view({ clues: [
    { id: 'clue.private', name: 'EV07 许岚留言', scope: 'TEAM', sources: [{ nodeId: 3, title: 'L03 求救点', blocks: [{ text: '外区还有人活着', images: ['https://cdn.example/ev07.jpg'] }] }] },
    { id: 'clue.public', name: 'EV04 维护规程', scope: 'PUBLIC', sources: [{ nodeId: 5, title: 'L00 证据墙', blocks: [{ text: procedure, images: ['https://cdn.example/ev04-a.jpg', 'https://cdn.example/ev04-b.jpg'] }] }] },
  ] });
  const { p } = page(incoming);
  let reads = 0;
  p._loadPlayerGameModule = async () => { reads++; p._applyThemeTeamView(incoming, incoming); return true; };
  await p.openThemeBackpack();
  p.changeThemeBackpackTab({ currentTarget: { dataset: { key: 'clues' } } });
  const cards = p.data.themeBag.clues;
  assert.deepEqual(cards.map(row => [row.name, row.scopeText]), [['EV07 许岚留言', '本队线索'], ['EV04 维护规程', '全场公开']]);
  assert.deepEqual(cards[0].blocks, [{ source: 'L03 求救点', text: '外区还有人活着', images: ['https://cdn.example/ev07.jpg'] }]);
  assert.equal(cards[1].blocks[0].text, procedure);
  assert.deepEqual(cards[1].blocks[0].images, ['https://cdn.example/ev04-a.jpg', 'https://cdn.example/ev04-b.jpg']);
  p.closeThemeBackpack(); await p.openThemeBackpack();
  assert.deepEqual(p.data.themeBag.clues, cards, '重开后正文与图片不丢'); assert.equal(reads, 2);
  incoming = view({ clues: [] });
  p.closeThemeBackpack(); await p.openThemeBackpack();
  assert.deepEqual(p.data.themeBag.clues, []);
  assert.match(read('theme-backpack.wxml'), /themeBackpackTab === 'clues'[\s\S]*?{{item.name}}[\s\S]*?{{item.scopeText}}[\s\S]*?{{block.text}}/);
});

test('缺线索提示只显示名称，既有方案卡不输出内部条件键或答案', () => {
  const incoming = view({ interactionTools: [{ kind: 'PLAN', endingKey: 'p1', title: '第二次日出',
    missingClues: ['EV04 维护规程', 'EV06 负载校验'], conditions: [{ var: 'clue.internal_key', value: 1 }], answer: 'fixture-secret-answer' }] });
  const row = bag.present(incoming, []).planRows[0];
  assert.equal(row.conditionText, '还缺线索：EV04 维护规程、EV06 负载校验');
  for (const file of ['theme-plan-list.wxml', 'theme-plan-sheet.wxml']) {
    const wxml = read(file);
    assert.match(wxml, /{{item.conditionText}}/);
    assert.doesNotMatch(wxml, /{{[^}]*(?:missingClues|conditions|answer)[^}]*}}/);
  }
});
