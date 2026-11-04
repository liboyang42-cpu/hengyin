import { run, S } from './lib.mjs';
await run({ path: 'subpackageMember/signup/index', identity: 'j2-m2', agree: true, lat: 31.2317, lng: 121.4752 }, async (w) => {
  await S(3000); await w.tap('开始游戏'); await S(5000);
  try { await w.tap('开始游戏', { exact: true }); await S(3000); } catch (e) {}
  console.log('BEFORE', JSON.stringify(await w.data('gpsOk')), JSON.stringify(await w.data('gpsHint')));
  const pt = await w.tap('开启定位', { exact: true, nth: 1 }); console.log('PT', JSON.stringify(pt)); await S(2500);
  console.log('AFTER', JSON.stringify(await w.data('gpsOk')), JSON.stringify(await w.data('gpsHint')));
  const log = await w.log(); console.log('LOG', JSON.stringify(log.slice(-8)).slice(0, 1500));
});
