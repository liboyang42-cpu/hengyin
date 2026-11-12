// 把同一时刻的 原型(示意色) | 原型(真实 --lk-* 色) | 真实 play 页(rig) 并排,一行四个时刻,输出一张 PNG。
// 用法: node sheet.cjs <shotsDir> <moment> <look> <t1,t2,..> <out.png> [--two]   (--two 只并排 protoreal | rig)
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');
const [, , dir, m, look, times, out] = process.argv;
const two = process.argv.includes('--two');
const cols = two ? ['protoreal', 'rig'] : ['proto', 'protoreal', 'rig'];
const names = { proto: '原型(示意色)', protoreal: '原型(真实色值)', rig: '真实 play 页' };
const ts = times.split(',');
(async () => {
  const cells = [];
  for (const t of ts) for (const c of cols) {
    const f = path.resolve(dir, `${c}-m${m}-${look}-${t}.png`);
    const src = fs.existsSync(f) ? 'data:image/png;base64,' + fs.readFileSync(f).toString('base64') : '';
    cells.push(`<figure><figcaption>m${m} ${look} ${t}ms · ${names[c]}</figcaption><img src="${src}"></figure>`);
  }
  const W = ts.length * cols.length * 398 + 10;
  const html = `<body style="margin:0;background:#18181c;font:12px sans-serif;color:#ddd"><div style="display:flex;gap:8px;padding:5px;width:${W}px">${cells.join('')}</div><style>figure{margin:0}figcaption{height:28px;line-height:28px}img{width:390px;height:844px;display:block}</style></body>`;
  const b = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
  const p = await b.newPage();
  await p.setViewport({ width: W, height: 890 });
  await p.setContent(html);
  await p.screenshot(out.endsWith('.jpg') ? { path: out, type: 'jpeg', quality: 82 } : { path: out });
  await b.close();
  console.log(out);
})();
