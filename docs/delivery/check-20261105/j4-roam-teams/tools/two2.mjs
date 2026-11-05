// j4-1105: two real accounts in one session, two browsers at once. No request shim.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import { execSync } from 'node:child_process';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const P = 'pages/play/index?topicId=990014&activityId=9';
const tok = (o) => JSON.parse(execSync(`cat /tmp/j4/tok-${o}.json`).toString()).token;
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const sql = (q) => execSync(`$(cat /tmp/j4/mysql.cmd) -N -e "${q}"`, { shell: '/bin/bash' }).toString().trim();
const redis = (c) => execSync(`/root/walk-rig/work/bin/redis-cli -n 9 ${c}`).toString().trim();
const A = await launch({ path: P, identity: 'e2e-openid-9004', agree: true, lat: 31.2306, lng: 121.4740, settle: 4000 });
const C = await launch({ path: P, identity: 'walk-player-2', agree: true, lat: 31.2302, lng: 121.4732, settle: 4000 });
const pg = (w, expr) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return JSON.stringify(${expr})})()`);
for (const [n, w] of [['A', A], ['C', C]]) {
  await w.tap('开始游戏'); await sleep(2500);
  try { await w.tap('开启定位', { nth: 1 }); await sleep(3000); } catch (e) { log(n, 'no 开启定位'); }
  await w.shot(`60-consent-${n}`);
  log(n, 'consent', await pg(w, 'p.data.teamConsent'));
  try { await w.tap('开始', { exact: true }); await sleep(1500); } catch (e) { log(n, 'no consent sheet'); }
  try { await w.tap('我到了'); await sleep(1500); await w.tap('定位并签到'); await sleep(2500); } catch (e) { log(n, 'no 我到了', e.message); }
}
log('lead start', execSync(`curl -s -X POST -H "Authorization: Bearer ${tok('e2e-openid-9008')}" -d activityId=9 http://127.0.0.1:8099/api/club/lead/start`).toString());
await sleep(4000);
// walk ~72 s: A heads for station 1 (31.2318,121.4749); C wanders west
const st1 = [31.2318, 121.4749];
for (let i = 1; i <= 12; i++) {
  const t = i / 14;
  await A.loc(31.2306 + (st1[0] - 31.2306) * t, 121.4740 + (st1[1] - 121.4740) * t);
  await C.loc(31.2302 + i * 0.00006, 121.4732 - i * 0.0001);
  await sleep(6000);
  if (i % 4 === 0) log('A dist', await pg(A, '{d:p.data.storyDistText,nx:p.data.storyNext&&p.data.storyNext.dist,c:p.data.compass&&p.data.compass.dist}'));
}
const net = async (w) => (await w.net()).filter(x => /trail/.test(x.url)).map(x => `${x.status} ${x.method} ${x.url.replace(/.*\/api/, '/api')}`);
log('A trail calls', JSON.stringify(await net(A)));
log('C trail calls', JSON.stringify(await net(C)));
log('redis pts', redis('HKEYS team_trail:pts:9'));
for (const [n, w] of [['A', A], ['C', C]]) {
  await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; if (p.openStoryMap) p.openStoryMap(); return 1})()`);
  await sleep(2500);
  await w.shot(`61-routes-storymap-${n}`);
  log(n, 'rail', await pg(w, 'p.data.teamRail'), 'pins', await pg(w, 'p.data.fmTeamPins'));
  log(n, (await w.text()).slice(0, 300));
}
// --- follow a team from the rail (A follows C's team)
await A.eval('MPWEB.mapTrueScale=true');
log('A before follow', await pg(A, '{k:p.data.teamFollowKey,c:p.data.storyMapCenter}'));
await A.tapSel('.team-rail__i'); await sleep(1500);
await A.shot('62-rail-follow-on-A');
log('A follow', await pg(A, '{k:p.data.teamFollowKey,c:p.data.storyMapCenter,last:p._teams&&p._teams[0]&&p._teams[0].last}'));
for (let i = 13; i <= 16; i++) { await C.loc(31.2302 + i * 0.00006, 121.4732 - i * 0.0001); await A.loc(31.2306 + (st1[0] - 31.2306) * 12 / 14, 121.4740 + (st1[1] - 121.4740) * 12 / 14); await sleep(4500); }
await sleep(6000);
await A.shot('63-rail-follow-moved-A');
log('A follow after C moved', await pg(A, '{k:p.data.teamFollowKey,c:p.data.storyMapCenter,last:p._teams&&p._teams[0]&&p._teams[0].last}'));
await A.tapSel('.team-rail__i'); await sleep(1500);
await A.shot('64-rail-follow-off-A');
log('A unfollow', await pg(A, '{k:p.data.teamFollowKey,c:p.data.storyMapCenter}'));
// --- C ends the run with no station done (F8) via the real hold handler
await C.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; if (p.closeStoryMap) p.closeStoryMap(); return 1})()`); await sleep(1500);
await C.shot('65-C-before-end');
await C.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; p.onRunHoldStart({touches:[{}]}); return 1})()`); await sleep(1500);
await C.shot('66-C-hold-progress');
await sleep(2600);
await C.shot('67-C-end-sheet'); log('C end', (await C.text()).slice(0, 400));
await sleep(3000); await C.shot('68-C-end-sheet-later');
log('redis pts after C end', redis('HKEYS team_trail:pts:9'));
await sleep(14000);
log('A rail after C ended', await pg(A, '{rail:p.data.teamRail,pins:p.data.fmTeamPins}'));
await A.shot('69-A-after-C-ended');
// --- author turns 同场路线 off (FIXTURE: published column, as approval would copy it)
sql(`update cms_topic set published_theme_runtime_config_json='{\\"teamTrail\\":false}' where id=990014`);
await C.close();
const C2 = await launch({ path: P, identity: 'walk-player-2', agree: true, lat: 31.2309, lng: 121.4722, settle: 4000 });
await sleep(3000);
log('C2 state', await pg(C2, 'p._teamTrail&&p._teamTrail.state()'), (await C2.text()).slice(0, 200));
await sleep(14000);
log('A after author off', await pg(A, '{rail:p.data.teamRail,pins:p.data.fmTeamPins,st:p._teamTrail&&p._teamTrail.state(),run:p._teamTrail&&p._teamTrail.isRunning()}'));
await A.shot('70-A-after-author-off');
log('A trail calls tail', JSON.stringify((await net(A)).slice(-3)));
log('redis pts after author off', redis('HKEYS team_trail:pts:9'));
log('A errors', JSON.stringify(await A.errors()), 'C2 errors', JSON.stringify(await C2.errors()));
await A.close(); await C2.close();
