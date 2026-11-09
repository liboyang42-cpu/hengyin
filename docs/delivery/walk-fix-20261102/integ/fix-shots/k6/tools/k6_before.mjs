import { go, sleep } from './go.mjs';
const T = process.argv[2] || 'before';
const M = 'e2e-openid-9002';
// j1 N4 / p2 N-3: ledger rows vs header (before settlement)
await go(`${T}-n4-ledger`, { path: 'pages/merchant/ledger/index', identity: M }, async (w, shot) => {
  await sleep(2500); await shot(`n4-ledger-${T}`); await w.scroll(400); await sleep(600); await shot(`n4-ledger-scrolled-${T}`);
});
// p2 N-1: tap the activity scan row
await go(`${T}-n1-row`, { path: 'pages/merchant/ledger/index', identity: M }, async (w, shot) => {
  await sleep(2500); await w.tap('周六手冲咖啡体验 · 单人体验'); await sleep(2500); await shot(`pn1-ticket-detail-${T}`);
});
// j1 N3: deleted activity 15 on 我的项目 (活动 tab) and merchant home
await go(`${T}-n3-myproject`, { path: 'subpackageA/pages/myproject/index', identity: M }, async (w, shot) => {
  await sleep(2500); try { await w.tap('活动', { exact: true }); } catch (e) {} await sleep(1800); await shot(`n3-myproject-${T}`);
});
await go(`${T}-n3-home`, { path: 'pages/merchant/index/index', identity: M }, async (w, shot) => {
  await sleep(2500); await w.scroll(450); await sleep(600); await shot(`n3-home-${T}`);
});
// p2 N-2: customer 玩家二号 (9009) at shop 8001: bought 自由探索 ¥69 (host 9008) + own activity ¥39
await go(`${T}-n2-customer`, { path: 'pages/merchant/customer/index', identity: M }, async (w, shot) => {
  await sleep(2500); await shot(`pn2-customer-${T}`); await w.tap('测试乙'); await sleep(2500); await shot(`pn2-customer-detail-${T}`);
});
// p2 N-4: 分润明细 as merchant owner and as a player
await go(`${T}-n4-coopfin`, { path: 'pages/coop/finance/index', identity: M }, async (w, shot) => { await sleep(2800); await shot(`pn4-coop-finance-merchant-${T}`); });
await go(`${T}-n4-coopfin-player`, { path: 'pages/coop/finance/index', identity: 'e2e-openid-9004' }, async (w, shot) => { await sleep(2800); await shot(`pn4-coop-finance-player-${T}`); });
