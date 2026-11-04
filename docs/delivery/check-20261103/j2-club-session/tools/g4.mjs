import { run, S } from './lib.mjs';
// G4: lead on the director page: 开始准备 → overview
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.shot('96-director-before-prepare');
  await w.tap('开始准备'); await S(1500); await w.say('prep-confirm'); await w.shot('97-director-prepare-confirm');
  await w.tap('开始准备', { exact: true, nth: 0 });
  await S(3000); await w.say('prepared'); await w.shot('98-director-prepared');
  await w.scroll(600); await w.say('prepared2'); await w.shot('99-director-prepared-2');
});
