import { run, S } from './lib.mjs';
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(1500);
  await w.tap('发布主题', { nth: 2 }); await S(1500); await w.say('choice'); await w.shot('28-publish-choice');
});
