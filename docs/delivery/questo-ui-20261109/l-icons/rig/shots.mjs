// l-icons rig driver. usage:
//   RIG_WALK=<rig>/mpweb/walk.mjs RIG_WEB=http://127.0.0.1:8097 XCX=<xcx> RIG_SHOTS=<dir> node shots.mjs <after|before> [scenario...]
// 游玩页走 pages/play?preview=1 + applyPreviewData(l-play/rig/gen.js,LOOK / PAL 环境变量选外观);
// 通行证相关页读 rig 的桩后端(fakeapi.mjs,HARNESS:只回这几屏要读的事实)。
import { execFileSync } from 'node:child_process';
import path from 'node:path';
const { launch } = await import(process.env.RIG_WALK);
const [P, ...only] = process.argv.slice(2);
const GEN = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../../l-play/rig/gen.js');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PG = 'getCurrentPages().slice(-1)[0]';
const want = (id) => !only.length || only.some((o) => id.startsWith(o));
const log = (w) => { const e = w.errors().slice(0, 3), c = w.console().filter((l) => l.startsWith('[error]') && !/features|favicon|404/.test(l)).slice(0, 4); if (e.length || c.length) console.log('  ERR', JSON.stringify(e), JSON.stringify(c)); };

async function play(kind, env, fn) {
  const data = execFileSync('node', [GEN, process.env.XCX, kind], { env: Object.assign({}, process.env, env || {}) }).toString();
  const w = await launch({ path: 'pages/play/index?preview=1', identity: 'e2e-openid-9004', settle: 2500, agree: true });
  try { await w.eval(`(() => { const p = ${PG}; p.applyPreviewData(${data}); })()`); await sleep(2500); await fn(w); log(w); } finally { await w.browser.close(); }
}
async function page(pathname, identity, fn) {
  const w = await launch({ path: pathname, identity, settle: 2500, agree: true });
  try { await fn(w); log(w); } finally { await w.browser.close(); }
}
const LOOKS = ['STARDUST', 'PAPER', 'SURVIVAL', 'CYBER'], PALS = ['DEFAULT', 'BLUE', 'RED', 'YELLOW', 'WHITE'];
const END = (reward) => `(()=>{const p=${PG};p._wonMedals=${reward ? "[{nodeId:11,name:'邮差的钥匙',img:'',rank:1}]" : '[]'};p._showEnding({show:true,real:true,idx:0,list:[{code:'a',title:'邮差的钥匙',summary:'老城三封信 · 7 / 7 站 · 58 分钟'}],archive:{identity:[],avatarUrl:'',photos:[],notes:[],stations:[],walk:''},hasArchive:false});return 1})()`;
const GATE2 = `(()=>{const p=${PG};p.setData({chapter:Object.assign({},p.data.chapter,{id:2,name:'老书店',description:'店门虚掩着，柜台后面没有人。'})});p._seenChapterGates=new Set();p.showChapterGate();return 1})()`;
const SHEET = `(()=>{const p=${PG};p.setData({isPreview:false});p.openSheet(p.data.nodes[0]);return 1})()`;

// 章节开场:四种外观 × 五种配色(after);before 只要四种外观的默认配色
const combos = P === 'after' ? LOOKS.flatMap((l) => PALS.map((p) => [l, p])) : LOOKS.map((l) => [l, 'DEFAULT']);
for (const [look, pal] of combos) {
  const id = `ch-start-${look.toLowerCase()}-${pal.toLowerCase()}`; if (!want(id)) continue; console.log('==', id);
  await play('route', { LOOK: look, PAL: pal }, async (w) => { await w.tap("开始游戏"); await sleep(1200 + 900); await w.shot(`${id}-${P}`); });   // tap 自带 700ms,门 3.6s 后自己进,这里 ≈ 2.8s
}
if (want('ch-start-frames')) { console.log('== ch-start-frames');   // 帧条:圆点飞拢 → 落位 → 章节印转
  await play('route', { LOOK: 'STARDUST', PAL: 'DEFAULT' }, async (w) => { await w.tap('开始游戏'); await sleep(1200); const t0 = Date.now(); for (const at of [300, 700, 1100, 1600, 2200, 2700]) { await sleep(Math.max(0, at - (Date.now() - t0))); await w.shot(`ch-start-f${at}-${P}`); } });
}
if (want('ch-start-reduced')) { console.log('== ch-start-reduced');
  await play('route', { LOOK: 'SURVIVAL', PAL: 'DEFAULT' }, async (w) => { await w.eval(`(()=>{${PG}.setData({reducedMotion:true});return 1})()`); await w.tap('开始游戏'); await sleep(1200 + 600); await w.shot(`ch-start-reduced-${P}`); });
}
if (want('ch-end')) { console.log('== ch-end');
  await play('two', { LOOK: 'PAPER', PAL: 'DEFAULT' }, async (w) => { await w.tap('开始游戏'); await sleep(1200 + 5000); await w.eval(GATE2); await sleep(1800); await w.shot(`ch-end-${P}`); await sleep(4200); await w.shot(`ch-end-next-${P}`); });
}
for (const [id, reward] of [['run-end-badge', true], ['run-end-success', false]]) { if (!want(id)) continue; console.log('==', id);
  await play('route', {}, async (w) => { await w.tap('开始游戏'); await sleep(1200 + 5000); await w.eval(END(reward)); const t0 = Date.now(); if (reward) { for (const at of [500, 900, 1400]) { await sleep(Math.max(0, at - (Date.now() - t0))); await w.shot(`${id}-f${at}-${P}`); } } await sleep(Math.max(0, 2600 - (Date.now() - t0))); await w.shot(`${id}-${P}`); });
}
if (want('station')) { console.log('== station(星尘玻璃)');
  await play('route', { LOOK: 'STARDUST', PAL: 'DEFAULT' }, async (w) => { await w.tap('开始游戏'); await sleep(1200 + 5000); await w.eval(SHEET); await sleep(1500); await w.shot(`station-stardust-${P}`); });
  await play('route', { LOOK: 'SURVIVAL', PAL: 'DEFAULT' }, async (w) => { await w.tap('开始游戏'); await sleep(1200 + 5000); await w.eval(SHEET); await sleep(1500); await w.shot(`station-survival-${P}`); });
}
// 通行证相关页(桩后端)
if (want('passes')) { console.log('== passes');
  await page('subpackageMember/market/passes/index', 'e2e-openid-9004', async (w) => { await sleep(1500); await w.shot(`passes-player-${P}`); });
  await page('subpackageMember/market/passes/index', 'e2e-merchant-9102', async (w) => { await sleep(1500); await w.shot(`passes-merchant-${P}`); });
}
if (want('membership')) { console.log('== membership');
  await page('subpackageMember/market/membership/index', 'e2e-openid-9004', async (w) => { await sleep(2200); await w.shot(`membership-${P}`); });
  await page('subpackageMember/market/membership/index', 'e2e-merchant-9102', async (w) => { await sleep(1500); await w.shot(`membership-merchant-${P}`); });
  await page('subpackageMember/market/library/index', 'e2e-merchant-9102', async (w) => { await sleep(1500); await w.shot(`library-merchant-${P}`); });
}
if (P === 'after' && want('reveal')) { console.log('== reveal');
  await page('subpackagePass/reveal/index', 'e2e-openid-9004', async (w) => {
    const t0 = Date.now(); for (const at of [300, 1300, 2000, 2600, 3200, 3900, 4700, 5600]) { await sleep(Math.max(0, at - (Date.now() - t0))); await w.shot(`reveal-first-f${at}-after`); }
    await w.shot('reveal-first-after');
    await w.tap('开始'); await sleep(2500); await w.shot('reveal-start-to-membership-after');
    await w.open('/subpackagePass/reveal/index'); await sleep(1500); await w.shot('reveal-again-after');   // 第二次进:直接待机
  });
  await page('subpackagePass/reveal/index?mode=paid&count=4&until=2027-11-10', 'e2e-openid-9004', async (w) => { await sleep(5000); await w.shot('reveal-paid-after'); await w.tap('进入可玩主题库'); await sleep(2500); await w.shot('reveal-paid-to-library-after'); });
  await page('subpackagePass/reveal/index', 'e2e-merchant-9102', async (w) => { await sleep(1200); await w.shot('reveal-merchant-after'); });
  const w = await launch({ path: 'pages/member/index/index', identity: 'e2e-openid-9004', settle: 2000, agree: true });
  try { await w.eval(`wx.setStorageSync('play_reduced_motion', true)`); await w.open('/subpackagePass/reveal/index'); await sleep(1500); await w.shot('reveal-reduced-after'); } finally { await w.browser.close(); }
}
