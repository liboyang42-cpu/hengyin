<template>
  <div class="app-container">
    <cy-page-header eyebrow="俱乐部与漫游" title="频道活动" description="俱乐部主理人在频道里发出的活动。参加人数按已确认的报名计算；照片先走媒体审核，这里可以从相册移除或恢复。" />

    <el-form size="small" :inline="true">
      <el-form-item label="俱乐部编号"><el-input v-model="query.clubId" clearable placeholder="全部" style="width: 140px" /></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="load(true)">筛选</el-button></el-form-item>
    </el-form>

    <cy-resource-state v-if="error" kind="error" title="频道活动暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="load(true)" />
    <cy-empty-state v-else-if="!loading && !events.length" title="还没有频道活动" />
    <el-table v-else v-loading="loading" :data="events" size="small" border>
      <el-table-column label="活动" width="80" prop="id" />
      <el-table-column label="名称" min-width="160" prop="title" show-overflow-tooltip />
      <el-table-column label="俱乐部 / 频道" width="140">
        <template slot-scope="s">#{{ s.row.clubId }} / #{{ s.row.channelId }}</template>
      </el-table-column>
      <el-table-column label="时间" width="170" prop="startAt" />
      <el-table-column label="地点" min-width="120" prop="placeName" show-overflow-tooltip />
      <el-table-column label="人数" width="130" align="center">
        <template slot-scope="s">参加 {{ s.row.goingCount }} / {{ s.row.capacity }}<br>可能去 {{ s.row.maybeCount }}</template>
      </el-table-column>
      <el-table-column label="收费" width="70" align="center">
        <template slot-scope="s">{{ s.row.paid ? '收费' : '免费' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="s">{{ stateText[s.row.state] || s.row.state }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="center">
        <template slot-scope="s">
          <el-button type="text" size="mini" @click="openAlbum(s.row)">相册</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="events.length >= 50" style="margin-top: 12px; text-align: center">
      <el-button size="small" :loading="loading" @click="load(false)">更早的活动</el-button>
    </div>

    <el-dialog :title="album.title ? '相册：' + album.title : '相册'" :visible.sync="albumOpen" width="760px">
      <cy-empty-state v-if="!albumLoading && !photos.length" title="相册里还没有照片" />
      <el-table v-else v-loading="albumLoading" :data="photos" size="small" border>
        <el-table-column label="照片" width="120">
          <template slot-scope="s">
            <el-image v-if="s.row.previewUrl" :src="s.row.previewUrl" :preview-src-list="[s.row.previewUrl]" style="width: 96px; height: 72px" fit="cover" />
            <span v-else>无法预览</span>
          </template>
        </el-table-column>
        <el-table-column label="上传人" width="100">
          <template slot-scope="s">玩家 #{{ s.row.memberId }}</template>
        </el-table-column>
        <el-table-column label="素材" width="90" prop="assetId" />
        <el-table-column label="上传时间" prop="createTime" min-width="150" />
        <el-table-column label="相册状态" width="90" align="center">
          <template slot-scope="s">{{ s.row.removed ? '已移除' : '在相册中' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template slot-scope="s">
            <el-button v-if="!s.row.removed" v-hasPermi="['business:channelEvent:moderate']" type="text" size="mini" :loading="saving" @click="remove(s.row)">移除</el-button>
            <el-button v-else v-hasPermi="['business:channelEvent:moderate']" type="text" size="mini" :loading="saving" @click="restore(s.row)">恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div slot="footer" class="dialog-footer">
        <span style="float: left; color: #909399; font-size: 12px">照片内容由媒体审核把关；未通过审核的照片玩家看不到。</span>
        <el-button size="small" @click="albumOpen = false">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listChannelEvents, listChannelEventPhotos, removeChannelEventPhoto, restoreChannelEventPhoto } from '@/api/business/channelEvent'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'ChannelEvent',
  components: { CyEmptyState, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      error: false,
      saving: false,
      events: [],
      query: { clubId: '' },
      stateText: { UPCOMING: '即将开始', LIVE: '进行中', ENDED: '已结束', IN_REVIEW: '审核中', CANCELLED: '已取消' },
      albumOpen: false,
      albumLoading: false,
      album: {},
      photos: []
    }
  },
  created() {
    this.load(true)
  },
  methods: {
    load(reset) {
      const params = {}
      if (this.query.clubId) params.clubId = this.query.clubId
      if (!reset && this.events.length) params.beforeId = this.events[this.events.length - 1].id
      this.loading = true
      this.error = false
      listChannelEvents(params).then(r => {
        const rows = r.data || []
        this.events = reset ? rows : this.events.concat(rows)
      }).catch(() => {
        this.error = true
      }).finally(() => {
        this.loading = false
      })
    },
    openAlbum(row) {
      this.album = row
      this.albumOpen = true
      this.loadAlbum()
    },
    loadAlbum() {
      this.albumLoading = true
      listChannelEventPhotos(this.album.id).then(r => {
        this.photos = r.data || []
      }).finally(() => {
        this.albumLoading = false
      })
    },
    remove(row) {
      this.$modal.confirm('从相册移除这张照片？玩家将看不到它。').then(() => {
        this.saving = true
        return removeChannelEventPhoto(row.id)
      }).then(() => {
        this.$modal.msgSuccess('已移除')
        this.loadAlbum()
      }).catch(() => {}).finally(() => {
        this.saving = false
      })
    },
    restore(row) {
      this.saving = true
      restoreChannelEventPhoto(row.id).then(() => {
        this.$modal.msgSuccess('已恢复')
        this.loadAlbum()
      }).catch(() => {}).finally(() => {
        this.saving = false
      })
    }
  }
}
</script>
