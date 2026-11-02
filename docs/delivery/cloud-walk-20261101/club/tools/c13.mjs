import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.shot('131-lead-roster-18'); await txt(w,'roster');
}
