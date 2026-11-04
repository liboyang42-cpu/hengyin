import { run, S } from './lib.mjs';
await run({ path: 'pages/play/index?topicId=990015', identity: 'e2e-openid-9004', agree: true, lat: 31.2380, lng: 121.4900 }, async (w) => {
  await S(5000);
  await w.tap('开始游戏'); await S(2500); await w.shot('22-after-start'); await w.say('s1');
  await S(3000); await w.shot('23-after-start-2'); await w.say('s2');
  await S(4000); await w.shot('24-after-start-3'); await w.say('s3');
});
