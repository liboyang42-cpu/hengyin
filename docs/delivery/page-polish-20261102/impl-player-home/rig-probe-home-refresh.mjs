import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const w = await launch({ path: 'pages/index/index', identity: 'e2e-openid-9004', settle: 6000, agree: true });
const pm = () => w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; const d = p.data; return [d.pm_recommendedTopicList, d.pm_nearbyActivityList, d.pm_upcomingActivityList, d.pmFeed, d.bottomTopicList.length].join(','); });
const seen = new Set();
const t0 = Date.now();
await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onRefresh());
while (Date.now() - t0 < 4000) { seen.add(await pm()); await sleep(15); }
console.log('pm_reco,pm_nearby,pm_upcoming,pmFeed,feedLen states seen during refresh:', [...seen].join(' | '));
await w.close();
