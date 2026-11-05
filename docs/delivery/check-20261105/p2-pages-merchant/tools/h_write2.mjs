import { go, sleep } from './go.mjs';
import { typeP } from './h_write.mjs';
const M = 'e2e-openid-9002';
const which = process.argv[2];
if (which === 'coopset') await go('h13-coopset', { path: 'pages/merchant/decor/coop-setting/index', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tap('编辑', { exact: true }); await sleep(1000);
  await w.tap('想要什么样的合作'); await sleep(1200); await shot('h13-coopset-demand-editor');
  console.log((await w.text()).slice(0, 600));
  try { await w.type('textarea', '希望和跑团合作周末路线'); } catch (e) { try { await w.type('input', '希望和跑团合作周末路线'); } catch (e2) { console.log('NOFIELD'); } }
  await sleep(300); await shot('h14-coopset-demand-typed');
  for (const t of ['确定', '完成', '保存']) { try { await w.tap(t, { exact: true }); break; } catch (e) {} }
  await sleep(1200); await shot('h15-coopset-after-field');
  try { await w.tap('保存', { exact: true }); } catch (e) {}
  await sleep(2500); await shot('h16-coopset-after-save');
});
if (which === 'gallery') await go('h17-gallery', { path: 'pages/merchant/decor/gallery/index', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tapSel('.dg-add'); await sleep(2500); await shot('h17-gallery-crop');
  console.log((await w.text()).slice(0, 500));
  for (const t of ['完成', '确定', '使用', '确认']) { try { await w.tap(t, { exact: true }); console.log('tapped', t); break; } catch (e) {} }
  await sleep(3000); await shot('h18-gallery-one-photo'); await w.tap('保存相册'); await sleep(3500); await shot('h19-gallery-after-save');
});
if (which === 'coupon') await go('h20-coupon', { path: 'subpackageMember/coupon/coupon', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tap('发布优惠券'); await sleep(2000);
  await w.tap('发布优惠券', { exact: true }); await sleep(1200); await shot('h20-coupon-empty-submit');
  await typeP(w, '请输入优惠券名称', '到店立减 5 元');
  await w.tap('开始日期'); await sleep(1200); await shot('h21-coupon-date-picker'); console.log((await w.text()).slice(0, 700));
});
