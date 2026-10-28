'use strict';
// 下一站 · 条件与效果（10-28 原型 A e-route）。每条规则是一句中文，点一句就地展开改；
// 「添加条件 / 效果」「换一个名字」开一层（layer 事件通知外层换标题、藏其它结果）。
// 数据仍是 { conditions, effects }，键名照存，change 事件与保存路径不变。
const form = require('../../utils/publish/story-rule-form');
const sentence = require('../../utils/publish/story-rule-sentence');
const EMPTY_NAMES = { clue: [], tag: [], item: [], trait: [], attr: [], clock: [], value: [], node: [] };
Component({
  properties: { value:{type:Object,value:null}, baseline:{type:Object,value:null}, nodes:{type:Array,value:[]}, variables:{type:Object,value:{}}, identity:{type:String,value:''},
    names:{type:Object,value:null}, readOnly:{type:Boolean,value:false} },
  data:{conditionRows:[],effectRows:[],topSeg:[],showTopSeg:false,error:'',sorting:false,layer:null,layerRows:[],query:''},
  lifetimes:{
    attached() { this._active=true;this._open='';this._groups=[];this._draft=JSON.parse(JSON.stringify(this.data.value || {conditions:[],effects:[]}));this.refresh(); },
    detached() { this._active=false; }
  },
  methods:{
    _names() { return Object.assign({},EMPTY_NAMES,this.data.names || {}); },
    refresh() {
      const names=this._names(),issues=form.errors(this._draft,this.data.nodes,this.data.variables);
      const mode=sentence.topMode(this._draft),conditionRows=sentence.conditionRows(this._draft,names,this._open,this._groups);
      const count=conditionRows.filter(row=>row.depth===0).length;
      this.setData({conditionRows,effectRows:sentence.effectRows(this._draft,names,this._open),
        showTopSeg:count>1 || mode==='OR',topSeg:[{key:'AND',label:'全部满足',on:mode==='AND'},{key:'OR',label:'任一满足',on:mode==='OR'}],
        // 校验报错由 story-rule-form 给出、可能带内部字段名；界面只说一句人话。
        error:issues.length?'有一句还没选完，请补全或删除':''});
    },
    edit(change) {
      if(!this._active || this.data.readOnly) return;
      try { this._draft=change(this._draft);this.refresh();
        this.triggerEvent('change',{identity:this.data.identity,value:JSON.parse(JSON.stringify(this._draft))});
      } catch(error) { this.setData({error:error.message}); }
    },
    toggleRow(e) { const d=e.currentTarget.dataset,path=d.path;if(this.data.sorting && d.type==='leaf' && /^effects\./.test(path)) return;
      if(d.type==='group') this._groups=this._groups.includes(path)?this._groups.filter(p=>p!==path):this._groups.concat(path);
      else this._open=this._open===path?'':path;
      this.refresh(); },
    toggleSort() { this.setData({sorting:!this.data.sorting}); },
    onTopMode(e) { const mode=e.currentTarget.dataset.key;this.edit(v=>sentence.setTopMode(v,mode)); },
    onGroupMode(e) { const d=e.currentTarget.dataset;this.edit(v=>sentence.setGroupMode(v,d.path,d.key)); },
    onNegate(e) { const d=e.currentTarget.dataset,row=this.data.conditionRows.find(r=>r.path===d.path);if(!row)return;
      this.edit(v=>sentence.setNegated(v,d.path,d.key==='1'));
      if(this._open===d.path) this.refresh(); },
    onPickName(e) { const d=e.currentTarget.dataset;this.edit(v=>sentence.setKey(v,d.path,d.key)); },
    onCompare(e) { const d=e.currentTarget.dataset;this.edit(v=>sentence.setCompare(v,d.path,d.key)); },
    onResult(e) { const d=e.currentTarget.dataset;this.edit(v=>sentence.setResult(v,d.path,d.key)); },
    onVarOp(e) { const d=e.currentTarget.dataset;this.edit(v=>sentence.setVarOp(v,d.path,d.key)); },
    onStep(e) { const d=e.currentTarget.dataset;this.edit(v=>sentence.setAmount(v,d.path,Number(d.amount)+Number(d.step))); },
    onMoveUp(e) { const path=e.currentTarget.dataset.path;this.edit(v=>sentence.move(v,path,-1)); },
    onRemove(e) { const path=e.currentTarget.dataset.path;this._open='';this._groups=[];this.edit(v=>sentence.remove(v,path)); },
    // ---- 下一层：添加 / 换名字 ----
    _layerRows() {
      const layer=this.data.layer;if(!layer) return [];
      const names=this._names(),q=this.data.query.trim();
      const pick=list=>(names[list] || []).filter(row=>!q || row.label.indexOf(q)>=0).map(row=>({key:row.key,label:row.label}));
      if(layer.mode==='rename') return [{kind:'',label:'',open:true,names:pick(layer.list),list:layer.list}];
      const kinds=layer.target==='effects'?sentence.EFFECT_KINDS:sentence.CONDITION_KINDS;
      return kinds.filter(k=>k.kind!=='group' || layer.target==='conditions').map(k=>Object.assign({},k,{open:layer.kind===k.kind,
        hasNames:!!k.names,names:layer.kind===k.kind && k.names?pick(k.names):[]}));
    },
    _setLayer(layer) {
      this.setData({layer,query:''},()=>this.setData({layerRows:this._layerRows()}));
      this.triggerEvent('layer',{identity:this.data.identity,open:!!layer,title:layer?layer.title:''});
    },
    openAdd(e) { if(this.data.readOnly) return; const target=e.currentTarget.dataset.target;
      this._setLayer({mode:'add',target,kind:'',title:target==='effects'?'添加效果':'添加条件'}); },
    openRename(e) { const path=e.currentTarget.dataset.path,list=sentence.namesListFor(this._draft,path,this._names());if(!list) return;
      this._setLayer({mode:'rename',path,list,title:'换一个名字'}); },
    closeLayer() { if(this.data.layer) this._setLayer(null); },
    onLayerKind(e) {
      const kind=e.currentTarget.dataset.kind,layer=Object.assign({},this.data.layer),def=(layer.target==='effects'?sentence.EFFECT_KINDS:sentence.CONDITION_KINDS).find(k=>k.kind===kind);
      if(!def) return;
      if(!def.names) return this._commitAdd(kind,'');
      layer.kind=layer.kind===kind?'':kind;this.setData({layer,query:''},()=>this.setData({layerRows:this._layerRows()}));
    },
    onLayerQuery(e) { this.setData({query:e.detail.value || ''},()=>this.setData({layerRows:this._layerRows()})); },
    onLayerName(e) {
      const key=e.currentTarget.dataset.key,layer=this.data.layer;if(!layer) return;
      if(layer.mode==='rename') { this.edit(v=>sentence.setKey(v,layer.path,key));return this._setLayer(null); }
      this._commitAdd(layer.kind,key);
    },
    _commitAdd(kind,key) {
      const layer=this.data.layer;let added='';
      this.edit(v=>{const result=sentence.add(v,layer.target,kind,key,this._names());added=result.path;return result.value;});
      if(added) { if(kind==='group') this._groups=this._groups.concat(added);else this._open=added;this.refresh(); }
      this._setLayer(null);
    }
  }
});
