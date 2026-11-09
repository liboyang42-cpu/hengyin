import { go, sleep } from './go.mjs';
const T = process.argv[2] || 'before';
await go(`${T}-n2-customer`, { path: 'pages/merchant/customer/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2500); await w.tap('测试乙'); await sleep(2500); await shot(`pn2-customer-detail-${T}`);
});
