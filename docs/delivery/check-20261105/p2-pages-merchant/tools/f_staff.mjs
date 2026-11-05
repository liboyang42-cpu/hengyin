import { go, sleep } from './go.mjs';
import fs from 'node:fs';
const step = process.argv[2];
if (step === 'invite') await go('f01-invite', { path: 'pages/merchant/team/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(1800); await w.tap('邀请员工'); await sleep(900); await shot('f01-invite-role-sheet');
  await w.tap(process.argv[3] || '核销员', { exact: true }); await sleep(2500); await shot(process.argv[4] || 'f02-invite-created');
  const tok = await w.eval(() => getCurrentPages().slice(-1)[0]._createdInviteToken);
  fs.writeFileSync('/root/p2/invite_token', tok || ''); console.log('TOKEN?', !!tok);
});
if (step === 'accept') await go('f03-accept', { path: 'pages/index/index', identity: process.argv[3] || 'walk-player-1' }, async (w, shot) => {
  const tok = fs.readFileSync('/root/p2/invite_token', 'utf8').trim();
  await w.relaunch('/pages/merchant/team/index?invite=' + encodeURIComponent(tok)); await sleep(2500); await shot((process.argv[4]||'f03')+'-invitee-landing');
  await w.tap('接受'); await sleep(2500); await shot((process.argv[4]||'f04')+'-invitee-after-accept');
});
if (step === 'clerk') await go('f06-clerk', { path: 'pages/index/index', identity: 'walk-player-1' }, async (w, shot) => {
  await sleep(1500); await shot('f06-clerk-home-tab');
  await w.relaunch('/pages/merchant/index/index'); await sleep(2500); await shot('f07-clerk-merchant-home');
  try { await w.tap('更多'); await sleep(900); await shot('f08-clerk-more'); } catch (e) { console.log('no 更多', e.message); }
  for (const [p, n] of [['pages/merchant/verify-records/index', 'f09-clerk-verify-records'], ['pages/merchant/ledger/index', 'f10-clerk-ledger'], ['pages/merchant/customer/index', 'f11-clerk-customer'], ['pages/merchant/team/index', 'f12-clerk-team'], ['pages/merchant/decor/index', 'f13-clerk-decor'], ['pages/coop/finance/index', 'f14-clerk-coop-finance'], ['pages/merchant/aftercare/index', 'f15-clerk-aftercare'], ['pages/merchant/marketing/index', 'f16-clerk-marketing'], ['pages/topic/merchantinfo/merchantinfo?topicId=990014', 'f17-clerk-workbench']]) {
    await w.relaunch('/' + p); await sleep(2200); await shot(n);
  }
});
if (step === 'clerkscan') await go('f18-clerk-scan', { path: 'pages/merchant/index/index', identity: process.argv[4] || 'walk-player-1' }, async (w, shot) => {
  await sleep(2000); await w.scan(process.argv[3]); await w.tap('扫码'); await sleep(2500); await shot('f18-clerk-scan-result');
});
if (step === 'clerkcard') await go('f19-clerk-card', { path: 'pages/merchant/index/index', identity: process.argv[3] || 'walk-player-1' }, async (w, shot) => {
  await sleep(2200); await w.tap('E2E 探店日一期'); await sleep(2500); await shot('f19-clerk-project-card-tap');
  console.log('ROUTE', JSON.stringify(await w.route()));
});
if (step === 'clerk2') await go('f22-checkin-clerk', { path: 'pages/merchant/index/index', identity: 'walk-player-2' }, async (w, shot) => {
  await sleep(2200); await shot('f22-checkin-clerk-home');
  await w.tap('更多'); await sleep(900); await shot('f23-checkin-clerk-more');
  for (const [t, n] of [['退款售后', 'f24-checkin-clerk-aftercare'], ['管理优惠券', 'f25-checkin-clerk-coupons'], ['品牌资料', 'f26-checkin-clerk-decor'], ['协作邀请', 'f27-checkin-clerk-coop']]) {
    await w.relaunch('/pages/merchant/index/index'); await sleep(2000); await w.tap('更多'); await sleep(800);
    try { await w.tap(t); await sleep(2200); await shot(n); } catch (e) { console.log('NO', t); }
  }
  for (const [p, n] of [['pages/merchant/verify-records/index', 'f28-checkin-clerk-verify-records'], ['pages/merchant/customer/index', 'f29-checkin-clerk-customer'], ['pages/merchant/ledger/index', 'f30-checkin-clerk-ledger']]) { await w.relaunch('/' + p); await sleep(2200); await shot(n); }
});
if (step === 'remove') await go('f31-remove', { path: 'pages/merchant/team/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2000); await shot('f31-team-two-members');
  await w.tap('移除', { nth: 0 }); await sleep(900); await shot('f32-remove-confirm'); await w.tap('移除成员'); await sleep(2000); await shot('f33-after-remove');
});
