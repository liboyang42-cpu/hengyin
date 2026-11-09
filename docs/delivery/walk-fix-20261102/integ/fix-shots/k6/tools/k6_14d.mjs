import { go, sleep } from './go.mjs';
const T = process.argv[2] || 'd13';
await go(`${T}-earn`, { path: 'subpackageA/pages/assetcenter/earnings/index?theme=merchant', identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(3000); await shot(`d14-earnings-${T}`); });
await go(`${T}-fin`, { path: 'pages/merchant/index/index', identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(2500); await w.tap('财务'); await sleep(3000); await shot(`d14-merchant-finance-${T}`); });
await go(`${T}-ledger`, { path: 'pages/merchant/ledger/index', identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(2800); await shot(`d14-ledger-${T}`); });
