import { run, S } from './lib.mjs';
// G9: merchant on the station page for session 9 → accept the station
await run({ path: 'pages/merchant/game-node/index?activityId=9', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(4000); await w.say('node'); await w.shot('108-merchant-station-invite');
  const t = await w.text(); const m = t.match(/接受邀请|接受站点|接受/); console.log('BTN', m && m[0]);
  if (m) { await w.tap('接受邀请', { exact: true }); await S(1500); await w.say('confirm'); const t2 = await w.text(); if (/确认|接受/.test(t2)) { try { await w.modal('confirm'); } catch (e) {} } await S(3000); }
  await w.say('accepted'); await w.shot('109-merchant-station-accepted');
});
