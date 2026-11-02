import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.shot('128-lead-story');
  await w.tap('玩法', {exact:true}); await S(2500); await w.shot('129-lead-story-games'); await txt(w,'games');
  for (const t of ['查看答案']) { try { await w.tap(t); await S(3000); await w.shot('130-lead-answer'); await txt(w,'ans'); } catch (e) { console.log(e.message); } }
}
