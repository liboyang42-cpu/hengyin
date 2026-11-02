import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); await w.shot('111-member-play-running'); await txt(w,'p');
  try { await w.tap('开始游戏'); await S(4000); await w.shot('112-member-play-running-2'); await txt(w,'p2'); } catch (e) { console.log('no start btn'); }
}
