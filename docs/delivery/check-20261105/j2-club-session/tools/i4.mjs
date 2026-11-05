import { run, S } from './lib.mjs';
// I4: lead releases chapter 2 from the director page
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.scroll(600); await S(400); await w.say('row'); await w.shot('129-lead-release-row');
  await w.tap('放行「第二章 大世界的灯」'); await S(1500); await w.say('release'); await w.shot('130-lead-release-sheet');
  try { await w.ty('写明放行原因', '第一章全员完成'); await S(400); } catch (e) { console.log('no reason field'); }
  const t = await w.text(); console.log('BTN', JSON.stringify(t.match(/确认放行|放行/g)));
  try { await w.tap('确认放行'); } catch (e) { await w.tap('放行', { exact: true }); } await S(3000);
  await w.say('released'); await w.shot('131-lead-released');
});
