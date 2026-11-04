// j4: two real accounts in one session, two browsers at once. No request shim.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import { execSync } from 'node:child_process';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const P = 'pages/play/index?topicId=990014&activityId=9';
const api = (o, m, p, d) => execSync(`/tmp/j4/api.sh ${o} ${m} '${p}' '${d || ''}'`).toString();
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
async function open(identity, lat, lng) {
  const w = await launch({ path: P, identity, agree: true, lat, lng, settle: 4000 });
  return w;
}
const A = await open('e2e-openid-9004', 31.2306, 121.4740);
const C = await open('walk-player-2', 31.2302, 121.4732);
for (const [n, w] of [['A', A], ['C', C]]) {
  await w.tap('开始游戏'); await sleep(2500);
  await w.tap('开启定位', { nth: 1 }); await sleep(3000);
  await w.shot(`58-consent-${n}`);
  try { await w.tap('开始', { exact: true }); await sleep(1500); } catch (e) { log(n, 'no consent sheet (already decided)'); }
  try { await w.tap('我到了'); await sleep(1500); await w.shot(`59a-gather-consent-${n}`); await w.tap('定位并签到'); await sleep(2500); } catch (e) { log(n, 'no 我到了', e.message); }
  await w.shot(`59-gather-arrived-${n}`);
  log(n, (await w.text()).slice(0, 300));
}
log('lead start', execSync(`curl -s -X POST -H "Authorization: Bearer $(python3 -c "import json;print(json.load(open('/tmp/j4/tok-e2e-openid-9008.json'))['token'])")" -d activityId=9 http://127.0.0.1:8099/api/club/lead/start`).toString());
await sleep(4000);
for (const [n, w] of [['A', A], ['C', C]]) { await w.shot(`60-after-lead-start-${n}`); log(n, (await w.text()).slice(0, 400)); }
// walk both for ~60 s so several syncs run (every ~12 s)
const pathA = [], pathC = [];
for (let i = 1; i <= 12; i++) { pathA.push([31.2306 + i * 0.0001, 121.4740 + i * 0.00008]); pathC.push([31.2302 + i * 0.00006, 121.4732 - i * 0.0001]); }
for (let i = 0; i < 12; i++) { await A.loc(...pathA[i]); await C.loc(...pathC[i]); await sleep(5000); }
const net = async (w) => (await w.net()).filter(x => /trail/.test(x.url)).map(x => `${x.status} ${x.method} ${x.url.replace(/.*\/api/, '/api')}`);
log('A trail calls', JSON.stringify(await net(A)));
log('C trail calls', JSON.stringify(await net(C)));
for (const [n, w] of [['A', A], ['C', C]]) {
  try { await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; if (p.openStoryMap) p.openStoryMap(); return 1})()`); } catch (e) {}
  await sleep(3000);
  await w.eval('MPWEB.mapFitAll=true');
  await w.shot(`61-team-routes-storymap-${n}`);
  log(n, 'data.teamTrail', String(JSON.stringify(await w.data('teamTrail'))).slice(0, 800));
  log(n, (await w.text()).slice(0, 400));
}
await A.close(); await C.close();
