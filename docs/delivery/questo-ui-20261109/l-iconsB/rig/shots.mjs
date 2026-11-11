// l-iconsB 截图驱动(每个用到 cy-illus 的屏,改前 / 改后各拍一次)。
// 用法:RIG_WALK=<rig>/mpweb/walk.mjs RIG_WEB=http://127.0.0.1:<port> XCX=<该包的 chengyinhub-xcx> RIG_SHOTS=<dir> node shots.mjs <before|after> [场景前缀...]
// 游玩页走 pages/play?preview=1 + applyPreviewData(l-play/rig/gen.js);通行证页读桩后端 fakeapi.mjs(HARNESS);
// 结算页的战报由 rig 直接 setData 注入(HARNESS,桩后端没有战报接口)。kit 结论屏用 l-kits/rig/kit-shots.mjs(同一 RIG_WEB)。
import { execFileSync } from 'node:child_process';
import path from 'node:path';
const { launch } = await import(process.env.RIG_WALK);
const [P, ...only] = process.argv.slice(2);
const HERE = path.dirname(new URL(import.meta.url).pathname);
const GEN = path.resolve(HERE, '../../l-play/rig/gen.js');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PG = 'getCurrentPages().slice(-1)[0]';
const want = (id) => !only.length || only.some((o) => id.startsWith(o));
const log = (id, w) => { const e = w.errors().slice(0, 3), c = w.console().filter((l) => l.startsWith('[error]') && !/features|favicon|404/.test(l)).slice(0, 4); if (e.length || c.length) console.log('  ERR', id, JSON.stringify(e), JSON.stringify(c)); };
async function play(id, kind, fn, env) {
  if (!want(id)) return; console.log('==', id);
  const data = execFileSync('node', [GEN, process.env.XCX, kind], { env: Object.assign({}, process.env, env || {}) }).toString();
  const w = await launch({ path: 'pages/play/index?preview=1', identity: 'e2e-openid-9004', settle: 2500, agree: true });
  try { await w.eval(`(() => { const p = ${PG}; p.applyPreviewData(${data}); })()`); await sleep(2500); await w.tap('开始游戏'); await sleep(1200 + 5000); await fn(w); log(id, w); } finally { await w.browser.close(); }
}
async function page(id, pathname, identity, fn) {
  if (!want(id)) return; console.log('==', id);
  const w = await launch({ path: pathname, identity, settle: 2500, agree: true });
  try { await fn(w); log(id, w); } finally { await w.browser.close(); }
}
async function frames(w, id, ats) { const t0 = Date.now(); for (const at of ats) { await sleep(Math.max(0, at - (Date.now() - t0))); await w.shot(`${id}-f${at}-${P}`); } }

const END = (reward) => `(()=>{const p=${PG};p._wonMedals=${reward ? "[{nodeId:11,name:'邮差的钥匙',img:'',rank:1}]" : '[]'};p._showEnding({show:true,real:true,idx:0,list:[{code:'a',title:'邮差的钥匙',summary:'老城三封信 · 7 / 7 站 · 58 分钟'}],archive:{identity:[],avatarUrl:'',photos:[],notes:[],stations:[],walk:''},hasArchive:false});return 1})()`;
const ITEMS = "[{id:1,name:'黄铜钥匙',initial:'钥',image:'/pages/play/images/d_topic2.png',quantity:1,effectText:'打开钟楼侧门'},{id:2,name:'邮戳 · 1924',initial:'邮',quantity:2,effectText:''}]";
const BAG = `(()=>{const p=${PG};p.setData({themeBag:Object.assign({},p.data.themeBag,{sessionId:1,capacity:12,used:3,items:${ITEMS},pendingRows:[{id:'p1',name:'积分',text:'+15',illus:'points'}],valueRows:[],clues:[{id:9,name:'侧门没有锁',content:'侧门的锁芯里有新的划痕，钥匙是从外面插进去的。'}]}),themeBackpackOpen:true,themeBackpackTab:'items'});return 1})()`;
const BAGC = `(()=>{const p=${PG};p.setData({themeBackpackTab:'clues'});return 1})()`;

// 游玩页:结局(勋章 / 成功)、背包(待领取积分 / 线索)。地点卡与玩法开场在这份预览数据里有封面图,不出插图,没拍
for (const [id, reward] of [['run-end-badge', true], ['run-end-success', false]]) {
  await play(id, 'route', async (w) => { await w.eval(END(reward)); if (P === 'after' && reward) await frames(w, id, [300, 700, 1100, 1600, 2400]); else await sleep(2600); await w.shot(`${id}-${P}`); });
}
await play('bag', 'route', async (w) => { await w.eval(BAG); await sleep(1800); await w.shot(`bag-${P}`); await w.eval(BAGC); await sleep(1500); await w.shot(`bag-clues-${P}`); });

// 通行证页:未持证 / 已持证 / 商家
await page('pass-player', 'subpackageMember/market/membership/index', 'e2e-openid-9004', async (w) => { await sleep(2500); await w.shot(`pass-player-${P}`); });
await page('pass-held', 'subpackageMember/market/membership/index', 'e2e-openid-9004-held', async (w) => { await sleep(2500); await w.shot(`pass-held-${P}`); });
await page('pass-merchant', 'subpackageMember/market/membership/index', 'e2e-merchant-9102', async (w) => { await sleep(2000); await w.shot(`pass-merchant-${P}`); });
await page('pass-reduced', 'pages/member/index/index', 'e2e-openid-9004-held', async (w) => { await w.eval(`wx.setStorageSync('play_reduced_motion', true)`); await w.open('/subpackageMember/market/membership/index'); await sleep(2500); await w.shot(`pass-reduced-${P}`); });
await page('library-merchant', 'subpackageMember/market/library/index', 'e2e-merchant-9102', async (w) => { await sleep(2000); await w.shot(`library-merchant-${P}`); });

// 结算 · 分享卡(勋章)
const SETTLE = `(()=>{const p=${PG};p.setData({state:'ready',step:'share',topicName:'老城三封信',report:{result:'已通关',cover:'/pages/play/images/d_topic1.png',scored:true,scoreText:'82',nodesText:'7 / 7'},shareCard:{kicker:'已通关',title:'老城三封信',meta:'7 / 7 站 · 82 分'}});return 1})()`;
await page('settle-share', 'subpackageJourney/pages/settle/index?topicId=101', 'e2e-openid-9004', async (w) => { await w.eval(SETTLE); await sleep(2500); await w.shot(`settle-share-${P}`); });

// 收集册卡堆(勋章环)· 从「我的」进入时的开场(album-intro 只在 from=profile 时播)
// rig 不带 page.options.from、桩后端也没有勋章接口:先 setData 一枚勋章让列表出来,再把开场组件切到卡堆态(HARNESS:等同从「我的」进来 1.6s 后)
const RING = `(()=>{const p=${PG};const h=p.selectComponent('album-head');const c=h&&h.selectComponent('album-intro');if(!c)return 'no';c.setData({show:true,land:true,ring:true,idx:0});return 'ok'})()`;
await page('album', 'subpackageP3/pages/badge-wall/index/index?from=profile', 'e2e-openid-9004', async (w) => { await sleep(1500); await w.eval(`(()=>{const p=${PG};p.setData({viewMode:'list',loadFail:false,partialFail:false,badges:[{id:1,name:'邮差的钥匙',unlocked:true,iconUrl:''}]});return 1})()`); await sleep(800); console.log('  ring', await w.eval(RING)); await sleep(2500); await w.shot(`album-${P}`); });
// 判定屏(play-verdict):赢 = 成功插图,输 = 失败插图
for (const [id, kind] of [['verdict-win', 'win'], ['verdict-lose', 'lose']]) {
  await play(id, 'route', async (w) => { await w.eval(`(()=>{const p=${PG};p.setData({r5Verdict:{show:true,kind:'${kind}',text:'${kind === 'win' ? '答对了' : '再想想'}',images:[]}});return 1})()`); await sleep(2600); await w.shot(`${id}-${P}`); });
}
