import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.shot('140-lead-club-badges'); await txt(w,'badges');
  await w.scroll(700); await S(500); await w.shot('141-lead-club-badges-2'); await txt(w,'badges2');
}
