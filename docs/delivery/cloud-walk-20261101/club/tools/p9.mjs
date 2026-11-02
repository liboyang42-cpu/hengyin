import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); await w.shot('134-member-header-during'); await txt(w,'hdr');
}
