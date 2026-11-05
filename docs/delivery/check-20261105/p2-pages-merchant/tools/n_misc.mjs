import { go, sleep } from './go.mjs';
const M = 'e2e-openid-9002';
await go('n01-mkt-coupon', { path: 'pages/merchant/marketing/index', identity: M }, async (w, shot) => {
  await sleep(2500); await w.tap('1 张在投放'); await sleep(2200); await shot('n01-marketing-coupon-entry'); console.log('ROUTE', JSON.stringify(await w.route()));
});
await go('n02-cust-manage', { path: 'pages/merchant/customer/index', identity: M }, async (w, shot) => {
  await sleep(2500); await w.tap('管理', { exact: true }); await sleep(900); await w.tap('导出', { exact: true }); await sleep(2000); await shot('n02-customer-export'); console.log('EXPORT', (await w.text()).slice(-400));
  await w.relaunch('/pages/merchant/customer/index'); await sleep(2200); await w.tap('管理', { exact: true }); await sleep(900); await w.tap('批量标签', { exact: true }); await sleep(1500); await shot('n03-customer-bulk-tag'); console.log('BULK', (await w.text()).slice(-400));
});
await go('n04-cust-note', { path: 'pages/merchant/customer/index', identity: M }, async (w, shot) => {
  await sleep(2500); await w.tap('玩家二号'); await sleep(2200); await w.tap('编辑', { exact: true }); await sleep(1500); await shot('n04-customer-tag-editor'); console.log('ED', (await w.text()).slice(-500));
});
