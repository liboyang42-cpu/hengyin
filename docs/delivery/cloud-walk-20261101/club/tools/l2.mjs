import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4000); await w.scroll(1500); await S(800); await w.shot('40-lead-own-topic-bottom'); await txt(w,'bottom');
  console.log('buyOpts', JSON.stringify(await w.data('buyOpts')));
}
