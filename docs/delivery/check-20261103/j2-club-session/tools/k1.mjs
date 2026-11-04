import { run, S } from './lib.mjs';
import { call } from './f2.mjs';
// K1: m3 buys the self-play pass. Taps on pages/topic/index don't reach handlers in the rig, so the page handlers bound to
// the same controls (onHeroCta, onBuyGo, onCheckoutProfile, onSelfPlayConsentChange, onSelfPlayConfirm) are invoked.
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-m3', agree: true }, async (w) => {
  await S(3500);
  await call(w, 'onHeroCta', '{}'); await S(2500); await w.shot('151-selfplay-sheet');
  await call(w, 'onBuyGo', '{ currentTarget: { dataset: {} } }'); await S(3500); await w.shot('152-selfplay-checkout');
  await call(w, 'onCheckoutProfile', '{}'); await S(2500); console.log('R', JSON.stringify(await w.route())); await w.say('profile');
  try { await w.tap('陈小满'); await S(2000); } catch (e) { console.log('pick', e.message); }
  console.log('R2', JSON.stringify(await w.route())); await w.say('back');
  await call(w, 'onSelfPlayConsentChange', '{ detail: { checked: true } }'); await S(500); await w.shot('152b-selfplay-checkout-ready');
  await w.pay('success'); await call(w, 'onSelfPlayConfirm', '{}'); await S(1500); await w.say('paying');
  try { await w.tap('继续下单', { exact: true }); } catch (e) {} await S(7000);
  console.log('ROUTE3', JSON.stringify(await w.route())); await w.say('paid'); await w.shot('153-selfplay-paid');
});
