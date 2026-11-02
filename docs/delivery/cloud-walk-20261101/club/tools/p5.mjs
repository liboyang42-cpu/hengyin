import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); await w.shot('117-leader-play'); await txt(w,'lp');
  try { await w.tap('开始游戏'); } catch (e) {} await S(4000); await w.shot('118-leader-play-2'); await txt(w,'lp2');
  console.log('lead', JSON.stringify(await w.data('lead')).slice(0,400));
}
