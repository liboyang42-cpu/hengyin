import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); await w.tap('开始游戏'); await S(3000);
  await w.tap('我到了'); await S(1500); await w.shot('105-member-arrive-modal');
  await w.tap('定位并签到'); await S(3000); await w.shot('106-member-arrived'); await txt(w,'arrived');
}
