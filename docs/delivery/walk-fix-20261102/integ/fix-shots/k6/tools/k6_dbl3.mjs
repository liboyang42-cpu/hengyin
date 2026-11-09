import { go, sleep } from './go.mjs';
await go('k1-dbl-pay', { path: 'subpackageMember/orderinfo/orderinfo?id=79', identity: 'e2e-openid-k6new1' }, async (w, shot) => {
  await sleep(3500); await w.pay('success'); await w.tap('去支付', { exact: true }); await sleep(6000);
  await shot('k1-dbl-05-paid-from-order'); console.log('ROUTE', JSON.stringify(await w.route()));
});
