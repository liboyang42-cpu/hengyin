import { run, S } from './lib.mjs';
// G10: merchant readiness: capacity typed; date/time wheels fed through the page's own change handlers (wheels can't scroll in the rig)
await run({ path: 'pages/merchant/game-node/index?activityId=9', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(4000); await w.ty('每个时段最多接待人数', '6');
  await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; p.onServiceDateChange({detail:{value:'2026-11-04'}}); p.onServiceTimeConfirm({detail:{value:['14:00','18:00']}});})()`); await S(500);
  await w.ty('给主办方留一句现场说明', '外摆区南侧，报“喷泉”领一杯手冲'); await w.shot('110-merchant-station-ready-form');
  await w.tap('确认准备完成'); await S(3000); await w.say('ready'); await w.shot('111-merchant-station-ready');
});
