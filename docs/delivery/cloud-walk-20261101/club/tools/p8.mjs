import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); try { await w.tap('开始游戏'); } catch (e) {} await S(4000);
  for (let i=0;i<3;i++) { try { await w.tap('继续探索'); await S(2500); } catch (e) {} }
  await txt(w,'card'); await w.shot('124-member-card3');
  await w.tap('开始任务'); await S(3500); await txt(w,'task'); await w.shot('125-member-photo-task');
  for (const t of ['拍照', '拍一张', '上传', '去拍照']) { try { await w.tap(t); await S(4000); break; } catch (e) {} }
  await w.tap('选取', {exact:true}); await S(5000); await w.shot('126-member-photo-after'); await txt(w,'after');
}
