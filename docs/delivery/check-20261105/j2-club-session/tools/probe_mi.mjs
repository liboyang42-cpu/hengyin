import { run, S } from './lib.mjs';
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.tap('看详情', { nth: 0 }); await S(12000);
  console.log('OPTS', JSON.stringify(await w.eval(`getCurrentPages().slice(-1)[0].options`)));
  const d = await w.data(); console.log('D', JSON.stringify({role:d.role, fmj:d.fromMerchantJoin, lf:d.loadFailed, ble:d.browseLoadError, name:d.info&&d.info.name, heroImg:d.heroImg, stateText:d.stateText, primaryText:d.primaryText, isJoin:d.isJoin}));
  await w.say('mi'); await w.shot('_probe-mi');
  console.log('CONSOLE_ALL', JSON.stringify(w.console().slice(-20)));
});
