import { go, sleep } from './go.mjs';
const M = 'e2e-openid-9002';
async function typeP(w, ph, text, nth = 0, clear = false) {
  const all = [];
  for (const h of await w.page.$$('pierce/input')) all.push(h);
  for (const h of await w.page.$$('pierce/textarea')) all.push(h);
  const m = [];
  for (const h of all) { const p = await h.evaluate((e) => e.getAttribute('placeholder') || ''); if (p.includes(ph)) m.push(h); }
  const h = m[nth]; if (!h) throw new Error('no input ' + ph);
  await h.click({ clickCount: clear ? 3 : 1 }); if (clear) await w.page.keyboard.press('Backspace');
  await h.type(String(text), { delay: 10 }); await sleep(250);
}
await go('h22-coupon', { path: 'subpackageMember/coupon/coupon', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tap('发布优惠券'); await sleep(2000);
  await typeP(w, '请输入优惠券名称', '到店立减 5 元');
  await w.tap('开始日期'); await sleep(1000); await w.tap('11月6日'); await sleep(500); await shot('h22-coupon-date-picked');
  await w.tap('完成', { exact: true }); await sleep(800);
  await typeP(w, '请输入投放数量', '50', 0, true);
  await w.tap('请选择'); await sleep(900); await shot('h23-coupon-type-sheet'); console.log('TYPES', (await w.text()).slice(-300));
  const t = await w.text();
  const opt = ['礼品券'].find((x) => t.includes(x));
  if (opt) { await w.tap(opt, { exact: true }); await sleep(900); }
  await shot('h24-coupon-type-picked'); console.log((await w.text()).slice(0, 900));
  await typeP(w, '请输入优惠券说明', '到店出示即可使用');
  await shot('h25-coupon-filled');
  await w.tap('发布优惠券', { exact: true }); await sleep(2500); await shot('h26-coupon-after-submit');
  console.log('ROUTE', JSON.stringify(await w.route()));
});
