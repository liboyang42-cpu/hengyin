import { run, S } from './lib.mjs';
// C6: lead looks for pricing: 更多 sheet on the club theme page, then the public theme page
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('更多', { exact: true }); await S(1200); await w.say('more'); await w.shot('38-club-topic-more');
});
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.say('public'); await w.shot('39-public-topic-as-lead');
  const t = await w.text(); const b = (t.match(/定价中[^|]*|立即报名|报名|购票|去定价|确认终价/) || [])[0]; console.log('BTN', b);
});
