// 可复用路线地图组件:给 topicId 自取主题章节,渲染节点 marker + 橙色 polyline。
// 与主题详情复用同一分章投影；只有明确验证的供应商几何可画为道路。
const toast = require('../../../../utils/toast.js');
const routePreview = require('../../../../utils/topic-route-preview.js');
const app = getApp();

Component({
  lifetimes: {
    detached() { this._routeDetached = true; this._routeReadEpoch = (this._routeReadEpoch || 0) + 1; }
  },
  properties: {
    topicId: {
      type: null,
      value: '',
      observer(v) {
        if (v && Number(v) > 0) this.loadRoute(v);
      }
    },
    // 地图高度(如 '400rpx' / '300px')
    height: {
      type: String,
      value: '400rpx'
    }
  },

  data: {
    routeReady: false,
    routeIsRoad: false,
    routeSourceText: '',
    mapLatitude: 0,
    mapLongitude: 0,
    routeMarkers: [],
    routePolyline: [],
    routeIncludePoints: []
  },

  methods: {
    loadRoute(topicId) {
      if (this._routeDetached) return;
      const that = this;
      const epoch = this._routeReadEpoch = (this._routeReadEpoch || 0) + 1;
      const session = app.getSession(), revision = session.getRevision();
      const current = () => !that._routeDetached && that._routeReadEpoch === epoch
        && app.getSession() === session && session.getRevision() === revision;
      that.setData(routePreview.build([]));
      app.sendRequest({
        hideLoading: true,
        url: '/api/topic/info-to-user',
        method: 'POST',
        data: { id: topicId },
        success(res) {
          if (!current()) return;
          if (res.code == '200' && res.data && String(res.data.id) === String(topicId) && res.data.chaptersList) {
            that.buildRouteMap(res.data.chaptersList);
          } else {
            that.setData(routePreview.build([]));
            that.triggerEvent('empty');
          }
        },
        fail() {
          if (!current()) return;
          that.setData(routePreview.build([]));
          that.triggerEvent('empty');
        }
      });
    },

    // 拍平章节节点 → marker + polyline(贴路线优先,否则节点直连)
    buildRouteMap(chaptersList) {
      const view = routePreview.build(chaptersList);
      this.setData(view, () => this.triggerEvent(view.routeReady ? 'ready' : 'empty'));

    },

    // 截取当前路线地图为临时图片(发布静态预览图用),返回 Promise<tempImagePath>
    snapshotRoute() {
      const that = this;
      return new Promise(function (resolve, reject) {
        if (!that.data.routeReady) {
          reject('route not ready');
          return;
        }
        const ctx = wx.createMapContext('rmMap', that);
        ctx.takeSnapshot({
          success: function (res) { resolve(res.tempImagePath); },
          fail: function (e) { reject(e); }
        });
      });
    },

    onNodeTap(e) {
      const markerId = (e.detail && e.detail.markerId !== undefined) ? e.detail.markerId : e.markerId;
      const m = (this.data.routeMarkers || [])[markerId];
      if (m && m.callout && m.callout.content) {
        toast(m.callout.content);
      }
    }
  }
});
