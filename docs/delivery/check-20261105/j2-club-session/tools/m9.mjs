import { run, S } from './lib.mjs';
// M9: after 确认终价 on the 自由探索 theme: what the owner's 管理 says, and what a player sees on the theme page
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-m1', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2500); await w.say('manage'); await w.shot('197-fe-manage-after-pricing');
});
await run({ path: 'pages/topic/index/index?id=990015', identity: 'j2b-m3', agree: true }, async (w) => {
  await S(3500); await w.say('cover'); await w.shot('198-fe-player-theme');
  for (let i = 0; i < 3; i++) { await w.scroll(700); await S(400); }
  await w.say('bottom'); await w.shot('199-fe-player-theme-bottom');
  console.log('HERO', JSON.stringify(await w.data('heroCta')));
});
