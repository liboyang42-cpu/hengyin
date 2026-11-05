import { go, sleep } from './go.mjs';
const M = 'e2e-openid-9002';
const which = process.argv[2];
async function typeP(w, ph, text, nth = 0) {
  const all = [];
  for (const h of await w.page.$$('pierce/input')) all.push(h);
  for (const h of await w.page.$$('pierce/textarea')) all.push(h);
  const m = [];
  for (const h of all) { const p = await h.evaluate((e) => e.getAttribute('placeholder') || ''); if (p.includes(ph)) m.push(h); }
  const h = m[nth]; if (!h) throw new Error('no input ' + ph);
  await h.click(); await h.type(String(text), { delay: 10 }); await sleep(250);
}
if (which === 'gallery') await go('h30-gallery', { path: 'pages/merchant/decor/gallery/index', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tapSel('.dg-add'); await sleep(2500); await shot('h30-gallery-crop');
  await w.tap('选取', { exact: true }); await sleep(3000); await shot('h31-gallery-one-photo');
  await w.tap('保存相册'); await sleep(3500); await shot('h32-gallery-after-save');
  console.log('ROUTE', JSON.stringify(await w.route()));
});
if (which === 'npc') await go('h33-npc', { path: 'pages/merchant/decor/ai-npc/index', identity: M }, async (w, shot) => {
  await sleep(2000);
  for (let i = 0; i < 4; i++) { try { await w.tap('下一步', { exact: true }); await sleep(900); } catch (e) { break; } }
  try { await w.tap('开始', { exact: false }); await sleep(900); } catch (e) {}
  await shot('h33-npc-form'); console.log((await w.text()).slice(0, 900));
  await w.tap('知道了', { exact: true }); await sleep(800);
  await w.tap('编辑', { exact: true }); await sleep(800);
  await w.tap('未填写'); await sleep(1000); await shot('h34-npc-name-editor'); console.log('ED', (await w.text()).slice(-300));
  try { await w.type('input', '小咖'); } catch (e) { await w.type('textarea', '小咖'); }
  await shot('h34b-npc-name-typed'); for (const t of ['保存']) { try { await w.tap(t, { exact: true }); break; } catch (e) {} }
  await sleep(1500); await w.tap('选择性格'); await sleep(1000); await shot('h35-npc-personality'); console.log('PE', (await w.text()).slice(-300));
  for (const t of ['温柔', '可爱']) { try { await w.tap(t, { exact: true }); break; } catch (e) {} }
  for (const t of ['完成', '确定']) { try { await w.tap(t, { exact: true }); break; } catch (e) {} }
  await sleep(900); await shot('h36-npc-filled'); console.log('FI', (await w.text()).slice(0, 600));
  for (const t of ['完成编辑', '保存', '保存角色']) { try { await w.tap(t, { exact: true }); console.log('SAVE', t); break; } catch (e) {} }
  await sleep(3000); await shot('h37-npc-after-save'); console.log('AF', (await w.text()).slice(0, 600));
});
if (which === 'citynode') await go('h40-citynode', { path: 'pages/merchant/citynode/create/index', identity: M }, async (w, shot) => {
  await sleep(2000); await w.tap('去配置玩法'); await sleep(2500); await shot('h40-citynode-config-game'); console.log('ROUTE', JSON.stringify(await w.route())); console.log((await w.text()).slice(0, 700));
});
if (which === 'mapply') await go('h45-mapply', { path: 'pages/topic/merchantapply/index', identity: M }, async (w, shot) => {
  await w.relaunch('/pages/topic/merchantapply/index?topicId=990013'); await sleep(2500); await shot('h45-mapply-990013'); console.log((await w.text()).slice(0, 700));
  await w.tap('提交报名'); await sleep(1200); await shot('h46-mapply-empty-submit'); console.log((await w.text()).slice(0, 700));
  await w.tap('点击选择承接地点'); await sleep(1500); await shot('h47-mapply-location');
  await typeP(w, '填写可用空间', '店内 20 平米，可容纳 8 人，周末下午可拍摄');
  await w.tap('上传现场照片'); await sleep(2500); await shot('h48-mapply-photo');
  for (const t of ['选取']) { try { await w.tap(t, { exact: true }); await sleep(2500); } catch (e) {} }
  await shot('h49-mapply-filled'); await w.tap('提交报名'); await sleep(3000); await shot('h50-mapply-after-submit');
  console.log('ROUTE', JSON.stringify(await w.route())); console.log((await w.text()).slice(0, 700));
});
