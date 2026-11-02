import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(2500); await txt(w,'manage'); await w.shot('15-club-manage');
  await w.scroll(700); await S(500); await txt(w,'manage2'); await w.shot('16-club-manage-2');
  await w.scroll(700); await S(500); await txt(w,'manage3');
}
