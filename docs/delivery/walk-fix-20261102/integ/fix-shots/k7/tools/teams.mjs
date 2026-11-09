// k7-map rig walk: team lists and team confirm dialogs (j4 F15, N8) and the guest roam entry (N4). argv[2] = prefix.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const pre = process.argv[2] || '';
const pg = (w, expr) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return JSON.stringify(${expr})})()`);
// F15: walk-player-1 has no team of their own on 我的队伍 -> 附近在招募 lists the two untitled teams
let w = await launch({ path: 'subpackageRoam/my-teams/index', identity: 'walk-player-1', agree: true, lat: 31.2304, lng: 121.4737, settle: 4000 });
try { await w.tap('附近招募'); await sleep(2500); } catch (e) { console.log('no tab', e.message); }
await w.shot(pre + 'f15-nearby-teams');
console.log('open rows', await pg(w, '(p.data.openRows||[]).map(r=>r.name+" | "+r.members+" | "+r.sub)'), 'groups', await pg(w, '(p.data.groups||[]).map(g=>g.rows.map(r=>r.name))'));
await w.close();
// N8: leader 阿青 (team 1): reject the application, then disband (both cancelled after the shot)
w = await launch({ path: 'pages/team/detail/index?teamId=1', identity: 'walk-player-2', agree: true, lat: 31.2304, lng: 121.4737, settle: 4000 });
await w.shot(pre + 'n8-team-detail');
try { await w.tap('拒绝'); await sleep(1500); await w.shot(pre + 'n8-reject-confirm'); } catch (e) { console.log('no 拒绝', e.message); }
console.log((await w.text()).slice(0, 400).replace(/\n/g, ' / '));
try { await w.tap('取消'); await sleep(800); } catch (e) { try { await w.tap('再想想'); } catch (e2) {} }
await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];const c=p.selectComponent&&p.selectComponent('#dcReject');if(c&&c.close)c.close();return 1})()`);
await sleep(800);
try { await w.scroll(600); await sleep(600); await w.tap('解散队伍'); await sleep(1500); await w.shot(pre + 'n8-disband-confirm'); } catch (e) { console.log('no 解散', e.message); }
console.log((await w.text()).slice(-600).replace(/\n/g, ' / '));
await w.close();
// N4: guest roam entry, pass card 寻找组队
w = await launch({ path: 'pages/roam/index', agree: true, lat: 31.2304, lng: 121.4737, settle: 4000 });
console.log('guest teamsLive', await pg(w, 'p.data.introTeamsLive'));
await w.shot(pre + 'n4-guest-entry');
await w.scroll(500); await sleep(800); await w.shot(pre + 'n4-guest-entry-pass');
console.log((await w.text()).slice(0, 600).replace(/\n/g, ' / '));
await w.close();
