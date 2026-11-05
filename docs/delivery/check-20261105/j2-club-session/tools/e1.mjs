import { run, S } from './lib.mjs';
// E1: lead's 管理 › 现在要做 in 定价 stage → 确认终价 → pricing page
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000); await w.say('manage'); await w.shot('61-manage-pricing-stage');
  await w.tap('确认终价', { nth: 1 }); await S(3500); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('pricing'); await w.shot('62-pricing-open');
  const inputs = await w.page.$$('pierce/input');
  console.log('INPUTS', JSON.stringify(await Promise.all(inputs.map(h => h.evaluate(e => e.placeholder + '|' + e.value)))));
  await w.scroll(600); await S(500); await w.say('pricing2'); await w.shot('63-pricing-2');
});
