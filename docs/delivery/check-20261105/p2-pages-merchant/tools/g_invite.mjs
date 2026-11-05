import { go, sleep } from './go.mjs';
const step = process.argv[2];
if (step === 'view') await go('g01-invite', { path: 'pages/merchant/index/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2200); await shot('g01-home-with-invite');
  await w.tap('更多'); await sleep(800); await w.tap('协作邀请'); await sleep(2200); await shot('g02-more-coop-entry');
  await w.relaunch('/pages/coop/list/index'); await sleep(2200); await shot('g03-coop-list-received');
  const t = await w.text(); console.log(t.slice(0, 600));
  try { await w.tap('E2E 探店日一期'); } catch (e) { await w.tap('想请你们'); }
  await sleep(2500); await shot('g04-invite-detail'); await w.scroll(700); await shot('g05-invite-detail-scrolled');
  console.log('ROUTE', JSON.stringify(await w.route()));
});
if (step === 'accept') await go('g06-accept', { path: 'pages/coop/invite-detail/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await w.relaunch('/pages/coop/invite-detail/index?inviteId=' + process.argv[3]); await sleep(2500); await shot('g06-invite-detail-deeplink');
  await w.scroll(900); await sleep(500); await shot('g07-invite-detail-bottom');
  await w.tap(process.argv[4] || '接受'); await sleep(1500); await shot('g08-after-accept-tap'); 
  try { await w.modal('confirm'); await sleep(2000); } catch (e) {}
  await shot('g09-after-accept');
});
if (step === 'reject') await go('g10-reject', { path: 'pages/coop/invite-detail/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await w.relaunch('/pages/coop/invite-detail/index?inviteId=' + process.argv[3]); await sleep(2500);
  await w.tap('拒绝'); await sleep(1200); await shot('g10-reject-empty-reason');
  try { await w.modal('confirm'); await sleep(1200); } catch (e) {}
  await shot('g11-reject-empty-result');
  await w.scroll(900); await w.type('textarea', '这周店里装修，暂不接待'); await sleep(300);
  await w.tap('拒绝'); await sleep(1200); await shot('g12-reject-confirm');
  try { await w.modal('confirm'); await sleep(2000); } catch (e) {}
  await shot('g13-after-reject');
});
