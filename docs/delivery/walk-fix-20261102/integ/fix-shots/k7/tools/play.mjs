// k7-map rig walk: same-session team pin without an avatar next to station 1 (j4 F16) and the finish sheet after
// ending with no station done (j4 N9). Two real logins in two browsers stand for two phones. argv[2] = prefix.
// Seams: loc steps stand in for walking; onRunHoldStart/End stand in for the 3 s hold (no touch in the rig);
// openStoryMap is the handler behind the story-map button.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import { execSync } from 'node:child_process';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const pre = process.argv[2] || '';
const redis = (c) => execSync(`/root/walk-rig/work/bin/redis-cli -n 9 ${c}`).toString().trim();
const pg = (w, expr) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return JSON.stringify(${expr})})()`);
const P = 'pages/play/index?topicId=990014&activityId=9';
const start = async (w) => {
  try { await w.tap('开始游戏'); await sleep(2500); } catch (e) { console.log('no 开始游戏'); }
  try { await w.tap('开启定位', { nth: 1 }); await sleep(2500); } catch (e) {}
  try { await w.tap('开始', { exact: true }); await sleep(1500); } catch (e) {}
};
// B = 阿青 (walk-player-2), no avatar, leader of an untitled team; walks up to station 1 (31.2318,121.4749)
const B = await launch({ path: P, identity: 'walk-player-2', agree: true, lat: 31.2312, lng: 121.4744, settle: 4000 });
await start(B);
const A = await launch({ path: P, identity: process.argv[3] || 'e2e-openid-9004', agree: true, lat: 31.2300, lng: 121.4730, settle: 4000 });
await start(A);
for (let i = 1; i <= 5; i++) { await B.loc(31.2312 + i * 0.00011, 121.4744 + i * 0.00009); await A.loc(31.2300 + i * 0.0001, 121.4730 + i * 0.00006); await sleep(3000); }
await sleep(14000);
console.log('redis', redis('HKEYS team_trail:pts:9'));
await A.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; if (p.openStoryMap) p.openStoryMap(); return 1})()`); await sleep(3000);
console.log('A rail', await pg(A, '(p.data.teamRail||[])'), 'pins', await pg(A, '(p.data.fmTeamPins||[])'));
await A.shot(pre + 'f16-storymap');
await A.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; if (p.closeStoryMap) p.closeStoryMap(); return 1})()`); await sleep(1500);
// N9: A ends the run without completing a station
console.log('toggle', await A.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];if(p.data.runState==='idle'&&p.onRunToggle)p.onRunToggle();return p.data.runState})()`)); await sleep(1500);
await A.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; p.onRunHoldStart(); return p.data.runState})()`); await sleep(4200);
await A.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];p.onRunHoldEnd();return 1})()`); await sleep(5000);
await A.shot(pre + 'n9-finish-sheet');
console.log('finish', (await A.text()).slice(0, 300).replace(/\n/g, ' / '));
console.log('errors A', JSON.stringify(await A.errors()), 'B', JSON.stringify(await B.errors()));
await A.close(); await B.close();
