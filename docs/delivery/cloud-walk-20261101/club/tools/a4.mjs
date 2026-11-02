import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(2500); await S(2000); await w.tapSel('.manage-now-btn'); await S(2500); await txt(w,'p1'); await w.shot('17-publish-entry');
  await w.scroll(700); await S(500); await txt(w,'p2'); await w.shot('18-publish-entry-2');
  await w.scroll(900); await S(500); await txt(w,'p3');
}
