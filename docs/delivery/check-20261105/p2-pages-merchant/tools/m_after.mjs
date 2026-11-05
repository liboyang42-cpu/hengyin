import { go, sleep } from './go.mjs';
await go('m01-aftercare', { path: 'pages/merchant/aftercare/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2200); await w.tap('处理中', { exact: true }); await sleep(1500); await shot('m01-aftercare-processing');
  await w.tap('已完成', { exact: true }); await sleep(1500); await shot('m02-aftercare-done');
});
await go('m03-ledger-refund', { path: 'pages/merchant/ledger/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2200); await w.tap('待处理', { exact: true }); await sleep(1500); await shot('m03-ledger-pending-tab');
  await w.tap('全部', { exact: true }); await sleep(1200); await w.scroll(500); await shot('m04-ledger-scrolled');
  try { await w.tap('退款 · 城瘾用户'); } catch (e) { await w.tap('退款'); }
  await sleep(2500); await shot('m05-refund-row-tap'); console.log('ROUTE', JSON.stringify(await w.route())); await w.scroll(600); await shot('m06-refund-detail-scrolled');
});
await go('m07-ledger-sale', { path: 'pages/merchant/ledger/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2200); await w.scroll(500); await w.tap('周六手冲咖啡体验 · 单人体验'); await sleep(2500); await shot('m07-sale-detail'); await w.scroll(600); await shot('m08-sale-detail-scrolled');
});
await go('m09-earnings', { path: 'subpackageA/pages/assetcenter/earnings/index', identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(2500); await w.scroll(600); await shot('m09-earnings-scrolled'); });
