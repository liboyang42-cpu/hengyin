import { go, sleep } from './go.mjs';
await go('k1-dbl-second', { path: 'pages/activity/baoming/baoming?activityId=9&ticketId=76', identity: 'e2e-openid-k6new1' }, async (w, shot) => {
  await sleep(3500);
  await w.tap('同意将姓名、手机号提供给主办方，用于报名核验'); await sleep(1500);
  await w.pay('success');
  await w.tap('付款', { exact: true }); await sleep(1500); await shot('k1-dbl-03-second-buy-tap');
  await sleep(4000); await shot('k1-dbl-04-sent-to-existing-order');
  console.log('ROUTE', JSON.stringify(await w.route()));
});
