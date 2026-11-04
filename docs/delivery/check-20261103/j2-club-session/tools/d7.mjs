import { run, S } from './lib.mjs';
// D7: lead sees the merchant's station application in the 招商 layer and approves it
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('商家', { exact: true }); await S(3500);
  await w.say('layer'); await w.shot('59-lead-recruit-layer-with-application');
});
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('商家', { exact: true }); await S(3500);
  await w.tap('确认', { exact: true }); await S(1500); await w.say('confirm1'); await w.shot('60-lead-confirm-sheet');
  await w.tap('确认', { exact: true }); await S(3000);
  await w.say('confirmed'); await w.shot('61-lead-after-confirm');
});
