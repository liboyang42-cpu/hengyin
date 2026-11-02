import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4000);
  const all = await w.page.$$('pierce/input[placeholder="0"]');
  await all[1].click(); await all[1].type('6'); await S(300);
  await w.tap('重新计算地板'); await S(2000);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSliderChange({ detail: { value: 39 } })); await S(800);
  await w.shot('42-lead-pricing-filled'); await txt(w,'filled');
  await w.tap('确认终价并提交开售审核'); await S(2500); await txt(w,'after'); await w.shot('43-lead-pricing-submitted');
}
