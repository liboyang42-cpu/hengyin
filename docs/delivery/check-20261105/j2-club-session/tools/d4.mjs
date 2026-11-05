import { run, S } from './lib.mjs';
// D4: lead sees the application in the 招商 layer, taps 确认, and lands on the terms invitation; sends it
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000);
  await w.tap('去招商', { nth: 1 }); await S(4000); await w.say('layer'); await w.shot('38-recruit-layer-with-application');
  await w.tap('确认', { exact: true }); await S(1500); await w.say('confirm1'); await w.shot('39-lead-confirm-sheet');
  try { await w.tap('确认', { exact: true }); } catch (e) { console.log(e.message); } await S(3500);
  console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('after'); await w.shot('40-lead-after-confirm');
  await w.scroll(500); await S(500); await w.say('after2'); await w.shot('41-lead-invite-2');
});
