import { go, sleep } from './go.mjs';
await go('g20-accept', { path: 'pages/coop/list/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2500); await shot('g20-coop-list-two');
  await w.relaunch('/pages/coop/invite-detail/index?inviteId=990026'); await sleep(2500); await shot('g21-invite-detail-drainage');
  await w.tap('接受合作', { exact: true }); await sleep(1200); await shot('g22-accept-confirm');
  await w.tap('接受合作', { exact: true, nth: 1 }); await sleep(2500); await shot('g23-after-accept'); await w.scroll(900); await shot('g24-after-accept-scrolled');
});
await go('g25-home-after-accept', { path: 'pages/merchant/index/index', identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(2500); await shot('g25-home-after-accept'); await w.relaunch('/pages/coop/list/index'); await sleep(2200); await shot('g26-coop-list-after'); });
