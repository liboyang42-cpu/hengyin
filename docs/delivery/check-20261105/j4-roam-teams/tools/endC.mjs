import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import { execSync } from 'node:child_process';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const redis = (c) => execSync(`/root/walk-rig/work/bin/redis-cli -n 9 ${c}`).toString().trim();
const pg = (w, expr) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return JSON.stringify(${expr})})()`);
const C = await launch({ path: 'pages/play/index?topicId=990014&activityId=9', identity: 'walk-player-2', agree: true, lat: 31.2311, lng: 121.4716, settle: 5000 });
console.log('state', await pg(C, '{run:p.data.runState,nodes:(p.data.nodes||[]).map(n=>!!n.done),tt:p._teamTrail&&p._teamTrail.state()}'));
console.log((await C.text()).slice(0, 300));
try { await C.tap('开始游戏'); await sleep(2500); } catch (e) { console.log('no 开始游戏'); }
try { await C.tap('开启定位', { nth: 1 }); await sleep(3000); } catch (e) { console.log('no 开启定位'); }
console.log('toggle', await C.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];p.onRunToggle();return p.data.runState})()`)); await sleep(1500);
for (let i=1;i<=5;i++){ await C.loc(31.2311+i*0.00005, 121.4716-i*0.00006); await sleep(3000); }
console.log('state2', await pg(C, '{run:p.data.runState,tt:p._teamTrail&&p._teamTrail.state(),running:p._teamTrail&&p._teamTrail.isRunning()}'));
await C.loc(31.2312, 121.4714); await sleep(13000);
console.log('redis', redis('HKEYS team_trail:pts:9'));
try { await C.tapSel('[aria-label="更多"]'); } catch (e) { try { await C.tap('···'); } catch (e2) { console.log('no more btn'); } }
await sleep(1500);
await C.shot('65-C-tools');
console.log((await C.text()).slice(0, 500));
await C.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; p.onRunHoldStart(); return p.data.runState})()`); await sleep(1600);
await C.shot('66-C-hold-progress');
await sleep(2600);
await C.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];p.onRunHoldEnd();return 1})()`); await sleep(1500);
await C.shot('67-C-end-sheet');
await sleep(4000); await C.shot('67b-C-end-sheet-settled'); console.log('end', (await C.text()).slice(0, 500));
console.log('redis after end', redis('HKEYS team_trail:pts:9'));
await C.eval(`(()=>{const s=(function f(r){for(const el of r.querySelectorAll('*')){if(el.shadowRoot){const x=f(el.shadowRoot);if(x)return x}if(/scroll-view|scroll/.test(el.tagName.toLowerCase())&&el.scrollHeight>el.clientHeight+50&&el.textContent.includes('点亮'))return el}return null})(document);if(s)s.scrollTop=400;return !!s})()`); await sleep(800);
await C.shot('68-C-end-sheet-scrolled');
console.log('net', JSON.stringify((await C.net()).filter(x => /trail|finish|end/.test(x.url)).map(x => x.status + ' ' + x.url.replace(/.*\/api/, '/api'))));
await C.close();
