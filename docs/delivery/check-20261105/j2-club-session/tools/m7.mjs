import { run, S } from './lib.mjs';
// M7: owner 林一帆: 管理 › 现在要做 › 确认终价 → pricing page for the 自由探索 theme; fill price and confirm
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-m1', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2500); await w.say('manage'); await w.shot('189-fe-manage-pricing');
  await w.tap('确认终价', { nth: 1 }); await S(4000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('pricing'); await w.shot('190-fe-pricing-open');
  const inputs = await w.page.$$('pierce/input'); console.log('INPUTS', JSON.stringify(await Promise.all(inputs.map(h => h.evaluate(e => e.placeholder + '|' + e.value)))));
  await w.scroll(700); await S(400); await w.say('pricing2'); await w.shot('191-fe-pricing-2');
});
