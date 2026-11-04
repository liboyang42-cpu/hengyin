import { run, S } from './lib.mjs';
// E1: lead taps 开一场 on 管理 and looks at the session form
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(1500); await w.shot('66-manage-on-sale');
  await w.tap('开一场', { nth: 1 }); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('ops'); await w.shot('67-open-session-form');
  await w.scroll(700); await w.say('ops2'); await w.shot('68-open-session-form-2');
  await w.scroll(700); await w.say('ops3');
});
