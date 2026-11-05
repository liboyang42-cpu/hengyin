import { run, S } from './lib.mjs';
// M5: merchant: 承接进度 › 填写实际供给 › 选择常备权益 › 核销额度 › 确认实际供给 (arg: openid, perk text)
const who = process.argv[2] || 'e2e-openid-9002', shoot = !!process.env.SHOOT;
await run({ path: 'pages/merchant/coop-center/index', identity: who, agree: true }, async (w) => {
  await S(3500); await w.tap('人民广场 · 慢逛一下午'); await S(5000); await w.scroll(600); await S(400);
  await w.tap('承接进度'); await S(3500); await w.tap('填写实际供给', { nth: -1 }); await S(3000);
  await w.say('supply'); if (shoot) await w.shot('183-fe-merchant-supply-form');
  await w.tap('选择常备权益'); await S(2000); await w.say('perkpick'); if (shoot) await w.shot('184-fe-merchant-perk-pick');
  const t = await w.text(); const perk = (t.split('常备权益 | * | ')[1] || '').split(' | ')[0]; console.log('PERK', perk);
  if (perk) { await w.tap(perk.trim()); await S(1200); }
  const inputs = await w.page.$$('pierce/input'); const ph = await Promise.all(inputs.map(h => h.evaluate(e => e.placeholder))); console.log('INPUTS', JSON.stringify(ph));
  for (let i = 0; i < inputs.length; i++) { const b = await inputs[i].boundingBox(); if (b && /额度|份|次/.test(ph[i] || '')) { await inputs[i].click({ clickCount: 3 }); await inputs[i].type('100'); } }
  await S(600); await w.say('filled'); if (shoot) await w.shot('185-fe-merchant-supply-filled');
  await w.tap('确认实际供给'); await S(3000); await w.say('saved'); if (shoot) await w.shot('186-fe-merchant-supply-saved');
});
