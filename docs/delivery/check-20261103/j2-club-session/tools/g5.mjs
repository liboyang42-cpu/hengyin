import { run, S } from './lib.mjs';
// G5: lead → 更多 / 报名参与者 → 定向广播 → choose two members → send
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500);
  let t = '';
  for (const entry of ['报名参与者', '更多']) { await w.tap(entry, { exact: true }); await S(1500); t = await w.text(); if (t.includes('定向广播')) { console.log('ENTRY', entry); break; } await w.back().catch(() => {}); await S(800); }
  await w.say('entry'); await w.shot('100-director-broadcast-entry');
  await w.tap('定向广播'); await S(2500); await w.say('sheet'); await w.shot('101-director-broadcast-sheet');
});
