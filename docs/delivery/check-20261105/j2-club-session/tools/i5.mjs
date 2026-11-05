import { run, S } from './lib.mjs';
// I5: after the release: player opens play, chapter 2 opens, station 3 (大世界) answer 4, then the ending
const who = process.argv[2] || 'j2b-m1', shoot = !!process.env.SHOOT, P = process.env.P || '';
async function answer(w, v) {
  const hs = await w.page.$$('pierce/input'); let h = null;
  for (const x of hs) { const b = await x.boundingBox(); if (b && b.y > 100) h = x; }
  await h.click({ clickCount: 3 }); await h.type(v, { delay: 20 }); await S(300);
}
await run({ path: 'subpackageMember/signup/index', identity: who, agree: true, lat: 31.2297, lng: 121.4810 }, async (w) => {
  await S(3000); await w.tap('开始游戏'); await S(5000);
  try { await w.tap('开始游戏', { exact: true }); await S(3000); } catch (e) {}
  try { await w.tap('开启定位', { exact: true, nth: 1 }); await S(2000); } catch (e) {}
  try { await w.tap('轻点跳过'); await S(1200); } catch (e) {}
  if ((await w.text()).includes('同场路线')) { await w.tap('开始', { exact: true }); await S(2000); }
  await w.say('open'); if (shoot) await w.shot(P + '132-player-after-release');
  for (const b of ['继续', '继续探索']) { try { await w.tap(b, { exact: true }); await S(2500); } catch (e) {} }
  await w.loc(31.2297, 121.4810); await S(3000); await w.say('near3'); if (shoot) await w.shot(P + '133-player-near-station3');
  await w.tap('开始任务'); await S(3000); await answer(w, '4'); await w.tap('提交', { exact: true }); await S(3500);
  await w.say('st3'); if (shoot) await w.shot(P + '134-player-station3-right');
  try { await w.tap('继续', { exact: true }); await S(4000); } catch (e) { console.log('no 继续'); }
  await w.say('end1'); if (shoot) await w.shot(P + '135-player-ending');
  for (let i = 0; i < 3; i++) { const t = await w.text(); const m = t.match(/继续|查看结局|完成|收下|好的/); if (!m) break; try { await w.tap(m[0], { exact: true }); } catch (e) { break; } await S(3000); await w.say('end' + (i + 2)); if (shoot) await w.shot(P + '136-player-ending-' + i); }
});
