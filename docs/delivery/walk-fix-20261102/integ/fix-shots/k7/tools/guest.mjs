// k7-map rig walk of k2's unwalked work: a guest on a club page (posts, themes, members, leaderboard, no private fields),
// stale login state on the same page after logging in, and 我的参与 as a guest. Real pages, real backend.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import fs from 'node:fs';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const OUT = process.env.RIG_SHOTS + '/k2-guest-readbacks.txt';
const log = (...a) => { const s = a.join(' '); console.log(s); fs.appendFileSync(OUT, s + '\n'); };
fs.writeFileSync(OUT, '# k2 guest walk readbacks ' + new Date().toISOString() + '\n');
const pg = (w, expr) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return JSON.stringify(${expr})})()`);
const PRIVATE = /"(phone|mobile|memberId|authorMemberId|reposterMemberId|isLiked|viewerReposted|isFollowTheUser|viewerCanManage|joinMessage|realName)"\s*:/;
const w = await launch({ path: 'pages/club/detail/index?id=7002', agree: true, settle: 5000 });
try { await w.tap('帖文', { exact: true }); await sleep(2500); } catch (e) { console.log('no 帖文 tab'); }
await w.shot('k2-guest-club-posts');
log('text posts:', (await w.text()).slice(0, 400).replace(/\n/g, ' / '));
for (const [label, shot] of [['活动', 'k2-guest-club-events'], ['概览', 'k2-guest-club-overview']]) {
  try { await w.tap(label, { exact: true }); await sleep(2500); } catch (e) { log('no tab', label); }
  await w.shot(shot);
  log('text ' + label + ':', (await w.text()).slice(0, 500).replace(/\n/g, ' / '));
  if (label === '概览') { await w.scroll(700); await sleep(1200); await w.shot('k2-guest-club-overview-2'); log('text 概览 scrolled:', (await w.text()).slice(0, 600).replace(/\n/g, ' / ')); }
}
const net = await w.net();
log('guest club calls:', JSON.stringify(net.filter(x => /\/api\/club\//.test(x.url)).map(x => x.status + ' ' + x.method + ' ' + x.url.replace(/.*\/api/, '/api'))));
log('login rows', await pg(w, '{myMemberId:p.data.myMemberId,isMember:p.data.isMember,joined:p.data.joined,canSeeMembers:p.data.canSeeMembers}'));
// stale login (j6 N2): log in from this page's own path, then the same page shows the member's state
// log in from this page's own prompt (the rig answers wx.login with the chosen identity)
await w.eval(`MPWEB.cfg.identity='e2e-openid-9004'`);
try { await w.tap('概览', { exact: true }); await sleep(1500); await w.tap('阿青', { exact: true }); await sleep(1800); log('tapped member 阿青'); } catch (e) { log('member tap failed', e.message); }
await w.shot('k2-guest-member-sheet');
log('prompt:', (await w.text()).slice(-300).replace(/\n/g, ' / '));
for (const t of ['微信登录', '登录', '一键登录', '同意并登录']) { try { await w.tap(t, { exact: true }); await sleep(3500); log('tapped', t); break; } catch (e) {} }
await sleep(2000);
log('after login', await pg(w, '{myMemberId:p.data.myMemberId,isMember:p.data.isMember,joined:p.data.joined,canSeeMembers:p.data.canSeeMembers}'));
await w.shot('k2-club-after-login');
log('errors', JSON.stringify(await w.errors()));
await w.close();
// 我的参与 as a guest
const g = await launch({ path: 'subpackageMember/mycanyu/mycanyu', agree: true, settle: 4000 });
await g.shot('k2-guest-mycanyu');
log('mycanyu guest:', (await g.text()).slice(0, 300).replace(/\n/g, ' / '));
log('mycanyu net:', JSON.stringify((await g.net()).map(x => x.status + ' ' + x.url.replace(/.*\/api/, '/api'))));
await g.close();
