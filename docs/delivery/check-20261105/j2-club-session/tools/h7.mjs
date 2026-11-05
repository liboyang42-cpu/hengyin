import { run, S } from './lib.mjs';
// H7: running director view; 报名参与者 → 定向广播 → pick two members by name → write → send
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.tap('报名参与者', { exact: true }); await S(2000); await w.say('roster'); await w.shot('108-director-roster');
  await w.tap('定向广播'); await S(2500); await w.say('bsheet'); await w.shot('109-broadcast-sheet');
  await w.tap('林一帆'); await S(600); await w.tap('王一舟'); await S(600);
  await w.ty('写点什么，比如：第 3 站临时排队，先去第 4 站', '两位请先到喷泉南侧集合'); await S(1200);
  await w.say('picked'); await w.shot('110-broadcast-picked');
  const t2 = await w.text(); const m = t2.match(/发送给 \d+ 人/); console.log('SEND', m && m[0]); if (m) { await w.tap(m[0]); await S(3000); }
  await w.say('sent'); await w.shot('111-broadcast-sent');
});
