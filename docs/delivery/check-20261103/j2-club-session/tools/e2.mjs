import { run, S } from './lib.mjs';
// E2: lead creates one session today 14:30. The time wheel (picker-view) cannot be scrolled in the rig, so the
// page's own onStartTimeChange receives the value the wheel would emit (HARNESS seam, labelled).
await run({ path: 'pages/club/event-ops/index?clubId=990004&recurrence=ONCE', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.shot('67-open-session-form');
  await w.tap('09:00', { exact: true }); await S(1200); await w.shot('67b-time-picker');
  try { await w.tap('完成', { exact: true }); } catch (e) {} await S(800);
  await w.eval(`getCurrentPages().slice(-1)[0].onStartTimeChange({ detail: { value: '14:30' } })`); await S(500);
  console.log('ST', JSON.stringify(await w.data('startTime')));
  await w.scroll(700); await w.shot('68-open-session-form-filled');
  await w.tap('创建系列场次'); await S(300); await w.say('created-toast'); await S(3000); await w.say('created'); await w.shot('69-session-created');
  await w.scroll(-3000); await S(500); await w.say('top'); await w.shot('70-session-list');
});
