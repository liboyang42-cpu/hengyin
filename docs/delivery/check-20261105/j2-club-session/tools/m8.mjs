import { run, S } from './lib.mjs';
// M8: pricing for the 自由探索 theme: 有人带 cost 60 / 4 people → 重新计算地板 → set the final price → 确认终价并提交开售审核
await run({ path: 'pages/topic/pricing/index?topicId=990015', identity: 'j2b-m1', agree: true }, async (w) => {
  await S(3500);
  await w.tap('确认终价并提交开售审核'); await S(1500); await w.say('empty-submit'); await w.shot('192-fe-pricing-empty-submit');
  const inputs = await w.page.$$('pierce/input[placeholder="0"]');
  await inputs[0].click({ clickCount: 3 }); await inputs[0].type('60'); await inputs[1].click({ clickCount: 3 }); await inputs[1].type('4'); await S(500);
  await w.tap('重新计算地板'); await S(2500); await w.say('recalc'); await w.shot('193-fe-pricing-recalc');
  const h = (await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; return Object.keys(Object.getPrototypeOf(p)).concat(Object.keys(p)).filter(k=>/slider|price/i.test(k)&&typeof p[k]==='function').join(',');})()`)); console.log('HANDLERS', h);
  const d = await w.data(); console.log('DATA', JSON.stringify(Object.fromEntries(Object.entries(d).filter(([k]) => /price|floor|final|max|min/i.test(k)))).slice(0, 800));
  // HARNESS: <slider> is not drawn in the rig; the page's own onSliderChange receives the value the slider would emit
  await w.eval(`getCurrentPages().slice(-1)[0].onSliderChange({ detail: { value: 29 } })`); await S(800); await w.say('slid'); await w.shot('194-fe-pricing-29');
  await w.tap('确认终价并提交开售审核'); await S(1500); await w.say('confirm'); await w.shot('195-fe-pricing-confirm');
  const t = await w.text(); if ((t.match(/确认/g) || []).length > 2) { try { await w.modal('confirm'); } catch (e) { try { await w.tap('确认', { exact: true, nth: -1 }); } catch (e2) {} } }
  await S(3500); await w.say('done'); await w.shot('196-fe-pricing-done');
});
