import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); try { await w.tap('开始游戏'); } catch (e) {} await S(4000);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].toggleSessionTools()); await S(1500);
  await w.shot('121-leader-tools'); await txt(w,'tools');
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].leadUnlockFromTools()); await S(2000);
  await w.shot('122-leader-unlock'); await w.tap('确认解锁'); await S(3000); await w.shot('123-leader-unlocked'); await txt(w,'unlocked');
}
