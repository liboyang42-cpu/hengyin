import { go, sleep } from './go.mjs';
await go('k1-tpl', { path: 'subpackageA/pages/myproject/index', identity: 'e2e-openid-9003' }, async (w, shot) => {
  await sleep(3000); await shot('k1-tpl-01-myproject-before');
  await w.page.evaluate(() => { const ps = getCurrentPages(); ps[ps.length - 1].saveAsTemplate({ currentTarget: { dataset: { id: 990014, title: 'E2E 探店日一期' } } }); });
  await sleep(1200); await shot('k1-tpl-02-confirm');
  await w.tapSel('.mo__btn--act'); await sleep(3000); await shot('k1-tpl-03-after-save');
  try { await w.tap('模板', { exact: true }); await sleep(2000); await shot('k1-tpl-04-template-tab'); } catch (e) { console.log('no tab', e.message); }
});
await go('k1-tpl-player', { path: 'pages/topic/index/index?id=990014', identity: 'e2e-openid-9004' }, async (w, shot) => { await sleep(3500); await shot('k1-tpl-05-theme-still-on-sale'); });
