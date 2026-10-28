'use strict';
const contract=require('../../utils/publish/attribute-binding');
Component({
  properties:{rules:{type:String,value:''},snapshot:{type:Object,value:null},topicId:{type:String,value:''},configVersion:{type:null,value:null},readOnly:{type:Boolean,value:false}},
  data:{ready:false,options:[],conflicts:[],error:'',notice:''},
  observers:{'rules,snapshot,topicId,configVersion'(){if(this._active)this.reload();}},
  lifetimes:{attached(){this._active=true;this.reload();},detached(){this._active=false;this._session=null;}},
  methods:{
    reload(){
      const previous=this._session, selected=this._enabled;
      this._session=null;this._accepted=[];
      try {
        if(!this.data.topicId || !this.data.snapshot){this.setData({ready:false,error:'',notice:'完整注册版本尚未读取；现有配置保持不变。'});return;}
        this._session=contract.session(this.data.rules,this.data.snapshot,{topicId:this.data.topicId,configVersion:this.data.configVersion});
        this._enabled=previous && previous.original===this.data.rules && previous.context.topicId===String(this.data.topicId) && previous.context.configVersion===String(this.data.configVersion)
          ? selected.slice() : this._session.enabledKeys.slice();this.render();
      }catch(error){this.setData({ready:false,error:error.message,notice:''});}
    },
    render(){try{const s=this._session;this.setData({ready:true,error:'',notice:'只启用你勾选的属性。确认后定义以注册表为准，不改变已开始的局。',options:s.registry.definitions.map(d=>({key:d.stateKey,label:d.label,checked:this._enabled.includes(d.stateKey)})),conflicts:contract.conflicts(s,this._enabled).map(c=>Object.assign({},c,{accepted:this._accepted.includes(c.key)}))});}catch(error){this.setData({ready:false,error:error.message});}},
    select(e){if(!this._active || !this._session || this.data.readOnly)return;this._enabled=e.detail.value.slice();this._accepted=[];this.render();},
    accept(e){if(!this._active || !this._session || this.data.readOnly)return;this._accepted=e.detail.value.slice();this.render();},
    confirm(){if(!this._active || !this._session || this.data.readOnly)return;try{const result=contract.confirm(this._session,this._enabled,this._accepted);this.triggerEvent('confirm',result);}catch(error){this.setData({error:error.message});}},
    manage(){if(this._active)this.triggerEvent('manage');},
    refresh(){if(this._active)this.triggerEvent('refresh');}
  }
});
