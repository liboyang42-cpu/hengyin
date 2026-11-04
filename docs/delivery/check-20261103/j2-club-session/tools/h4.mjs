import { run, S } from './lib.mjs';
// H4: lead releases chapter 2 from the director page
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.scroll(600); await w.shot('127-lead-release-row-under-footer');
  await w.scroll(1500); await S(600); await w.shot('127b-lead-scrolled-to-bottom');
  await w.tap('放行「第二章 大世界的灯」'); await S(1500); await w.say('release'); await w.shot('127c-lead-release-sheet');
  await w.ty('写明放行原因', '第一章两队都已完成'); await S(400);
  const t = await w.text(); const m = t.match(/确认放行|放行/g); console.log('BTN', JSON.stringify(m));
  await w.tap('确认放行').catch(async () => { await w.tap('放行', { exact: true }); }); await S(3000);
  await w.say('released'); await w.shot('128-lead-released');
});
