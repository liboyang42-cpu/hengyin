import { run, S } from './lib.mjs';
// F8: m1 creates a 2-person team from order 75 (same screens as F5, no shots)
await run({ path: 'subpackageMember/orderinfo/orderinfo?id=75', identity: 'j2-m1', agree: true }, async (w) => {
  await S(3500); await w.scroll(600); await w.tap('叫上队友'); await S(2500);
  await w.tap('2 人队伍'); await S(500); await w.tap('确定', { exact: true }); await S(3000); console.log('ROUTE3', JSON.stringify(await w.route()));
});
