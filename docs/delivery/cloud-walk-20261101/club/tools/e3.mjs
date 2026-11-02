import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.shot('142-lead-enroll-ledger'); await txt(w,'ledger');
  await w.scroll(700); await S(500); await w.shot('143-lead-enroll-ledger-2'); await txt(w,'ledger2');
}
