const test=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const {installAppUpdate}=require('../../utils/app-update.js');
function harness(){let ready,failed;let installs=0,applies=0;const dialogs=[],toasts=[];
 const manager={onUpdateReady(fn){installs++;ready=fn;},onUpdateFailed(fn){failed=fn;},applyUpdate(){applies++;}};
 const wx={getUpdateManager:()=>manager,showModal:opts=>dialogs.push(opts),showToast:opts=>toasts.push(opts)};
 return {wx,manager,dialogs,toasts,ready:()=>ready(),failed:()=>failed(),installs:()=>installs,applies:()=>applies};
}
test('ready update requires an explicit confirm and warns about unsaved content',()=>{
 const h=harness();assert.equal(installAppUpdate(h.wx),true);h.ready();assert.equal(h.applies(),0);assert.match(h.dialogs[0].content,/先保存未保存/);assert.equal(h.dialogs[0].confirmText,'立即重启');h.dialogs[0].success({confirm:true});assert.equal(h.applies(),1);
});
test('cancel, false, missing and inconsistent modal result never restart',()=>{
 for(const result of [{confirm:false,cancel:true},{confirm:false},{},{confirm:true,cancel:true},null]){const h=harness();installAppUpdate(h.wx);h.ready();h.dialogs[0].success(result);assert.equal(h.applies(),0);}
});
test('reinstall or repeated ready callbacks produce one prompt and one manager subscription',()=>{
 const h=harness();installAppUpdate(h.wx);installAppUpdate(h.wx);h.ready();h.ready();assert.equal(h.installs(),1);assert.equal(h.dialogs.length,1);h.dialogs[0].success({confirm:false});h.ready();assert.equal(h.dialogs.length,1);assert.equal(h.applies(),0);
});
test('download failure stays accurate, is not duplicated, and never applies an update',()=>{
 const h=harness();installAppUpdate(h.wx);h.failed();h.failed();assert.equal(h.toasts.length,1);assert.match(h.toasts[0].title,/下载失败/);assert.equal(h.applies(),0);assert.equal(h.dialogs.length,0);
});
test('unsupported API and unavailable manager preserve application startup',()=>{
 for(const wx of [null,{}, {getUpdateManager(){throw Error('unavailable');}}, {getUpdateManager:()=>({})}])assert.equal(installAppUpdate(wx),false);
});
test('modal failure or absent UI never acts as consent',()=>{
 for(const configure of [h=>delete h.wx.showModal,h=>{h.wx.showModal=()=>{throw Error('UI unavailable');};}]){const h=harness();configure(h);installAppUpdate(h.wx);assert.doesNotThrow(()=>h.ready());assert.equal(h.applies(),0);}
});
test('app installs standard update manager once through existing launch entry',()=>{
 const source=fs.readFileSync(path.resolve(__dirname,'../../app.js'),'utf8');assert.match(source,/require\('\.\/utils\/app-update\.js'\)/);assert.match(source,/onLaunch\(e\)\s*\{\s*installAppUpdate\(wx\)/);assert.equal((source.match(/installAppUpdate\(wx\)/g)||[]).length,1);
});

test('duplicate modal completion cannot apply twice or turn an earlier refusal into approval',()=>{
 for(const first of [{confirm:true},{confirm:false,cancel:true},null]){const h=harness();installAppUpdate(h.wx);h.ready();h.dialogs[0].success(first);h.dialogs[0].success({confirm:true});assert.equal(h.applies(),first&&first.confirm===true?1:0);}
});
test('activation exception is reported as activation failure rather than a successful restart or download failure',()=>{
 const h=harness();h.manager.applyUpdate=()=>{throw Error('activation unavailable');};installAppUpdate(h.wx);h.ready();h.dialogs[0].success({confirm:true});assert.equal(h.toasts.length,1);assert.match(h.toasts[0].title,/未能启用/);assert.doesNotMatch(h.toasts[0].title,/下载失败/);
});
