import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); await w.shot('113-noteam-play-running'); await txt(w,'p');
  try { await w.tap('开始游戏'); await S(4000); await w.shot('114-noteam-play-running-2'); await txt(w,'p2'); } catch (e) { console.log('no start btn'); }
}
