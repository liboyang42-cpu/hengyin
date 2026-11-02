import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); try { await w.tap('开始游戏'); } catch (e) {} await S(4000);
  await w.tap('继续探索'); await S(3000); await w.shot('115-member-explore'); await txt(w,'e1');
  try { await w.tap('继续探索'); await S(3000); } catch (e) {}
  await w.shot('116-member-explore-2'); await txt(w,'e2');
}
