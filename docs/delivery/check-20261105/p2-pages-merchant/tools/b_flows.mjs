import { go, sleep } from './go.mjs';
const M = 'e2e-openid-9002';
const which = process.argv[2] || 'all';
const run = (k) => which === 'all' || which.split(',').includes(k);
if (run('f7')) await go('b01-ledger-row', { path: 'pages/merchant/ledger/index', identity: M }, async (w, shot) => {
  await sleep(1500); await shot('b01-ledger'); await w.tap('E2E 玩家二号'); await sleep(2000); await shot('b02-order-detail-nocash'); await w.scroll(600); await shot('b03-order-detail-nocash-scrolled');
  console.log('DATA', JSON.stringify(await w.data('detail')).slice(0, 1500));
});
if (run('f3')) await go('b04-home-all', { path: 'pages/merchant/index/index', identity: M }, async (w, shot) => {
  await sleep(1500); await w.tap('全部 1'); await sleep(2000); await shot('b04-myproject');
});
if (run('f1')) await go('b05-coupons', { path: 'pages/merchant/index/index', identity: M }, async (w, shot) => {
  await sleep(1500); await w.tap('更多'); await sleep(800); await shot('b05-more-sheet'); await w.tap('管理优惠券'); await sleep(2000); await shot('b06-coupon-list');
});
if (run('f8')) await go('b07-workbench', { path: 'pages/merchant/index/index', identity: M }, async (w, shot) => {
  await sleep(1500); await w.tap('E2E 探店日一期'); await sleep(2500); await shot('b07-workbench-top'); await w.scroll(500); await shot('b08-workbench-scrolled'); await w.scroll(900); await shot('b09-workbench-scrolled2');
});
if (run('cust')) await go('b10-customer', { path: 'pages/merchant/index/index', identity: M }, async (w, shot) => {
  await sleep(1500); await w.tap('客户'); await sleep(2000); await shot('b10-customer');
  await w.tap('玩家二号'); await sleep(2000); await shot('b11-customer-detail'); await w.scroll(700); await shot('b12-customer-detail-scrolled');
  await w.back(); await sleep(800); await w.tap('筛选'); await sleep(800); await shot('b13-customer-filter');
});
if (run('cust2')) await go('d01-customer-buyers', { path: 'pages/merchant/customer/index', identity: M }, async (w, shot) => {
  await sleep(2000); await shot('d01-customer-4buyers');
  await w.tap('城瘾用户'); await sleep(2000); await shot('d02-customer-detail-not-arrived'); await w.scroll(600); await shot('d02b-customer-detail-not-arrived-scrolled');
  await w.back(); await sleep(800); await w.tap('管理'); await sleep(900); await shot('d03-customer-manage');
});
if (run('nums')) {
  for (const [p, n] of [['pages/merchant/index/index', 'e10-home-after-scans'], ['pages/merchant/ledger/index', 'e11-ledger-after-scans'], ['pages/merchant/verify-records/index', 'e12-verify-records-after-scans'], ['pages/merchant/marketing/index', 'e13-marketing-after-scans'], ['pages/merchant/marketing/ai-insight/index', 'e14-ai-insight-after-scans'], ['pages/merchant/customer/index', 'e15-customer-after-scans'], ['pages/coop/finance/index', 'e16-coop-finance-after-scans']])
    await go(n, { path: p, identity: M }, async (w, shot) => { await sleep(2200); await shot(n); });
}
