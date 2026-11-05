import { run, S } from './lib.mjs';
// K1: lead's 管理 › 结算 (arg = shot prefix); then the director's 查看结算报告
const tag = process.argv[2] || '143';
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000);
  await w.tap('结算', { exact: true }); await S(3500); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('settle'); await w.shot(tag + '-club-settlement');
  await w.scroll(700); await S(400); await w.say('settle2'); await w.shot(tag + 'b-club-settlement-2');
});
if (!process.env.NOREPORT) await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.tap('查看结算报告'); await S(3500); console.log('ROUTE2', JSON.stringify(await w.route()));
  await w.say('report'); await w.shot(tag + 'c-session-settlement-report');
});
