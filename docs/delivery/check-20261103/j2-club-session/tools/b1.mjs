import { run, S } from './lib.mjs';
// B1: lead opens the club home, looks around, then 管理
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.shot('16-club-home-lead');
  await w.scroll(600); await w.shot('17-club-home-lead-scrolled');
  await w.scroll(-2000); await S(500);
  await w.tap('管理', { exact: true }); await S(1500);
  await w.say('manage'); await w.shot('18-club-manage');
  await w.scroll(700); await w.say('manage2'); await w.shot('19-club-manage-2');
  await w.scroll(700); await w.say('manage3'); await w.shot('19b-club-manage-3');
});
