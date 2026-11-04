import { run, S } from './lib.mjs';
// G6: lead → 开始活动 → confirm
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.tap('开始活动', { exact: true }); await S(400); await w.say('start-toast'); await w.shot('102-director-start-confirm'); await S(1200);
  await w.say('start1');
  const t = await w.text(); if ((t.match(/开始活动/g) || []).length > 1) { await w.tap('开始活动', { exact: true, nth: 0 }); await S(3500); }
  await w.say('started'); await w.shot('103-director-running');
  await w.scroll(600); await w.say('started2'); await w.shot('104-director-running-2');
});
