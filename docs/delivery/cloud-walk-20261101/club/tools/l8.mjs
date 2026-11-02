import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.shot('61-lead-roster-16'); await txt(w,'roster');
  await w.tap('候补', {exact:false}); await S(1200); await txt(w,'wl'); await w.shot('62-lead-roster-waitlist');
}
