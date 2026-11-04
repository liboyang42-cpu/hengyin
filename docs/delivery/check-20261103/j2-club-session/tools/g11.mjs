import { run, S } from './lib.mjs';
// G11: running director view; 报名参与者 → 定向广播 → pick two members → write → send
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.shot('112-director-running');
  await w.scroll(600); await w.say('running2'); await w.shot('113-director-running-2'); await w.scroll(-2000); await S(500);
  await w.tap('报名参与者', { exact: true }); await S(1500); await w.tap('定向广播'); await S(2500);
  await w.say('bsheet'); await w.shot('114-broadcast-sheet');
  await w.tap('城瘾用户', { nth: 0 }); await S(600); await w.say('after1');
  const t = await w.text(); console.log('COUNT', (t.match(/发送给 \d+ 人/) || [''])[0]);
  await w.tap('城瘾用户', { nth: 1 }); await S(600);
  await w.ty('写点什么，比如：第 3 站临时排队，先去第 4 站', '两位请先到喷泉南侧集合'); await S(1200);
  await w.say('picked'); await w.shot('115-broadcast-picked');
  const t2 = await w.text(); const m = t2.match(/发送给 \d+ 人/); if (m) { await w.tap(m[0]); await S(3000); }
  await w.say('sent'); await w.shot('116-broadcast-sent');
});
