import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(3500);
  await w.tap('加入', {exact:true}); await S(2500); await txt(w,'joined'); await w.shot('31-member-joined');
  await w.tap('活动', {exact:true}); await S(2500); await txt(w,'acts'); await w.shot('32-member-activities');
  console.log('DATA', JSON.stringify(await w.data('activeTab')), JSON.stringify((await w.data('topics')||[]).length));
}
