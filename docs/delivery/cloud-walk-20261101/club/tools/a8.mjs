import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(3500);
  await w.scroll(900); await S(500); await w.shot('25-eventops-form-bottom'); await txt(w,'bottom');
  await w.tap('创建系列场次'); await S(400); await txt(w,'past-time-400ms'); await S(2000); await w.shot('26-eventops-pasttime');
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onStartTimeChange({ detail: { value: '13:45' } }));  await S(500);
  await ty(w, '沿用票种', '6');
  await txt(w,'filled'); await w.shot('27-eventops-filled');
  await w.tap('创建系列场次'); await S(3000); await txt(w,'created'); await w.shot('28-eventops-created');
  await w.scroll(-3000); await S(500); await w.shot('29-eventops-top');
}
