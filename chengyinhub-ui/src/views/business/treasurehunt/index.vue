<template>
  <div class="app-container">
    <el-alert type="info" :closable="false" show-icon
      title="寻宝:玩家按线索在几个地点找到隐藏的宝藏,到场才算找到(定位可信)。找齐全部宝藏后发一次「寻宝完成」积分(积分规则 41)。玩家只看到线索和大致范围,看不到精确位置。" />

    <el-form :inline="true" size="small" class="mt8">
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 120px" @change="load">
          <el-option v-for="(text, key) in statusNames" :key="key" :label="text" :value="Number(key)" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button v-hasPermi="['business:treasureHunt:edit']" type="primary" icon="el-icon-plus" @click="openCreate">新建寻宝</el-button>
        <el-button icon="el-icon-refresh" @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="编号" prop="id" width="70" />
      <el-table-column label="寻宝" prop="title" />
      <el-table-column label="来源" width="100">
        <template slot-scope="s">{{ s.row.creatorMemberId ? '玩家 ' + s.row.creatorMemberId : '官方' }}</template>
      </el-table-column>
      <el-table-column label="AI 起草" width="80">
        <template slot-scope="s"><el-tag v-if="s.row.aiAssisted === 1" size="mini" type="info">AI 起草</el-tag></template>
      </el-table-column>
      <el-table-column label="宝藏数" prop="itemCount" width="70" />
      <el-table-column label="参与 / 找齐" width="100">
        <template slot-scope="s">{{ s.row.players }} / {{ s.row.finishers }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template slot-scope="s">{{ statusNames[s.row.status] || '未知' }}</template>
      </el-table-column>
      <el-table-column label="备注" prop="reviewNote" />
      <el-table-column label="操作" width="260">
        <template slot-scope="s">
          <el-button v-hasPermi="['business:treasureHunt:edit']" type="text" @click="openEdit(s.row)">修改</el-button>
          <el-button v-if="[0, 1, 4].includes(s.row.status)" v-hasPermi="['business:treasureHunt:approve']" type="text" @click="decide(s.row, 'approve')">通过并上线</el-button>
          <el-button v-if="[1, 2].includes(s.row.status)" v-hasPermi="['business:treasureHunt:edit']" type="text" @click="decide(s.row, 'reject')">退回</el-button>
          <el-button v-if="s.row.status === 2" v-hasPermi="['business:treasureHunt:edit']" type="text" @click="decide(s.row, 'end')">结束</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="form.id ? '修改寻宝' : '新建寻宝'" :visible.sync="formOpen" width="760px" append-to-body>
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="标题" required><el-input v-model="form.title" maxlength="64" show-word-limit /></el-form-item>
        <el-form-item label="介绍"><el-input v-model="form.intro" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="封面图"><el-input v-model="form.coverImg" placeholder="可选,https 地址或站内图片" /></el-form-item>
        <el-form-item label="城市编码"><el-input v-model="form.cityCode" placeholder="可选,如 330100" style="width: 200px" /></el-form-item>
        <el-form-item label="开放时间">
          <el-date-picker v-model="form.window" type="datetimerange" value-format="yyyy-MM-dd HH:mm:ss"
            range-separator="至" start-placeholder="不限" end-placeholder="不限" />
        </el-form-item>
        <el-form-item label="AI 起草"><el-checkbox v-model="form.aiAssisted">线索由 AI 起草(玩家端会标注)</el-checkbox></el-form-item>
        <el-form-item label="宝藏" required>
          <el-table :data="form.items" size="mini" border>
            <el-table-column label="名称" width="120"><template slot-scope="s"><el-input v-model="s.row.name" maxlength="40" size="mini" /></template></el-table-column>
            <el-table-column label="线索"><template slot-scope="s"><el-input v-model="s.row.clue" maxlength="300" size="mini" /></template></el-table-column>
            <el-table-column label="纬度" width="100"><template slot-scope="s"><el-input v-model="s.row.lat" size="mini" /></template></el-table-column>
            <el-table-column label="经度" width="100"><template slot-scope="s"><el-input v-model="s.row.lng" size="mini" /></template></el-table-column>
            <el-table-column label="半径(米)" width="90"><template slot-scope="s"><el-input-number v-model="s.row.radiusM" :min="20" :max="300" size="mini" controls-position="right" style="width: 80px" /></template></el-table-column>
            <el-table-column width="50"><template slot-scope="s"><el-button type="text" icon="el-icon-delete" @click="form.items.splice(s.$index, 1)" /></template></el-table-column>
          </el-table>
          <el-button size="mini" icon="el-icon-plus" class="mt8" :disabled="form.items.length >= 20" @click="addItem">添加宝藏</el-button>
          <div class="el-form-item__description">2 至 20 件;坐标用 GCJ-02(高德/腾讯地图坐标)。</div>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" @click="save">保存为草稿</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listTreasureHunts, getTreasureHunt, saveTreasureHunt, decideTreasureHunt } from '@/api/business/gameMechanics'

function emptyItem() {
  return { name: '', clue: '', lat: '', lng: '', radiusM: 60 }
}

function emptyForm() {
  return { id: null, title: '', intro: '', coverImg: '', cityCode: '', window: [], aiAssisted: false, items: [emptyItem(), emptyItem()] }
}

export default {
  name: 'TreasureHunt',
  data() {
    return {
      loading: false,
      rows: [],
      status: null,
      statusNames: { 0: '草稿', 1: '待审核', 2: '进行中', 3: '已退回', 4: '已结束' },
      formOpen: false,
      form: emptyForm()
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      listTreasureHunts(this.status === '' ? null : this.status).then(res => { this.rows = res.data || [] }).finally(() => { this.loading = false })
    },
    addItem() {
      this.form.items.push(emptyItem())
    },
    openCreate() {
      this.form = emptyForm()
      this.formOpen = true
    },
    openEdit(row) {
      getTreasureHunt(row.id).then(res => {
        const d = res.data || {}
        this.form = {
          id: d.id,
          title: d.title || '',
          intro: d.intro || '',
          coverImg: d.coverImg || '',
          cityCode: d.cityCode || '',
          window: d.startTime || d.endTime ? [d.startTime, d.endTime] : [],
          aiAssisted: !!d.aiAssisted,
          items: (d.items || []).map(i => ({ id: i.id, name: i.name, clue: i.clue, lat: String(i.lat), lng: String(i.lng), radiusM: i.radiusM }))
        }
        this.formOpen = true
      })
    },
    save() {
      const window = this.form.window || []
      const body = {
        id: this.form.id,
        title: this.form.title,
        intro: this.form.intro,
        coverImg: this.form.coverImg,
        cityCode: this.form.cityCode,
        aiAssisted: this.form.aiAssisted,
        startTime: window[0] || null,
        endTime: window[1] || null,
        items: this.form.items.map(i => ({ id: i.id || undefined, name: i.name, clue: i.clue, lat: Number(i.lat), lng: Number(i.lng), radiusM: i.radiusM }))
      }
      saveTreasureHunt(body).then(() => {
        this.$modal.msgSuccess('已保存')
        this.formOpen = false
        this.load()
      })
    },
    decide(row, decision) {
      if (decision === 'reject') {
        this.$prompt('请写明退回原因,作者会看到这段话。', '退回寻宝', { inputValidator: v => !!(v && v.trim()) || '请填写原因' })
          .then(({ value }) => decideTreasureHunt(row.id, 'reject', value))
          .then(() => this.load())
          .catch(() => {})
        return
      }
      const words = { approve: '通过后玩家可以开始寻宝。', end: '结束后玩家不能再寻找,已找到的记录保留。' }
      this.$modal.confirm(words[decision]).then(() => decideTreasureHunt(row.id, decision)).then(() => this.load()).catch(() => {})
    }
  }
}
</script>
