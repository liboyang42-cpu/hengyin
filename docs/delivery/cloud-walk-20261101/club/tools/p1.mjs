import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); await w.tap('开始游戏'); await S(3500); await w.shot('88-member-gather'); await txt(w,'gather');
  await w.tap('我到了'); await S(2500); await w.shot('89-member-arrived'); await txt(w,'arrived');
}
