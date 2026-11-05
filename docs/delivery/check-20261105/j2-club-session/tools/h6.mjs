import { run, S } from './lib.mjs';
// H6: lead → 开始活动 → confirm; shot of the result (refusal reason or running)
const tag = process.argv[2] || '105';
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.tap('开始活动', { exact: true }); await S(1200); await w.say('start-confirm'); await w.shot(tag + '-director-start-confirm');
  const t = await w.text(); if ((t.match(/开始活动/g) || []).length > 1) { await w.tap('开始活动', { exact: true, nth: 0 }); }
  await S(3500); await w.say('after'); await w.shot(tag + 'b-director-after-start');
  await w.scroll(900); await S(400); await w.say('after2'); await w.shot(tag + 'c-director-after-start-2');
});
