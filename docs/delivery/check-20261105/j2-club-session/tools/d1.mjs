import { run, S } from './lib.mjs';
// D1: lead's 管理 › 现在要做 in 招募 stage → 去招商 → 招商 layer
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000); await w.say('manage'); await w.shot('24-manage-recruiting');
  await w.tap('去招商', { nth: 1 }); await S(4000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('recruit'); await w.shot('25-recruit-layer');
  await w.scroll(600); await w.say('recruit2'); await w.shot('26-recruit-layer-2');
});
