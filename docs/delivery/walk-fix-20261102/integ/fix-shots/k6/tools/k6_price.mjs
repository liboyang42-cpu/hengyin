import { go, sleep } from './go.mjs';
await go('k1-price', { path: 'pages/publish/activity/index?scope=MERCHANT', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(3000);
  await w.page.evaluate(() => { const ps = getCurrentPages(); ps[ps.length - 1].goStep(2); });
  await sleep(1500);
  await w.type('input[placeholder="请输入价格"]', '39.99'); await sleep(800);
  await shot('k1-price-3999-typed');
  const v = await w.page.evaluate(() => { const ps = getCurrentPages(); return JSON.stringify(ps[ps.length - 1].data.formData.tickets); });
  console.log('TICKETS', v);
  await w.type('input[placeholder="请输入价格"]', '9'); await sleep(800);
  await shot('k1-price-three-decimals');
  console.log('TICKETS2', await w.page.evaluate(() => { const ps = getCurrentPages(); return JSON.stringify(ps[ps.length - 1].data.formData.tickets); }));
});
