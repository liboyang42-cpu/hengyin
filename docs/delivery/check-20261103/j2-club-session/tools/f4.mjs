import { run, S } from './lib.mjs';
import { call } from './f2.mjs';
// F4: checkout → 参与人信息 (opens the participant page) → fill → back to checkout → consent → pay (rig payment seam)
const who = process.argv[2] || 'j2-m2', nm = process.argv[3] || '周小雨', ph = process.argv[4] || '13800002222', shoot = who === 'j2-m2';
await run({ path: 'pages/topic/index/index?id=990014', identity: who, agree: true }, async (w) => {
  await S(3000); await call(w, 'bmClick'); await S(2000);
  await w.tap('领队场'); await S(800); await w.tap('下一步 · 去结算'); await S(3000);
  await w.tap('参与人信息'); await S(1500); if (shoot) await w.shot('77-member-participant-form');
  try { await w.ty('输入姓名', nm); await w.ty('输入手机号', ph); await w.tap('保存参与人信息'); await S(2500); }
  catch (e) { await w.say('participant-existing'); try { await w.tap(nm, { nth: 1 }); await S(1500); } catch (e2) { console.log('pick fail'); } }
  console.log('ROUTE2', JSON.stringify(await w.route()));
  await w.say('back'); if (shoot) await w.shot('78-member-checkout-with-participant');
  try { await w.tap('同意将姓名、手机号提供给主办方，用于报名核验'); await S(600); } catch (e) { console.log('noconsent'); }
  await w.pay('success');
  const t = await w.text(); const m = t.match(/确认价格并付款|付款/); console.log('PAYBTN', m && m[0]);
  await w.tap(m ? m[0] : '付款', { exact: true }); await S(800); if (shoot) await w.shot('79a-member-late-refund-warning'); await w.tap('继续下单', { exact: true }); await S(400); await w.say('paying'); await S(7000);
  console.log('ROUTE3', JSON.stringify(await w.route())); await w.say('paid'); if (shoot) await w.shot('79-member-paid');
});
