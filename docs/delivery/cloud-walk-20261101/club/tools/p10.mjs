import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); await w.shot('138-member-after-end'); await txt(w,'end');
  try { await w.tap('开始游戏'); await S(4000); await w.shot('139-member-after-end-2'); await txt(w,'end2'); } catch (e) { console.log('no start'); }
}
