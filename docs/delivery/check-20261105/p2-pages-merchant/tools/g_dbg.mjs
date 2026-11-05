import { go, sleep } from './go.mjs';
await go('g14-reject-dbg', { path: 'pages/coop/invite-detail/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await w.relaunch('/pages/coop/invite-detail/index?inviteId=990025'); await sleep(2500);
  await w.tap('拒绝'); await sleep(800); await w.scroll(900); await sleep(400); await shot('g14-reject-empty-reason-scrolled');
  await w.type('textarea', '这周店里装修，暂不接待'); await sleep(300);
  console.log('draft=', JSON.stringify(await w.data('draft')));
  await w.tap('拒绝', { exact: true }); await sleep(1500); await shot('g15-reject-confirm');
  await w.tap('拒绝这条邀约', { exact: true, nth: 1 }); await sleep(2500); await shot('g16-after-reject'); await sleep(1000); console.log('ROUTE', JSON.stringify(await w.route()));
});
