import { run, S } from './lib.mjs';
// M6: merchant 9002 opens 店铺 › 常备权益 (decor/perks) to create the perk the 自由探索 supply needs
await run({ path: 'pages/merchant/decor/perks/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.say('perks'); await w.shot('187-fe-merchant-perks');
  const t = await w.text(); const m = t.match(/新建|添加|创建/); console.log('BTN', m && m[0]);
  if (m) { await w.tap(m[0]); await S(2500); await w.say('form'); await w.shot('188-fe-merchant-perk-form');
    const inputs = await w.page.$$('pierce/input'); console.log('INPUTS', JSON.stringify(await Promise.all(inputs.map(h => h.evaluate(e => e.placeholder))))); }
});
