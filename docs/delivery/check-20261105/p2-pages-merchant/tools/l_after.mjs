import { go, sleep } from './go.mjs';
const tag = process.argv[2] || 'l';
for (const [p, n] of [['pages/merchant/index/index', 'home'], ['pages/merchant/ledger/index', 'ledger'], ['pages/merchant/aftercare/index', 'aftercare'], ['pages/merchant/verify-records/index', 'verify-records'], ['pages/merchant/marketing/index', 'marketing'], ['pages/merchant/customer/index', 'customer'], ['subpackageA/pages/assetcenter/earnings/index', 'earnings'], ['pages/coop/finance/index', 'coop-finance']])
  await go(`${tag}-${n}`, { path: p, identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(2500); await shot(`${tag}-${n}`); if (n === 'home') { await w.scroll(500); await shot(`${tag}-${n}-scrolled`); } });
