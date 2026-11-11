// x-fix2 游玩页截图:走查同款 ?mock=free / ?mock=city(开发版才认 mock,故 env=develop)。
// usage: RIG_WEB=... RIG_SHOTS=... node shots-play.mjs <before|after> [only...]
const { launch } = await import(process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const [P, ...only] = process.argv.slice(2);
const want = (id) => !only.length || only.some((o) => id.startsWith(o));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PG = 'getCurrentPages().slice(-1)[0]';
async function play(kind, fn) {
  const w = await launch({ path: 'pages/play/index?mock=' + kind, identity: 'e2e-openid-9004', settle: 2500, agree: true, env: 'develop' });
  try { await fn(w); const e = w.errors().slice(0, 3); if (e.length) console.log('  ERR', JSON.stringify(e)); } finally { await w.browser.close(); }
}
const OPEN = `(()=>{const p=${PG};p.setData({packOpen:true,headIn:true});return 1})()`;
if (want('P-6-pack')) await play('free', async (w) => { await sleep(1500); await w.shot('P-6-pack-' + P); });
if (want('P-3')) await play('free', async (w) => { await sleep(1200); await w.eval(OPEN); await sleep(800); await w.eval(`(()=>{const p=${PG};p.toggleSessionTools();return 1})()`); await sleep(900); await w.shot('P-3-tools-' + P); });
if (want('P-7')) await play('free', async (w) => { await sleep(1200); await w.eval(`(()=>{const p=${PG};p.setData({packOpen:true,headIn:true,seatTiles:p.data.seatTiles.map((t,i)=>Object.assign({},t,{chapterLabel:'第 '+(i+1)+' 章 · Questify E2E 拒绝重申独立点位',statusText:'未到店',land:true}))});return p.data.seatTiles.length})()`); await sleep(1200); await w.shot('P-7-tiles-' + P); });
if (want('P-5')) await play('free', async (w) => { await sleep(1200); await w.eval(OPEN); await sleep(600); console.log(await w.eval(`(()=>{const p=${PG};const t=p.data.seatTiles.find(x=>Number(x.merchantId)>0)||p.data.seatTiles[0];p.onSeatTap({currentTarget:{dataset:{id:t.nodeId,idx:p.data.seatTiles.indexOf(t)}}});return t.nodeId})()`)); await sleep(2200); await w.eval(`(()=>{const p=${PG};p.setData({'hero.node.openText':''});return 1})()`); await sleep(400); await w.shot('P-5-hero-' + P); });
if (want('P-6-city')) await play('city', async (w) => { await sleep(1500); await w.shot('P-6-city-start-' + P); });
if (want('L-3')) await play('city', async (w) => {
  await sleep(1200);
  // 模拟开发者工具 / 旧基础库:离屏画布取样拿不到点(getImageData 全 0)
  await w.eval(`(()=>{const o=wx.createOffscreenCanvas;wx.createOffscreenCanvas=function(a){const c=o.call(wx,a);const g=c.getContext('2d');const gi=g.getImageData.bind(g);g.getImageData=(x,y,ww,hh)=>{const d=gi(x,y,ww,hh);d.data.fill(0);return d};c.getContext=()=>g;return c};return 1})()`);
  await w.tap('开始游戏'); await sleep(3000); await w.shot('L-3-gate-' + P);
});
if (want('P-8')) await play('city', async (w) => { await sleep(1200); await w.tap('开始游戏'); await sleep(6000); console.log(await w.eval(`(()=>{const p=${PG};const n=Object.assign({},p.data.nodes[0],{businessTime:'公共区域观察；以现场通行和开放时间为准'});p.setData({chapterFull:false});p.openSheet(n);return n.nodeId})()`)); await sleep(1600); await w.shot('P-8-place-' + P); });
