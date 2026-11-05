// k7-map rig walk: j6 N2 stale login state on the club page. Guest -> taps a person -> real login prompt -> 微信登录
// (rig answers wx.login with 9004) -> the same page now knows who is looking.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const pg = (w, expr) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return JSON.stringify(${expr})})()`);
const w = await launch({ path: 'pages/club/detail/index?id=7002', agree: true, settle: 5000 });
await w.eval(`MPWEB.cfg.identity='e2e-openid-9004'`);
console.log('guest', await pg(w, '{my:p.data.myMemberId,app:getApp().getUserID&&getApp().getUserID()}'));
await w.tap('概览', { exact: true }); await sleep(1200);
await w.tap('阿青', { exact: true }); await sleep(1500);
await w.tap('阿青', { exact: true, nth: 1 }).catch(() => w.tap('阿青', { exact: true })); await sleep(1800);
await w.shot('k2-stale-login-prompt');
const t = await w.text(); console.log('prompt', t.slice(-260).replace(/\n/g, ' / '));
for (const b of ['微信登录', '登录', '同意并登录', '一键登录']) { try { await w.tap(b, { exact: true }); console.log('tapped', b); await sleep(4000); break; } catch (e) {} }
console.log('route', JSON.stringify(await w.route()));
await w.eval(`(()=>{const ps=getCurrentPages();const p=ps.find(x=>x.route==='pages/club/detail/index');if(p&&p.onShow)p.onShow();return 1})()`); await sleep(3000);
console.log('after', await w.eval(`(()=>{const p=getCurrentPages().find(x=>x.route==='pages/club/detail/index');return JSON.stringify({my:p&&p.data.myMemberId,app:getApp().getUserID&&getApp().getUserID()})})()`));
await w.shot('k2-stale-login-after');
console.log('errors', JSON.stringify(await w.errors()));
await w.close();
