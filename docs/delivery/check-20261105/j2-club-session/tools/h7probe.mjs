import { run, S } from './lib.mjs';
// H7 probe: in the 定向广播 sheet, which member id sits behind each displayed name, and which ids does a tap on 林一帆 / 王一舟 select?
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.tap('报名参与者', { exact: true }); await S(2000); await w.tap('定向广播'); await S(2500);
  const dump = () => w.eval(`(()=>{const d=getCurrentPages().slice(-1)[0].data; const out={}; for (const k of Object.keys(d)) { if (/broadcast/i.test(k)) out[k]=d[k]; } return JSON.stringify(out).slice(0,3000);})()`);
  console.log('BEFORE', await dump());
  await w.tap('林一帆', { nth: -1 }); await S(600); await w.tap('王一舟', { nth: -1 }); await S(600);
  console.log('AFTER', await dump());
  await w.shot('110b-broadcast-picked-probe');
});
