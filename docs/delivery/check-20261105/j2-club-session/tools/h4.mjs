import { run, S } from './lib.mjs';
// H4: lead on the director page: 开始准备 → confirm → overview (who is holding up the start?)
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.say('before'); await w.shot('95-director-before-prepare');
  await w.tap('开始准备'); await S(1500); await w.say('prep-confirm'); await w.shot('96-director-prepare-confirm');
  const t = await w.text(); if ((t.match(/开始准备/g) || []).length > 1) await w.tap('开始准备', { exact: true, nth: 0 });
  await S(3500); await w.say('prepared'); await w.shot('97-director-prepared');
  await w.scroll(600); await S(400); await w.say('prepared2'); await w.shot('98-director-prepared-2');
  await w.scroll(600); await S(400); await w.say('prepared3'); await w.shot('99-director-prepared-3');
});
