import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(3500);
  await w.tapSel('.manage-now-btn'); await S(1500);
  await w.tap('城市定向 · 顺序探索'); await S(3500);
  await w.shot('20-fabu-open'); await txt(w,'fabu');
  console.log(JSON.stringify(await w.route()));
}
