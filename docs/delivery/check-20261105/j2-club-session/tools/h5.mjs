import { run, S } from './lib.mjs';
// H5: merchant home → the session's station card → accept → readiness (capacity typed; service date/time through the page's own handlers: wheels can't scroll in the rig)
await run({ path: 'pages/merchant/index/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(4000); await w.say('home'); await w.shot('100-merchant-home-session-card');
  await w.scroll(500); await S(400); await w.say('home2'); await w.shot('100b-merchant-home-2');
  // the session's station card sits second in the horizontal 项目 carousel; swipe it into view, then tap it
  await w.page.evaluate(() => { const f = (r) => { for (const e of r.querySelectorAll('*')) { if (e.shadowRoot) { const x = f(e.shadowRoot); if (x) return x; } if (e.scrollWidth > e.clientWidth + 50 && getComputedStyle(e).overflowX !== 'visible' && e.getBoundingClientRect().top > 200) return e; } return null; }; const sc = f(document); if (sc) sc.scrollLeft = 400; });
  await S(1200); await w.shot('100c-merchant-home-carousel-2'); await w.tap('2026-11-05'); await S(4000);
  console.log('ROUTE1', JSON.stringify(await w.route())); await w.say('node'); await w.shot('101-merchant-station-invite');
  if (!/game-node/.test(JSON.stringify(await w.route()))) return;
  await w.tap('接受邀请', { exact: true }); await S(1500); try { await w.modal('confirm'); } catch (e) { const t2 = await w.text(); if ((t2.match(/接受邀请/g) || []).length > 1) await w.tap('接受邀请', { exact: true, nth: 1 }); }
  await S(3000); await w.say('accepted'); await w.shot('102-merchant-station-accepted');
  try { await w.ty('每个时段最多接待人数', '6'); } catch (e) { console.log('no capacity input', e.message); }
  const today = new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
  await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; p.onServiceDateChange&&p.onServiceDateChange({detail:{value:'${today}'}}); p.onServiceTimeConfirm&&p.onServiceTimeConfirm({detail:{value:['09:00','18:00']}});})()`); await S(500);
  try { await w.ty('给主办方留一句现场说明', '外摆区南侧，报“喷泉”领一杯手冲'); } catch (e) { console.log('no note'); }
  await w.say('form'); await w.shot('103-merchant-station-ready-form');
  await w.tap('确认准备完成'); await S(3000); await w.say('ready'); await w.shot('104-merchant-station-ready');
});
