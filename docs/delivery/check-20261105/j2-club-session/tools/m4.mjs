import { run, S } from './lib.mjs';
// M4: merchant opens the free-explore theme after approval → 设置到店权益 (supply offer)
const who = process.argv[2] || 'e2e-openid-9002', shoot = !!process.env.SHOOT;
await run({ path: 'pages/merchant/coop-center/index', identity: who, agree: true }, async (w) => {
  await S(3500); await w.tap('人民广场 · 慢逛一下午'); await S(5000); await w.say('view'); if (shoot) await w.shot('181-fe-merchant-after-approval');
  await w.scroll(600); await S(400); await w.say('view2'); if (shoot) await w.shot('182-fe-merchant-after-approval-2');
  const t = await w.text(); let m = t.match(/设置到店权益|设置权益|配置权益|去设置/); if (!m) { await w.tap('承接进度'); await S(3500); console.log('ROUTE0', JSON.stringify(await w.route())); await w.say('progress'); if (shoot) await w.shot('182b-fe-merchant-progress'); const t3 = await w.text(); m = t3.match(/设置到店权益|设置权益|配置权益|去设置|设置/); } console.log('BTN', m && m[0]);
  if (m) { await w.tap(m[0]); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('offer'); if (shoot) await w.shot('183-fe-merchant-offer-form'); }
});
