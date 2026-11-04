import { run, S } from './lib.mjs';
// G8: merchant opens the theme from 合作中心 → 承接进度 → looks for 撤回/放弃
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.say('coop');
  await w.tap('人民广场 · 喷泉报时'); await w.waitText('承接', 15000); await S(1500);
  await w.say('theme'); await w.shot('106-merchant-theme-after-win');
  await w.scroll(900); await w.say('theme2'); await w.shot('107-merchant-theme-after-win-2');
});
