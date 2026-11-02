import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(1500); await w.tap('开始申请'); await S(1200);
  await ty(w, '你的称呼', '林小舟'); await ty(w, '手机号或微信号', '13800001111'); await ty(w, '如 高校社团负责人 / 户外领队', '城市漫步社群志愿领队');
  await w.shot('03-apply-step1'); await w.tap('下一步', { exact: true }); await S(900);
  const t2 = await txt(w, 's2'); await w.shot('04-apply-step2');
  await w.tap('5-20场', { exact: true }); await S(400);
  await ty(w, '如 50', '25'); await ty(w, '如 20', '18'); await w.shot('04b-apply-step2-filled');
  await w.tap('下一步', { exact: true }); await S(1200); await txt(w,'s3');
  await w.tap('能设计城市路线'); await w.tap('能设计任务 · 解谜'); await w.tap('能与商家联动');
  await w.shot('05-apply-step3'); await w.tap('下一步', { exact: true }); await S(900);
  await txt(w, 's4'); await w.shot('06-apply-step4');
  await w.tap('成为俱乐部主理人', { exact: true }); await S(2500);
  await txt(w, 'done'); await w.shot('07-apply-done');
}
