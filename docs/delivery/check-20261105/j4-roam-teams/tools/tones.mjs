import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import { execSync } from 'node:child_process';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const sql = (q) => execSync(`$(cat /tmp/j4/mysql.cmd) -N -e "${q}"`, { shell: '/bin/bash' }).toString().trim();
const P = 'pages/play/index?topicId=990014&activityId=9';
const pg = (w, expr) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return JSON.stringify(${expr})})()`);
// B walks and shares in the background so a same-session team line exists
const B = await launch({ path: P, identity: 'walk-player-1', agree: true, lat: 31.2300, lng: 121.4760, settle: 4000 });
await B.tap('开始游戏'); await sleep(2500); try { await B.tap('开启定位', { nth: 1 }); await sleep(2500); } catch (e) {}
try { await B.tap('开始', { exact: true }); await sleep(1500); } catch (e) {}
const looks = [['default', null], ['paper', '{\\"style\\":\\"PAPER\\"}'], ['2077', '{\\"style\\":\\"CYBER\\"}'], ['stardust', '{\\"style\\":\\"STARDUST\\"}']];
let step = 0;
for (const [name, look] of looks) {
  sql(`update cms_topic set look_json=${look ? "'" + look + "'" : 'NULL'} where id=990014`);
  const A = await launch({ path: P, identity: 'e2e-openid-9004', agree: true, lat: 31.2306, lng: 121.4740, settle: 4000 });
  await A.tap('开始游戏'); await sleep(2500); try { await A.tap('开启定位', { nth: 1 }); await sleep(2500); } catch (e) {}
  try { await A.tap('开始', { exact: true }); await sleep(1500); } catch (e) {}
  for (let i = 1; i <= 5; i++) { step++; await A.loc(31.2306 + i * 0.0001, 121.4740 + i * 0.00008); await B.loc(31.2300 + step * 0.00008, 121.4760 - step * 0.00006); await sleep(3000); }
  await sleep(12000);
  await A.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; if (p.openStoryMap) p.openStoryMap(); return 1})()`); await sleep(2500);
  console.log(name, await pg(A, '{sub:p.data.fmSubkey,ls:p.data.fmLayerStyle,tone:p.data.fmTone,lookClass:p.data.lookClass,trail:(p.data.fmTrail||[]).map(l=>l.color+"/"+(l.borderColor||"")),rail:(p.data.teamRail||[]).length}'));
  await A.shot(`96-tone-${name}`);
  await A.close();
}
sql(`update cms_topic set look_json=NULL where id=990014`);
await B.close();
