import { go, sleep } from './go.mjs';
const T = process.argv[2] || 'settled';
await go(`${T}-ledger`, { path: 'pages/merchant/ledger/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2800); await shot(`n4-ledger-${T}`);
  await w.tap('周六手冲咖啡体验 · 单人体验'); await sleep(2500); await shot(`pn1-ticket-detail-${T}`);
});
await go(`${T}-tixian`, { path: 'subpackageMember/tixian/tixian', identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(3000); await shot(`d14-tixian-${T}`); });
await go(`${T}-earn`, { path: 'subpackageA/pages/assetcenter/earnings/index?theme=merchant', identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(3000); await shot(`d14-earnings-${T}`); });
