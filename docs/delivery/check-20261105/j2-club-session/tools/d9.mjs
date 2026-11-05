import { run, S } from './lib.mjs';
// D9: merchant home → find the invitation → accept
await run({ path: 'pages/merchant/index/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(4000); await w.say('home'); await w.shot('57-merchant-home-invite');
  const t = await w.text(); const m = t.match(/待处理邀约|合作邀约|邀约|协作/); console.log('ENTRY', m && m[0]);
  if (m) { await w.tap(m[0]); await S(3000); }
  console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('inbox'); await w.shot('58-merchant-invite-inbox');
});
