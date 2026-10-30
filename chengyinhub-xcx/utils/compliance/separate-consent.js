'use strict';
const modal = require('../modal.js');
const FEATURES = {
  photo: {docType:'photo_model_processing',scene:'object_photo',title:'照片模型处理',
    content:'照片将发送给第三方模型服务商（DeepSeek；抠图时为阿里云），用于拍照判定、识别物品、抠图或生成立体藏品。是否单独同意本局游玩中的照片处理？拒绝不会影响其他功能。'},
  voice: {docType:'voice_clone_processing',scene:'merchant_voice',title:'声音克隆处理',
    content:'这段录音将发送给阿里云语音模型（通义 CosyVoice），用于生成可合成语音的专属音色。如录音不是你本人的声音，你必须已告知声音本人并取得其单独同意；生成的语音会在游玩中播放给玩家。是否确认并单独同意？拒绝不会影响其他功能。'},
};
function requestSeparateConsent(app, wx, feature, isCurrent) {
  const spec=FEATURES[feature];
  if(!spec) return Promise.reject(new Error('不支持的单独同意类型'));
  const owner=[app.getUserID(),(app.globalData||{}).authorization||''].join('|');
  const current=()=>owner===[app.getUserID(),(app.globalData||{}).authorization||''].join('|') && (!isCurrent || isCurrent());
  if(!(Number(app.getUserID())>0) || !current()) return Promise.reject(new Error('请先登录当前账号'));
  return new Promise((resolve,reject)=>{
    let settled=false;
    const finish=(error)=>{if(settled)return;settled=true;clearTimeout(timer);if(error)reject(error);else resolve();};
    const timer=setTimeout(()=>finish(new Error('单独同意未完成，请重试')),30000);
    try {modal.show({title:spec.title,content:spec.content,confirmText:'单独同意',cancelText:'拒绝',
      success:result=>{
        if(settled)return;
        if(!current()) {finish(new Error('账号或页面已变化，请重新确认'));return;}
        if(!result.confirm) {finish(new Error('已拒绝，仅停止此功能'));return;}
        Promise.resolve().then(()=>app.recordConsent({docType:spec.docType,scene:spec.scene,eventType:'AGREE'}))
          .then(()=>current()?finish():finish(new Error('账号或页面已变化，请重新确认')))
          .catch(()=>finish(new Error('单独同意记录失败，请重试')));
      },fail:()=>finish(new Error('单独同意未完成，请重试'))});
    }catch(error){finish(new Error('单独同意未完成，请重试'));}
  });
}
module.exports={requestSeparateConsent};
