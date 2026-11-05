// Same method as chengyinhub-xcx/scripts/preflight-release.js section 4 (packOptions.ignore + subpackage roots).
const fs=require('fs'),path=require('path');const ROOT=process.argv[2];
const appJson=JSON.parse(fs.readFileSync(path.join(ROOT,'app.json'),'utf8'));
const cfg=JSON.parse(fs.readFileSync(path.join(ROOT,'project.config.json'),'utf8'));
const ig=(cfg.packOptions&&cfg.packOptions.ignore)||[];
const igDirs=new Set(ig.filter(i=>i.type==='folder').map(i=>i.value.replace(/\/$/,'')));
const igFiles=new Set(ig.filter(i=>i.type==='file').map(i=>i.value));
const subRoots=(appJson.subPackages||appJson.subpackages||[]).map(x=>x.root.replace(/\/$/,''));
const sum=(dir,rel)=>{let t=0;for(const e of fs.readdirSync(dir,{withFileTypes:true})){const r=rel?`${rel}/${e.name}`:e.name;
 if(e.isDirectory()){const top=r.split('/')[0];if(top.startsWith('.')||igDirs.has(top))continue;if(subRoots.some(s=>r===s||r.startsWith(`${s}/`)))continue;t+=sum(path.join(dir,e.name),r);}
 else if(!igFiles.has(r))t+=fs.statSync(path.join(dir,e.name)).size;}return t;};
const b=sum(ROOT,'');const pages=appJson.pages.length+(appJson.subPackages||[]).reduce((n,s)=>n+s.pages.length,0);
console.log(JSON.stringify({root:ROOT,bytes:b,MiB:(b/1048576).toFixed(3),estKiB_x0633:Math.round(b*0.633/1024),ceilingBytes:3*1048576,headroom:3*1048576-b,pages,mainPages:appJson.pages.length,subpackages:(appJson.subPackages||[]).length}));
