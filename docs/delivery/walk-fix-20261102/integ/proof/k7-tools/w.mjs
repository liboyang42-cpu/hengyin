// node w.mjs <identity> <topic> <prefix> [extra steps json array] ; env OPEN, PRE (json array to put before open), LAT,LNG
import { spawnSync } from 'node:child_process';
const [id, topic, pre, extra] = process.argv.slice(2);
const lat = process.env.LAT || '31.2380', lng = process.env.LNG || '121.4900';
const steps = ["wait:3000","tapx:开始游戏","wait:9000","tapx:出发","wait:3000","tapn:开启定位|1","wait:1500","tapx:继续探索","wait:6000","tapx:继续探索","wait:3000","tapn:开启定位|1","wait:2000","loc:"+lat+","+lng,"wait:2500","say:pre","tapx:开始任务","wait:3000","tapn:开启定位|1","wait:3000","loc:"+lat+","+lng,"wait:3000","say:bag0","tapx:开始任务","wait:4000","say:bag","shot:"+pre+"-bag", ...JSON.parse(process.env.PRE || '[]'),
  ...(process.env.OPEN === 'none' ? [] : ["tapx:"+(process.env.OPEN||'打开任务'),"wait:6000"]), "say:game","shot:"+pre+"-game",
  "eval:(window.__L||[]).join('\\n')",
  "eval:(function(){var p=getCurrentPages().slice(-1)[0];return [p.data.journeyCheck.show,p.data.advancedPlay.show,(p.data.chapterParas||[]).map(function(x){return x.kind+':'+x.in}).join(',')]})()",
  ...JSON.parse(extra || '[]')];
const r = spawnSync('timeout', ['400','node', process.env.HOME + '/j3t/drive.mjs', JSON.stringify({ identity: id, path: 'pages/play/index?topicId=' + topic, lat: +lat, lng: +lng }), JSON.stringify(steps)], { encoding: 'utf8' });
const re = new RegExp(process.env.GREP || 'TEXT|MISS|DATA|EVAL|PICKVIEW|NET.*/api/play/(adv|arr|theme/game|enc|answer)|ERRORS|STEP');
for (const l of (r.stdout + r.stderr).split('\n')) if (re.test(l)) console.log(l.slice(0, 5000));
