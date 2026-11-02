import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4000); await txt(w,'td'); await w.shot('37-lead-topic-detail');
  await w.scroll(700); await S(600); await txt(w,'td2'); await w.shot('38-lead-topic-detail-2');
  await w.scroll(900); await S(600); await txt(w,'td3'); await w.shot('39-lead-topic-detail-3');
}
