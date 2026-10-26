'use strict';
const form = require('../../utils/publish/story-rule-form');
Component({
  properties: { value:{type:Object,value:null}, baseline:{type:Object,value:null}, nodes:{type:Array,value:[]}, variables:{type:Object,value:{}}, identity:{type:String,value:''} },
  data:{expanded:false,rows:[],error:'',summary:''},
  lifetimes:{
    attached() { this._active=true;this._draft=JSON.parse(JSON.stringify(this.data.value || {conditions:[],effects:[]}));this.refresh(); },
    detached() { this._active=false; }
  },
  methods:{
    refresh() {
      const issues=form.errors(this._draft,this.data.nodes,this.data.variables);
      this.setData({rows:form.rows(this._draft,this.data.nodes,this.data.variables),error:issues.length?issues[0].message:'',
        summary:(this._draft.conditions || []).length+' 条条件 · '+(this._draft.effects || []).length+' 条效果'});
    },
    reset() {
      if (!this._active) return;
      this._draft=JSON.parse(JSON.stringify(this.data.baseline || {conditions:[],effects:[]}));
      this.refresh();this.triggerEvent('change',{identity:this.data.identity,value:this._draft,discard:true});
    },
    toggle() { this.setData({expanded:!this.data.expanded}); },
    edit(action) {
      if(!this._active) return;
      try { this._draft=form.change(this._draft,action,this.data.nodes,this.data.variables);this.refresh();
        this.triggerEvent('change',{identity:this.data.identity,value:JSON.parse(JSON.stringify(this._draft))});
      } catch(error) { this.setData({error:error.message}); }
    },
    add(e) { this.edit({type:'addRoot',kind:e.currentTarget.dataset.kind}); },
    child(e) { this.edit({type:'addChild',path:e.currentTarget.dataset.path}); },
    remove(e) { this.edit({type:'remove',path:e.currentTarget.dataset.path}); },
    op(e) { const d=e.currentTarget.dataset,row=this.data.rows.find(r=>r.path===d.path),option=row && row.types[Number(e.detail.value)];if(option)this.edit({type:'op',path:d.path,value:option.value}); },
    input(e) { const d=e.currentTarget.dataset;this.edit({type:'field',path:d.path,key:d.key,value:e.detail.value}); },
    pick(e) { const d=e.currentTarget.dataset,row=this.data.rows.find(r=>r.path===d.path),field=row && row.fields.find(f=>f.key===d.key),option=field && field.options[Number(e.detail.value)];if(option)this.edit({type:'field',path:d.path,key:d.key,value:option.value}); }
  }
});
