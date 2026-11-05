import { run, S } from './lib.mjs';
// F1: lead taps 开一场 on 管理, creates one session today 12:00. The time wheel cannot be scrolled in the rig,
// so the page's own onStartTimeChange receives the value the wheel would emit (HARNESS seam, labelled).
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000); await w.say('manage'); await w.shot('64-manage-on-sale');
  await w.tap('开一场'); await S(3500); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('ops'); await w.shot('65-open-session-form');
  await w.eval(`getCurrentPages().slice(-1)[0].onStartTimeChange({ detail: { value: '12:00' } })`); await S(500);
  console.log('ST', JSON.stringify(await w.data('startTime')));
  await w.scroll(700); await S(500); await w.say('ops2'); await w.shot('66-open-session-form-2');
  const t = await w.text(); const m = t.match(/创建系列场次|创建场次|开一场|发布场次|确认开场/g); console.log('BTNS', JSON.stringify(m));
  await w.tap('创建系列场次'); await S(3500); await w.say('created'); await w.shot('67-session-created');
  await w.scroll(-3000); await S(500); await w.say('top'); await w.shot('68-session-list');
});
