import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.shot('95-lead-settlement'); await txt(w,'settle');
  await w.scroll(700); await S(500); await w.shot('96-lead-settlement-2'); await txt(w,'settle2');
}
