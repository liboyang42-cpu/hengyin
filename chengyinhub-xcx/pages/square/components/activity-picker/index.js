// 选择「我已通关的活动」底部弹层(广场发布关联运动feed用)
const app = getApp();
const toast = require('../../../../utils/toast.js');
const ownerId = () => String(typeof app.getUserID === 'function' ? app.getUserID() || '' : '');
const { isRecordList } = require('../../../../utils/response-shape.js');

Component({
  properties: {
    // 受控显示:由父页 setData 控制
    show: {
      type: Boolean,
      value: false,
      observer(v) {
        if (!v) { this.cancelPreview(); return; }
        if (this._owner !== ownerId()) { this.cancelPreview();this._owner=ownerId();this.setData({loaded:false,list:[]}); }
        if (!this.data.loaded) this.fetchList();
      }
    }
  },

  data: {
    list: [],
    loading: false,
    loaded: false,
    errorMsg: '',
    // 生成路线预览图中
    generating: false,
    previewTopicId: ''
  },

  lifetimes: {
    detached() {
      this._detached = true;
      clearTimeout(this._timer);
      if (this._fetchTask && typeof this._fetchTask.abort === 'function') this._fetchTask.abort();
      if (this._uploadControl) this._uploadControl.destroy();
      this._generation = (this._generation || 0) + 1;
      this._pending = null;
      this._previewTmp = null;
    }
  },

  methods: {
    previewCurrent(generation, pending, owner) {
      return !this._detached && this._generation === generation && this._pending === pending && ownerId() === owner;
    },
    cancelPreview() {
      clearTimeout(this._timer);this._timer=null;
      if(this._uploadControl)this._uploadControl.destroy();this._uploadControl=null;
      this._generation=(this._generation || 0)+1;this._pending=null;this._previewTmp=null;
      this.setData({generating:false,previewTopicId:''});
    },
    previewFailed(message) {
      clearTimeout(this._timer);this._timer=null;this._uploadControl=null;
      this.setData({generating:false,previewTopicId:''});
      toast(message || '路线预览上传失败，请重选该活动重试，选择仍保留');
    },
    // 拉「我已通关活动」列表
    fetchList() {
      if (this.data.loading) return;
      const that = this, owner=ownerId();
      this._owner=owner;
      const current=()=>!that._detached && ownerId()===owner;
      that.setData({ loading: true, errorMsg: '' });
      this._fetchTask = app.sendRequest({
        url: '/api/play/my-completed',
        method: 'POST',
        hideLoading: true,
        data: {},
        success(res) {
          if(!current())return;
          if (res && res.code == '200' && isRecordList(res.data)) {
            that.setData({ list: res.data, loaded: true, errorMsg: '' });
          } else {
            that.setData({ list: [], loaded: false, errorMsg: '活动列表加载失败，请重试' });
          }
        },
        fail() {
          if(!current())return;
          that.setData({ list: [], loaded: false, errorMsg: '活动列表加载失败，请重试' });
        },
        complete() {
          if(!current())return;
          that._fetchTask = null;
          that.setData({ loading: false });
        }
      });
    },

    onSelect(e) {
      const idx = e.currentTarget.dataset.index;
      const activity = this.data.list[idx];
      if (!activity) return;
      // 无主题→无路线,直接选中
      if (!activity.topicId) {
        this.emitSelect(activity, '');
        return;
      }
      if(this._pending===activity && this._previewTmp){this.setData({generating:true});this.uploadPreview(this._previewTmp);return;}
      this.cancelPreview();
      this._owner=ownerId();
      // 渲染隐藏 route-map 生成静态路线预览图
      this._generation = (this._generation || 0) + 1;
      this._pending = activity;
      this.setData({ generating: true, previewTopicId: activity.topicId });
      const generation=this._generation,pending=this._pending,owner=this._owner;
      this._timer = setTimeout(() => {if(this.previewCurrent(generation,pending,owner))this.previewFailed('路线预览生成超时，请重选该活动重试');}, 4000);
    },

    // route-map 数据就位→截图→上传OSS
    onRouteReady() {
      const that = this;
      const generation = this._generation;
      const pending = this._pending, owner=this._owner === undefined ? ownerId() : this._owner;
      const rm = this.selectComponent('#rmPreview');
      if (!rm || !rm.snapshotRoute) {
        this.previewFailed('路线预览尚未就绪，请重选该活动重试');
        return;
      }
      if (this._snapshotGeneration != null && this._snapshotGeneration === generation) return;
      this._snapshotGeneration = generation;
      rm.snapshotRoute().then(function (tmp) {
        if (!pending || !that.previewCurrent(generation,pending,owner)) return;
        that.uploadPreview(tmp);
      }).catch(function () {
        if (!pending || !that.previewCurrent(generation,pending,owner)) return;
        that.previewFailed('路线预览生成失败，请重选该活动重试');
      });
    },

    onRouteEmpty() {
      this.finish('');
    },

    uploadPreview(tmpPath) {
      const that=this, pending=this._pending,generation=this._generation,owner=this._owner === undefined ? ownerId() : this._owner;
      if(!pending || !this.previewCurrent(generation,pending,owner))return;
      clearTimeout(this._timer);this._timer=null;this._previewTmp=tmpPath;
      this._uploadControl=app.getUploadClient().uploadAll([tmpPath],{
        bizType:'COMMUNITY_POST',concurrency:1,perFileTimeoutMs:8000,totalDeadlineMs:10000,
        mapResult(data) {
          if(!that.previewCurrent(generation,pending,owner))throw new Error('选择已变化');
          if(!data || typeof data.url!=='string' || !data.url || typeof data.uploadReceipt!=='string' || !data.uploadReceipt
            || !Number.isSafeInteger(data.byteSize) || data.byteSize<=0 || typeof data.mimeType!=='string' || !/^image\//.test(data.mimeType))
            throw new Error('路线预览上传未完成，请重新选择活动');
          return {...data};
        },
        onDone(result) {
          if(!that.previewCurrent(generation,pending,owner))return;
          that._uploadControl=null;
          const receipt=result && result.ok && result.results && result.results[0];
          if(!receipt || !receipt.url || !receipt.uploadReceipt){that.previewFailed();return;}
          that.finish(receipt.url,receipt);
        }
      });
    },

    // 收尾:带(或不带)预览图选中,清状态。_pending 守卫防超时与成功回调双触发
    finish(routePreviewImg, routePreviewReceipt) {
      if (this._timer) {
        clearTimeout(this._timer);
        this._timer = null;
      }
      const activity = this._pending;
      this._pending = null;this._previewTmp=null;
      if (!activity || this._detached || (this._owner !== undefined && ownerId()!==this._owner)) return;
      this.setData({ generating: false, previewTopicId: '' });
      this.emitSelect(activity, routePreviewImg || '', routePreviewReceipt);
    },

    emitSelect(activity, routePreviewImg, routePreviewReceipt) {
      this.triggerEvent('select', { activity, routePreviewImg: routePreviewImg || '', routePreviewReceipt });
    },

    onClose() {
      this.cancelPreview();
      this.triggerEvent('close');
    },

    noop() {}
  }
});
