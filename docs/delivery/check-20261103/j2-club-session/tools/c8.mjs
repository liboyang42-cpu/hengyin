import { run, S } from './lib.mjs';
// C8: pricing page (reached through the page's own onHeroCta → lockedTip, since the dock tap did not fire in the rig)
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.eval(`getCurrentPages().slice(-1)[0].onHeroCta()`); await S(3000);
  await w.shot('42-pricing-open');
  const inputs = await w.page.$$('pierce/input[placeholder="0"]');
  await inputs[0].click({ clickCount: 3 }); await inputs[0].type('60');
  await inputs[1].click({ clickCount: 3 }); await inputs[1].type('4'); await S(500);
  await w.tap('重新计算地板'); await S(2000); await w.say('recalc'); await w.shot('43-pricing-recalc');
  await w.tap('确认终价并提交开售审核'); await S(2500); await w.say('confirmed'); await w.shot('44-pricing-confirmed');
  console.log('ROUTE2', JSON.stringify(await w.route()));
});
