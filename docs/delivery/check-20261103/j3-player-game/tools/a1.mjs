import { run, S } from './lib.mjs';
await run({ path: 'pages/index/index', identity: 'e2e-openid-9004', agree: true }, async (w) => {
  await S(2500); await w.shot('01-home'); await w.say('home');
  await w.open('pages/topic/index/index?id=990015');
  await S(3000); await w.shot('02-theme'); await w.say('theme');
  await w.scroll(900); await S(800); await w.shot('03-theme-scrolled'); await w.say('theme2');
});
