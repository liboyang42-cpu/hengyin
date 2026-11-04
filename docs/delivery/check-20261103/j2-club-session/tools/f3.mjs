import { run, S } from './lib.mjs';
import { call } from './f2.mjs';
// F3: session sheet → 下一步 · 去结算 → checkout page
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-m2', agree: true }, async (w) => {
  await S(3000); for (let i = 0; i < 3; i++) { await w.scroll(800); await S(400); }
  await call(w, 'bmClick'); await S(2000);
  await w.shot('74-member-session-sheet'); await w.tap('领队场'); await S(800); await w.tap('下一步 · 去结算'); await S(3000); console.log('ROUTE2', JSON.stringify(await w.route()));
  await w.say('checkout'); await w.shot('75-member-checkout');
  await w.scroll(700); await w.say('checkout2'); await w.shot('76-member-checkout-2');
});
