import { go, sleep } from './go.mjs';
const pages = [['pages/merchant/customer/detail/index?customerMemberId=9009', 'customer-detail'], ['pages/coop/invite-detail/index?inviteId=990026', 'invite-detail'], ['pages/merchant/verify-records/index', 'verify-records'], ['pages/merchant/customer/index', 'customer'], ['pages/merchant/ledger/index', 'ledger'], ['pages/merchant/decor/index', 'decor'], ['pages/merchant/ledger/order-detail/index?recordType=redemption&recordId=1', 'order-detail'], ['pages/coop/finance/index', 'coop-finance']];
for (const [who, id] of [['guest', ''], ['player', 'e2e-openid-9004']]) {
  let i = 0;
  for (const [p, n] of pages) {
    i++; const name = `k-${who}-${String(i).padStart(2, '0')}-${n}`;
    await go(name, { path: p.split('?')[0], identity: id || undefined }, async (w, shot) => { await w.relaunch('/' + p); await sleep(2500); await shot(name); });
  }
}
