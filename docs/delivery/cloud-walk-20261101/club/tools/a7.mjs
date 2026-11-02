import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(3500); await txt(w,'m'); await w.shot('23-manage-approved');
  await w.tap('开一场', {exact:true}); await S(3000); await w.shot('24-open-session'); await txt(w,'open');
  console.log(JSON.stringify(await w.route()));
}
