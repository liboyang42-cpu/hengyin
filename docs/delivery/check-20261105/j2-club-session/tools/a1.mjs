import { run, S } from './lib.mjs';
// A1: new player becomes 主理人 (4 steps), then creates the club with a cover (real upload through the OSS/WeChat seams)
await run({ path: 'pages/club/apply/index', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(1500); await w.shot('01-apply-intro');
  await w.tap('成为主理人'); await S(1000); await w.say('s1');
  await w.ty('你的称呼', '林小舟'); await w.ty('手机号或微信号', '13800001111'); await w.ty('如 高校社团负责人 / 户外领队', '城市徒步领队');
  await w.shot('02-apply-step1'); await w.tap('下一步'); await S(800);
  try { await w.tap('5-20场'); } catch (e) { console.log('no exp chip', e.message); }
  await w.ty('如 50', '20'); await w.ty('如 20', '10'); await w.shot('03-apply-step2');
  await w.tap('下一步'); await S(800); await w.tap('能设计城市路线'); await w.tap('能与商家联动'); await w.shot('04-apply-step3');
  await w.tap('下一步'); await S(800); await w.say('step4'); await w.shot('05-apply-step4');
  await w.tap('成为俱乐部主理人'); await S(2500); await w.say('done'); await w.shot('06-apply-done');
  await w.tap('创建我的俱乐部'); await S(1500); await w.say('create-intro'); await w.shot('07-create-intro');
  try { await w.tap('开始创建'); await S(800); } catch (e) { console.log(e.message); }
  await w.tap('旅行组织'); await S(400); await w.shot('08-create-step1'); await w.tap('下一步'); await S(800);
  const d2 = await w.data(); await w.tap(d2.dirList[0].label || d2.dirList[0].val); await S(400); await w.tap('下一步'); await S(800);
  await w.tapSel('.cc-cover'); await S(2500); try { await w.tap('选取'); } catch (e) { console.log(e.message); } await S(4000); await w.say('after-crop');
  try { await w.tap('确定'); } catch (e) {}
  await w.ty('给你的俱乐部起个名字', '沪上慢走社'); await w.ty('用一句话说清你的俱乐部是做什么的', '每月一次的城市慢走');
  await S(1200); await w.shot('09-create-step3'); await w.tap('下一步'); await S(800);
  await w.ty('如 上海', '上海'); await w.shot('10-create-step4');
  await w.tap('创建俱乐部'); await S(3000); await w.say('created'); await w.shot('11-club-created');
});
