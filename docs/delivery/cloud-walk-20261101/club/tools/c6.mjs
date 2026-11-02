import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4500);
  for (let i = 0; i < 4; i++) {
    await w.tap('标记到场', {nth:0}); await S(1000);
    await ty(w, '请输入更正原因（至少 2 个字）', '到了'); await w.tap('确定', {exact:true}); await S(2000);
  }
  await w.shot('99-lead-4-arrived'); await txt(w,'after');
  await w.tap('已到场', {exact:true}); await S(1000); await w.shot('100-lead-arrived-tab'); await txt(w,'arrtab');
}
