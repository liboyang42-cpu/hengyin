import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(3500); await txt(w,'m'); await w.shot('21-manage-pending');
  await w.scroll(600); await S(600); await txt(w,'m2'); await w.shot('22-manage-pending-2');
}
