// Same file selection as scripts/mainpkg-size-report.js (main-package dirs + root files, packOptions.ignore folders out,
// subpackage roots out), then deflate each file (zlib level 9) — an estimate of the zipped main package, not WeChat's build.
const fs=require('fs'),path=require('path'),zlib=require('zlib');
const ROOT=process.argv[2];
const app=JSON.parse(fs.readFileSync(path.join(ROOT,'app.json'),'utf8'));
const cfg=JSON.parse(fs.readFileSync(path.join(ROOT,'project.config.json'),'utf8'));
const ign=new Set(((cfg.packOptions||{}).ignore||[]).filter(e=>e.type==='folder').map(e=>e.value.replace(/^\.\//,'')));
ign.add('node_modules');ign.add('miniprogram_npm');
const ignFiles=new Set(((cfg.packOptions||{}).ignore||[]).filter(e=>e.type==='file').map(e=>e.value.replace(/^\.\//,'')));
const subs=(app.subPackages||app.subpackages||[]).map(s=>s.root.replace(/\/$/,''));
const inSub=r=>subs.some(s=>r===s||r.startsWith(s+'/'));
let raw=0,comp=0,n=0,rawDirs=0;
function walk(abs,rel,top){for(const e of fs.readdirSync(abs,{withFileTypes:true})){const r=rel?rel+'/'+e.name:e.name;
 if(e.name.startsWith('.')||ign.has(r)||ign.has(e.name)||ignFiles.has(r))continue; if(inSub(r))continue;
 const f=path.join(abs,e.name); if(e.isDirectory()){walk(f,r,false);continue;}
 const b=fs.readFileSync(f); raw+=b.length; if(!top) rawDirs+=b.length; comp+=zlib.deflateRawSync(b,{level:9}).length; n++;}}
walk(ROOT,'',true);
const mb=x=>(x/1024/1024).toFixed(2)+'MB';
console.log(JSON.stringify({files:n,uncompressedDirsOnly:mb(rawDirs),uncompressedWithRootFiles:mb(raw),deflateEstimate:mb(comp)}));
