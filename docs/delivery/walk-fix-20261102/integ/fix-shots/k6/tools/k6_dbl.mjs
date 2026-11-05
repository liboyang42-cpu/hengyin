import { go, sleep } from './go.mjs';
const URL = 'pages/activity/baoming/baoming?activityId=9&ticketId=76';
await go('k1-dbl-first', { path: URL, identity: 'e2e-openid-k6new1' }, async (w, shot) => {
  await sleep(3500);
  await w.tap('同意将姓名、手机号提供给主办方，用于报名核验'); await sleep(1500);
  await shot('k1-dbl-01-checkout-ready');
  await w.pay('cancel');
  await w.tap('付款', { exact: true }); await sleep(6000);
  await shot('k1-dbl-02-after-closing-pay-sheet');
  console.log('ROUTE', JSON.stringify(await w.route()));
});
