import { go, sleep } from './go.mjs';
await go('k1-dbl-explore', { path: 'pages/activity/baoming/baoming?activityId=9&ticketId=76', identity: 'e2e-openid-k6new1' }, async (w, shot) => {
  await sleep(3500); await w.tap('请选择参与人信息'); await sleep(2500); await shot('k1-dbl-00-participant');
  console.log('ROUTE', JSON.stringify(await w.route()));
});
