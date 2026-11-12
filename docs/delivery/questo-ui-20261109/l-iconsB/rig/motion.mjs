// l-iconsB 动作帧条(改后):在真实页面上让 cy-illus 重播进场(组件自带 replay()),按毫秒拍帧。
// 用法:RIG_WALK=… RIG_WEB=… RIG_SHOTS=… node motion.mjs
const { launch } = await import(process.env.RIG_WALK);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PG = 'getCurrentPages().slice(-1)[0]';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
const GEN = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../../l-play/rig/gen.js');
async function strip(id, pathname, identity, ats, pre) {
  console.log('==', id);
  const w = await launch({ path: pathname, identity, settle: 2500, agree: true });
  try {
    if (pre === 'play') { const data = execFileSync('node', [GEN, process.env.XCX, 'route']).toString(); await w.eval(`(() => { ${PG}.applyPreviewData(${data}); })()`); await sleep(2500); await w.tap('开始游戏'); await sleep(6200); }
    else if (pre) { await w.eval(pre); await sleep(1500); }
    const n = await w.eval(`(()=>{const p=${PG};const cs=(p.selectAllComponents?p.selectAllComponents('cy-illus'):[p.selectComponent('cy-illus')]).filter(Boolean);cs.forEach(c=>c.replay());return cs.length})()`);
    console.log('  replayed', n);
    const t0 = Date.now();
    for (const at of ats) { await sleep(Math.max(0, at - (Date.now() - t0))); await w.shot(`motion-${id}-f${String(at).padStart(4, '0')}-after`); }
  } finally { await w.browser.close(); }
}
// 通行证页:钥匙(进场弹出 → 1.1s 起在锁里转四分之一圈 → 宝石闪 → 绿光)+ 章节印(举起落下、印记显出、溅墨)
await strip('pass', 'subpackageMember/market/membership/index', 'e2e-openid-9004-held', [80, 300, 700, 1300, 2700, 3200, 3700, 4400, 4900]);
// 判定屏:成功(方砖盖下 → 钩画出 → 纸屑弹出落下)/ 失败(落地 → 抖一下 → 裂开 → 小云飘来 → 掉一滴)
const V = (k) => `(()=>{const p=${PG};p.setData({r5Verdict:{show:true,kind:'${k}',text:'',images:[]}});return 1})()`;
for (const [id, k, ats] of [['verdict-win', 'win', [60, 250, 600, 950, 1150, 1600, 2200]], ['verdict-lose', 'lose', [60, 400, 700, 900, 1400, 2200, 3000, 4400]]]) {
  console.log('==', id);
  const w = await launch({ path: 'pages/play/index?preview=1', identity: 'e2e-openid-9004', settle: 2500, agree: true });
  try {
    const data = execFileSync('node', [GEN, process.env.XCX, 'route']).toString();
    await w.eval(`(() => { ${PG}.applyPreviewData(${data}); })()`); await sleep(2500); await w.tap('开始游戏'); await sleep(6200);
    await w.eval(V(k));
    const t0 = Date.now();
    for (const at of ats) { await sleep(Math.max(0, at - (Date.now() - t0))); await w.shot(`motion-${id}-f${String(at).padStart(4, '0')}-after`); }
  } finally { await w.browser.close(); }
}
