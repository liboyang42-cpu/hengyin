import { go, sleep } from './go.mjs';
const M = 'e2e-openid-9002';
const which = process.argv[2];
export async function typeP(w, ph, text, nth = 0) {
  const all = [];
  for (const h of await w.page.$$('pierce/input')) all.push(h);
  for (const h of await w.page.$$('pierce/textarea')) all.push(h);
  const m = [];
  for (const h of all) { const p = await h.evaluate((e) => e.getAttribute('placeholder') || ''); if (p.includes(ph)) m.push(h); }
  const h = m[nth]; if (!h) throw new Error('no input ' + ph);
  await h.click(); await h.type(String(text), { delay: 10 }); await sleep(250);
}
if (which === 'perk') await go('h01-perk', { path: 'pages/merchant/decor/perks/index', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tap('添加权益'); await sleep(1200); await shot('h01-perk-form');
  await typeP(w, '例如', '双人手冲体验券'); await typeP(w, '请输入金额', '58', 0); await typeP(w, '请输入金额', '20', 1); await typeP(w, '请输入正整数', '30');
  await shot('h03-perk-filled');
  await w.tap('保存'); await sleep(2500); await shot('h04-perk-after-save');
});
if (which === 'coopset') await go('h05-coopset', { path: 'pages/merchant/decor/coop-setting/index', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tap('编辑', { exact: true }); await sleep(1200); await shot('h05-coopset-edit');
  console.log((await w.text()).slice(0, 800));
  try { await typeP(w, '想要', '希望和跑团合作周末路线'); } catch (e) { console.log(e.message); }
  await shot('h06-coopset-filled'); await w.tap('保存'); await sleep(2500); await shot('h07-coopset-after-save');
});
if (which === 'gallery') await go('h08-gallery', { path: 'pages/merchant/decor/gallery/index', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tap('+'); await sleep(2000); await shot('h08-gallery-crop');
  console.log((await w.text()).slice(0, 500));
  for (const t of ['完成', '确定', '使用']) { try { await w.tap(t, { exact: true }); break; } catch (e) {} }
  await sleep(2500); await shot('h09-gallery-one-photo'); await w.tap('保存相册'); await sleep(3000); await shot('h10-gallery-after-save');
});
if (which === 'coupon') await go('h11-coupon', { path: 'subpackageMember/coupon/coupon', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tap('发布优惠券'); await sleep(2000); await shot('h11-coupon-form'); console.log((await w.text()).slice(0, 1200));
  await w.scroll(600); await shot('h12-coupon-form-scrolled'); console.log((await w.text()).slice(0, 1200));
});
