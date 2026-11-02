import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.shot('63-lead-session13'); await txt(w,'s13');
  await w.tap('取消本场', {exact:true}); await S(1500); await w.shot('64-lead-cancel-sheet'); await txt(w,'sheet');
  await ty(w, '请输入取消原因（至少 2 个字）', '领队临时有事，本场取消，费用原路退回，抱歉！');
  await S(500); await w.shot('65-lead-cancel-filled');
  await w.tap('确认取消'); await S(1500); await w.shot('66-lead-cancel-confirm'); await txt(w,'confirm');
  await S(800); await w.shot('66b-lead-cancel-modal2'); await txt(w,'modal2'); try { await w.tap('取消本场', {exact:true, nth:1}); } catch (e) { console.log('no second'); }
  await S(3500); await w.shot('67-lead-cancel-done'); await txt(w,'done');
}
