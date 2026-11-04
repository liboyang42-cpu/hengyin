import { run, S } from './lib.mjs';
// H5: m2 walks to station 2 (museum) and tries to answer
await run({ path: 'subpackageMember/signup/index', identity: 'j2-m2', agree: true, lat: 31.2284, lng: 121.4755 }, async (w) => {
  await S(3000); await w.tap('开始游戏'); await S(5000);
  try { await w.tap('开始游戏', { exact: true }); await S(3000); } catch (e) {}
  try { await w.tap('开启定位', { exact: true, nth: 1 }); await S(2000); } catch (e) {}
  try { await w.tap('轻点跳过'); await S(1200); } catch (e) {}
  await w.loc(31.2284, 121.4755); await S(2500); await w.say('p1'); await w.shot('129-player-after-station1');
  try { await w.tap('继续探索'); await S(2500); } catch (e) {}
  try { await w.tap('继续', { exact: true }); await S(2500); } catch (e) {}
  await w.say('p2'); await w.shot('130-player-station2');
  try { await w.tap('开始任务'); await S(3000); await w.tap('铜鼎'); await S(500); await w.tap('提交', { exact: true }); await S(2000); } catch (e) { console.log('task', e.message); }
  await w.say('p3'); await w.shot('131-player-station2-blocked');
});
