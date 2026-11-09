import { go, sleep } from './go.mjs';
const T = process.argv[2] || 'before';
await go(`${T}-p1n1`, { path: 'pages/activity/baoming/baoming?activityId=10&ticketId=77', identity: 'e2e-openid-k6new1' }, async (w, shot) => {
  await sleep(3500); await w.scroll(500); await sleep(800); await shot(`p1n1-checkout-${T}`);
  try { await w.tapSel('.setly_li .radio'); await sleep(2500); await shot(`p1n1-checkout-tick-${T}`); } catch (e) { console.log('no points row'); }
});
