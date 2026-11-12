// v2-b 截帧:node cap.mjs <rigWebPort> <moment 2|7> <look> <outDir>
// 在 mpweb 里打开真实玩页(preview=1 + applyPreviewData 注入自由探索数据),按与原型相同的时间点截帧。
import fs from 'node:fs';
const [port, moment, look, outDir] = process.argv.slice(2);
process.env.RIG_WEB = `http://127.0.0.1:${port}`;
const { launch } = await import('./walk.mjs');
fs.mkdirSync(outDir, { recursive: true });
const names = ['晨间烘焙', '旧书与唱片', '午后小酌', '手作时间', '夜航唱片'];
const seed = {
  mode: 2, topicId: 'T-fx', topicName: '星尘便笺 · 人民广场', selfPlay: true, expiresAt: '2026-12-30 23:59:59',
  topicDesc: '一个慢下来的夜晚,从人民广场的第一粒星尘走到大剧院台阶。',
  chapters: names.map((n, i) => ({ chapterId: 100 + i, idxLabel: '第 ' + (i + 1) + ' 章', name: n, description: '第 ' + (i + 1) + ' 章' })),
  nodes: names.map((n, i) => ({ nodeId: i + 1, chapterId: 100 + i, name: n, done: false, imgUrl: ['/images/home-banner-2.jpg', '/pages/merchant/images/grow/treasure-hunter.jpg', '/pages/merchant/images/merchant_coop_banner.jpg', '/pages/club/images/d_frametu.jpg', '/subpackageRoam/assets/photos/event.webp'][i], address: '人民广场 ' + (i + 1) + ' 号', businessTime: '12:00-21:00', lat: 31.23 + i * 0.002, lng: 121.47 + i * 0.002, hasGame: true, validationMethod: 2, gameTitle: '任务 ' + (i + 1), duration: 15, difficulty: '轻松', players: '1–2 人' })),
};
const LOOK = { stardust: 'look--stardust', paper: 'look--paper', survival: 'look--survival', cyber: 'look--cyber' }[look];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const w = await launch({ path: 'pages/play/index?preview=1', settle: 2500, agree: true, identity: 'member-1' });
const ev = (f, a) => w.page.evaluate(f, a);
await ev((d) => { const p = getCurrentPages().slice(-1)[0]; p.applyPreviewData(d.seed); p.setData({ lookClass: d.look + ' story-font--sans', topicCover: '' }); p.setData({ 'chapter.cover': '' }); }, { seed, look: LOOK });
await sleep(1800);
const shot = async (tag, t0) => { const t = Math.round(performance.now() - t0); await w.page.screenshot({ path: `${outDir}/m${moment}-${look}-${tag}.jpg`, type: 'jpeg', quality: 80 }); return t; };
const log = {};
if (moment === '2') {
  await ev(() => getCurrentPages().slice(-1)[0].setData({ packOpen: false, headIn: false, packGo: false }));
  await sleep(1200);
  let t0 = performance.now(); log.idle = await shot('t0000-idle', t0);
  await sleep(1500); // 浮动半周期后再拍一次,证明在动
  log.idle2 = await shot('t0001-idle-later', performance.now());
  await ev(() => getCurrentPages().slice(-1)[0].openPack());
  t0 = performance.now();
  for (const off of [130, 400, 700, 1000, 1400, 1900, 2500, 3400]) {
    const wait = off - (performance.now() - t0); if (wait > 0) await sleep(wait);
    log['t' + String(off).padStart(4, '0')] = await shot('t' + String(off).padStart(4, '0'), t0);
  }
} else {
  await ev(() => getCurrentPages().slice(-1)[0].setData({ packOpen: true, headIn: true, isPreview: false, isMock: false, regId: 'R1' }));
  await sleep(900);
  let t0 = performance.now(); log.idle = await shot('t0000-play', t0);
  await ev(() => getCurrentPages().slice(-1)[0].toggleSessionTools());
  t0 = performance.now();
  for (const off of [60, 160, 300, 520, 900]) { const wait = off - (performance.now() - t0); if (wait > 0) await sleep(wait); log['t' + off] = await shot('menu-t' + String(off).padStart(4, '0'), t0); }
  await ev(() => getCurrentPages().slice(-1)[0].openTicketFromTools());
  t0 = performance.now();
  for (const off of [150, 400, 800, 1600]) { const wait = off - (performance.now() - t0); if (wait > 0) await sleep(wait); log['k' + off] = await shot('ticket-t' + String(off).padStart(4, '0'), t0); }
}
console.log(JSON.stringify({ look, moment, log, errors: w.errors().slice(0, 3) }));
await w.browser.close();
