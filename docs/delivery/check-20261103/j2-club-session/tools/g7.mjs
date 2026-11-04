import { run, S } from './lib.mjs';
// G7: lead taps the blocked station in 准备总览 to see options
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.scroll(600); await w.tap('人民广场音乐喷泉'); await S(2000); await w.say('node'); await w.shot('105-director-blocked-station');
});
