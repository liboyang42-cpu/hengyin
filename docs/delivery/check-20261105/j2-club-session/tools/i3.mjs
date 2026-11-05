import { run, S } from './lib.mjs';
// I3: player at station 2 (after the merchant's scan): answer; then chapter 2 is not released yet → 等待放行 screen → 返回地图
const who = process.argv[2] || 'j2b-m1', shoot = !!process.env.SHOOT, P = process.env.P || '', only2 = !!process.env.ONLY2;
await run({ path: 'subpackageMember/signup/index', identity: who, agree: true, lat: 31.2284, lng: 121.4755 }, async (w) => {
  await S(3000); await w.tap('开始游戏'); await S(5000);
  try { await w.tap('开始游戏', { exact: true }); await S(3000); } catch (e) {}
  try { await w.tap('开启定位', { exact: true, nth: 1 }); await S(2000); } catch (e) {}
  try { await w.tap('轻点跳过'); await S(1200); } catch (e) {}
  if ((await w.text()).includes('同场路线')) { await w.tap('开始', { exact: true }); await S(2000); }
  await w.loc(31.2284, 121.4755); await S(3000);
  try { await w.tap('继续探索'); await S(2500); } catch (e) {}
  await w.say('near2'); if (shoot) await w.shot(P + '123-player-station2-after-scan');
  await w.tap('开始任务'); await S(3000); await w.tap('铜鼎'); await S(500); await w.tap('提交', { exact: true }); await S(3000);
  await w.say('st2'); if (shoot) await w.shot(P + '124-player-station2-right');
  if (only2) return;
  try { await w.tap('继续', { exact: true }); await S(3000); } catch (e) { console.log('no 继续'); }
  await w.say('after2'); if (shoot) await w.shot(P + '125-player-after-chapter1');
  await w.loc(31.2297, 121.4810); await S(3500);
  await w.say('hold'); if (shoot) await w.shot(P + '126-player-chapter-hold');
  try { await w.tap('返回地图'); await S(3000); await w.say('map'); if (shoot) await w.shot(P + '127-player-back-to-map'); } catch (e) { console.log('no 返回地图', e.message); }
  const t = await w.text(); const m = t.match(/回到故事/); if (m) { await w.tap('回到故事'); await S(2500); await w.say('back-story'); if (shoot) await w.shot(P + '128-player-back-to-story'); }
});
