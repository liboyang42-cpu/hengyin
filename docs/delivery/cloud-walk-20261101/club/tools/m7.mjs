import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.shot('84-member-messages'); await txt(w,'msgs'); try { await w.tap('城瘾小助手'); await S(3000); await w.shot('85-member-assistant'); await txt(w,'assistant'); } catch(e) { console.log(e.message); }
}
