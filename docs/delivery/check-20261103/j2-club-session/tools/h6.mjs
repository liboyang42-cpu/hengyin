import { run, S } from './lib.mjs';
// H6: lead ends the session; player view afterwards
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.tap('结束活动', { exact: true }); await S(1500); await w.shot('132-lead-end-confirm');
  await w.tap('结束活动', { exact: true, nth: 1 }); await S(3500); await w.say('ended'); await w.shot('133-lead-ended');
  await w.scroll(700); await w.say('ended2'); await w.shot('134-lead-ended-recap');
  await w.scroll(700); await w.say('ended3'); await w.shot('135-lead-ended-recap-2');
});
await run({ path: 'subpackageMember/signup/index', identity: 'j2-m3', agree: true }, async (w) => {
  await S(3000); await w.say('wallet-after'); await w.shot('136-member-wallet-after-end');
  try { await w.tap('开始游戏'); await S(5000); } catch (e) {} await w.say('play-after'); await w.shot('137-member-play-after-end');
});
