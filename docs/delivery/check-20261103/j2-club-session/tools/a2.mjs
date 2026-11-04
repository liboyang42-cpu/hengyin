import { run, S } from './lib.mjs';
// A2: from the done page tap 创建我的俱乐部, walk the 4 steps, upload a cover, create
await run({ path: 'pages/club/apply/index', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(1500); await w.say('apply-revisit'); await w.shot('08-apply-revisit');
  await w.tap('创建我的俱乐部'); await S(1500); await w.say('create-intro'); await w.shot('09-create-intro');
  try { await w.tap('开始创建'); await S(800); } catch (e) { console.log(e.message); }
  await w.say('c1'); await w.shot('10-create-step1');
  const d = await w.data(); console.log('TYPES', JSON.stringify(d.typeOptions));
  await w.tap('旅行组织'); await S(400); await w.tap('下一步'); await S(800);
  await w.say('c2'); await w.shot('11-create-step2');
  const d2 = await w.data(); console.log('DIRS', JSON.stringify(d2.dirList)); await w.tap(d2.dirList[0].label || d2.dirList[0].val); await S(400); await w.tap('下一步'); await S(800);
  await w.say('c3');
  await w.tapSel('.cc-cover'); await S(2500); await w.say('cover'); await w.shot('12-create-cover'); await w.tap('选取'); await S(4000); await w.say('after-crop'); await w.shot('12b-create-cover-upload-harness'); await w.tap('确定');
  await w.ty('给你的俱乐部起个名字', '沪上慢走社'); await w.ty('用一句话说清你的俱乐部是做什么的', '每月一次的城市慢走');
  await S(1200); await w.shot('13-create-step3'); await w.tap('下一步'); await S(800);
  await w.ty('如 上海', '上海'); await w.shot('14-create-step4');
  await w.tap('创建俱乐部'); await S(3000); await w.say('created'); await w.shot('15-club-created');
});
