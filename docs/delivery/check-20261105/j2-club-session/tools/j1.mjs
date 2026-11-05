import { run, S } from './lib.mjs';
// J1: lead ends the session (recap); a player's 票夹 and play page afterwards
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.tap('结束活动', { exact: true }); await S(1500); await w.say('confirm'); await w.shot('137-lead-end-confirm');
  await w.tap('结束活动', { exact: true, nth: 1 }); await S(3500); await w.say('ended'); await w.shot('138-lead-ended');
  await w.scroll(700); await S(400); await w.say('ended2'); await w.shot('139-lead-ended-recap');
  await w.scroll(700); await S(400); await w.say('ended3'); await w.shot('140-lead-ended-recap-2');
});
await run({ path: 'subpackageMember/signup/index', identity: 'j2b-m3', agree: true }, async (w) => {
  await S(3000); await w.say('wallet-after'); await w.shot('141-member-wallet-after-end');
  try { await w.tap('开始游戏'); await S(5000); } catch (e) {} await w.say('play-after'); await w.shot('142-member-play-after-end');
});
