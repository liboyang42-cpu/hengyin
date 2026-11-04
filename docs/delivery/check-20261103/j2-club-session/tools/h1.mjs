import { run, S } from './lib.mjs';
// H1: m2 at the fountain: open play from 票夹, go through gather, reach station 1
const who = process.argv[2] || 'j2-m2', shoot = who === 'j2-m2';
await run({ path: 'subpackageMember/signup/index', identity: who, agree: true, lat: 31.2317, lng: 121.4752 }, async (w) => {
  await S(3000); await w.tap('开始游戏'); await S(5000);
  try { await w.tap('开始游戏', { exact: true }); await S(3000); } catch (e) {}
  try { await w.tap('开启定位', { exact: true, nth: 1 }); await S(2000); } catch (e) {}
  try { await w.tap('轻点跳过'); await S(1200); } catch (e) {}
  await w.say('running'); if (shoot) await w.shot('117-player-running');
  await w.loc(31.2317, 121.4752); await S(3000);
  await w.say('atstation'); if (shoot) await w.shot('118-player-chapter-story');
  await w.tap('继续探索'); await S(3000); await w.say('explore'); if (shoot) await w.shot('119-player-explore');
  await w.loc(31.2317, 121.4752); await S(3000); await w.say('explore2'); if (shoot) await w.shot('120-player-near-station1');
  await w.tap('开始任务'); await S(3500); await w.say('task'); if (shoot) await w.shot('121-player-task1');
  if (shoot) { await answer(w, '7'); await w.tap('提交', { exact: true }); await S(1500); await w.say('wrong'); await w.shot('122-player-wrong'); }
  await answer(w, '8'); await w.tap('提交', { exact: true }); await S(3000); await w.say('right'); if (shoot) await w.shot('123-player-right');
});
async function answer(w, v) {
  const hs = await w.page.$$('pierce/input'); let h = null;
  for (const x of hs) { const b = await x.boundingBox(); if (b && b.y > 100) h = x; }
  await h.click({ clickCount: 3 }); await h.type(v, { delay: 20 }); await S(300);
}
