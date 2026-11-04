import { run, S } from './lib.mjs';
// D2: lead taps 招商 on station 1 and invites a merchant
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('商家', { exact: true }); await S(3500);
  await w.tap('招商', { exact: true, nth: 1 }); await S(2000); await w.say('invite'); await w.shot('47-recruit-station-sheet');
  await w.scroll(600); await w.say('invite2'); await w.shot('48-recruit-station-sheet-2');
});
