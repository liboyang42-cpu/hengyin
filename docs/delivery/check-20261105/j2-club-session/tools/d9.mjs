import { run, S } from './lib.mjs';
// D9: merchant home (does it show the invitation?), then 合作中心 › bell › 收到的 › accept the invitation
if (!process.env.SKIP_A) await run({ path: 'pages/merchant/index/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(4000); await w.say('home'); await w.shot('57-merchant-home-after-invite');
  await w.scroll(700); await S(500); await w.say('home2'); await w.shot('57b-merchant-home-after-invite-2');
});
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3000); await w.tapSel('.cc-bell'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('inbox'); await w.shot('58-merchant-invite-inbox');
  const t = await w.text(); const m = ['接受合作'];
  if (m) { await w.tap(m[0]); await S(1500); await w.say('confirm'); await w.shot('59-merchant-accept-confirm');
    const t2 = await w.text(); const m2 = t2.match(/确认接受|确认|接受/g); console.log('M2', JSON.stringify(m2));
    await w.tap('接受合作', { nth: -1 }); await S(1500); await w.say('modal'); await w.shot('59b-merchant-accept-modal'); try { await w.modal('confirm'); } catch (e) { console.log(e.message); const t3 = await w.text(); await w.tap('接受合作', { exact: true, nth: 1 }); } await S(3000); await w.say('accepted'); await w.shot('60-merchant-accepted'); }
});
