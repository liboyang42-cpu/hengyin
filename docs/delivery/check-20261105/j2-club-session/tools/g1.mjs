import { run, S } from './lib.mjs';
// G1: member: club 活动 tab → session card → theme page (via=session) → 立即报名 → 领队场 → 去结算 → participant → consent → pay (rig seam)
// Real taps first; if a tap on pages/topic/index does not reach its handler (seen in earlier rounds), the page's own handler is called and logged as HARNESS.
const who = process.argv[2] || 'j2b-m1', nm = process.argv[3] || '林一帆', ph = process.argv[4] || '13800002221', shoot = !!process.env.SHOOT; const P = process.env.P || '';
const call = (w, m) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; return p['${m}']({ currentTarget: { dataset: {} } });})()`);
await run({ path: 'pages/club/detail/index?id=990004', identity: who, agree: true }, async (w) => {
  await S(2500); await w.tap('活动', { exact: true }); await S(2000); if (!/人民广场 · 喷泉报时/.test(await w.text())) { console.log('TAB tap retry'); await w.tapSel('.cy-tabs__item:nth-child(2), .tab-item:nth-child(2)'); await S(2000); } if (shoot) { await w.say('events'); await w.shot(P + '69-member-club-events'); }
  await w.tap('人民广场 · 喷泉报时'); await S(3500); console.log('ROUTE1', JSON.stringify(await w.route()), JSON.stringify(await w.eval(`getCurrentPages().slice(-1)[0].options`)));
  if (shoot) { await w.say('topic'); await w.shot(P + '70-member-topic-via-session'); }
  const before = (await w.text());
  const btn = (before.match(/立即报名|查看场次|报名/) || [])[0]; console.log('MAINBTN', btn);
  await w.tap(btn); await S(2000);
  let t = await w.text();
  if (!/领队场/.test(t)) { console.log('HARNESS: tap on', btn, 'did not reach the handler; calling onHeroCta'); await call(w, 'onHeroCta'); await S(2000); t = await w.text(); }
  if (shoot) { await w.say('sessions'); await w.shot(P + '71-member-session-sheet'); }
  await w.tap('领队场'); await S(800);
  await w.tap('下一步 · 去结算'); await S(3000);
  if ((await w.route()).slice(-1)[0].indexOf('topic/index') >= 0) { console.log('HARNESS: 去结算 tap did not navigate; calling onBuyGo'); await call(w, 'onBuyGo'); await S(3000); }
  console.log('ROUTE2', JSON.stringify(await w.route())); if (shoot) { await w.say('checkout'); await w.shot(P + '72-member-checkout'); }
  await w.tap('参与人信息'); await S(1500); if (shoot) await w.shot(P + '73-member-participant-form');
  await w.ty('输入姓名', nm); await w.ty('输入手机号', ph); await w.tap('保存参与人信息'); await S(2500);
  if (shoot) { await w.say('back'); await w.shot(P + '74-member-checkout-with-participant'); }
  try { await w.tap('同意将姓名、手机号提供给主办方，用于报名核验'); await S(600); } catch (e) { console.log('noconsent', e.message); }
  await w.pay('success');
  const t2 = await w.text(); const m = t2.match(/确认价格并付款|付款/); console.log('PAYBTN', m && m[0]);
  await w.tap(m ? m[0] : '付款', { exact: true }); await S(1200); await w.say('prepay'); if (shoot) await w.shot(P + '75-member-pay-confirm');
  try { await w.tap('继续下单', { exact: true }); } catch (e) { console.log('no 继续下单'); } await S(8000);
  console.log('ROUTE3', JSON.stringify(await w.route())); await w.say('paid'); if (shoot) await w.shot(P + '76-member-paid');
  if (shoot) { await w.scroll(700); await S(500); await w.say('paid2'); await w.shot(P + '77-member-order-2'); }
});
