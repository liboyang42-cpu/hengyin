<template>
  <div class="app-container">
    <el-tabs v-model="tab" @tab-click="load">
      <el-tab-pane label="敏感词" name="words">
        <el-form :inline="true" size="small">
          <el-form-item label="词条">
            <el-input v-model="wordQuery.word" clearable style="width: 180px" @keyup.enter.native="loadWords" />
          </el-form-item>
          <el-form-item label="分类">
            <el-select v-model="wordQuery.category" clearable style="width: 130px">
              <el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadWords">查询</el-button>
            <el-button v-hasPermi="['business:sensitive:edit']" icon="el-icon-plus" @click="openWord">新增</el-button>
            <el-button v-hasPermi="['business:sensitive:edit']" icon="el-icon-upload2" @click="openImport('words')">批量导入</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="words">
          <el-table-column label="词条" prop="word" />
          <el-table-column label="分类" width="120">
            <template slot-scope="s">{{ categoryLabel(s.row.category) }}</template>
          </el-table-column>
          <el-table-column label="动作" width="100">
            <template slot-scope="s">{{ s.row.action === 'LOG' ? '只记录' : '拦截' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template slot-scope="s">{{ s.row.enabled === 1 ? '启用' : '停用' }}</template>
          </el-table-column>
          <el-table-column label="更新时间" prop="updateTime" width="170" />
          <el-table-column label="操作" width="100">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:sensitive:edit']" type="text" @click="toggleWord(s.row)">
                {{ s.row.enabled === 1 ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="wordTotal > 0" :total="wordTotal" :page.sync="wordQuery.pageNum" :limit.sync="wordQuery.pageSize" @pagination="loadWords" />
      </el-tab-pane>

      <el-tab-pane label="放行短语" name="allows">
        <el-form :inline="true" size="small">
          <el-form-item label="短语">
            <el-input v-model="allowQuery.phrase" clearable style="width: 180px" @keyup.enter.native="loadAllows" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadAllows">查询</el-button>
            <el-button v-hasPermi="['business:sensitive:edit']" icon="el-icon-plus" @click="openAllow">新增</el-button>
            <el-button v-hasPermi="['business:sensitive:edit']" icon="el-icon-upload2" @click="openImport('allows')">批量导入</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="allows">
          <el-table-column label="短语" prop="phrase" />
          <el-table-column label="状态" width="90">
            <template slot-scope="s">{{ s.row.enabled === 1 ? '启用' : '停用' }}</template>
          </el-table-column>
          <el-table-column label="更新时间" prop="updateTime" width="170" />
          <el-table-column label="操作" width="100">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:sensitive:edit']" type="text" @click="toggleAllow(s.row)">
                {{ s.row.enabled === 1 ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="allowTotal > 0" :total="allowTotal" :page.sync="allowQuery.pageNum" :limit.sync="allowQuery.pageSize" @pagination="loadAllows" />
      </el-tab-pane>

      <el-tab-pane label="违规图片" name="images">
        <el-form :inline="true" size="small">
          <el-form-item label="分类">
            <el-select v-model="imageForm.category" style="width: 130px">
              <el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="imageForm.note" maxlength="200" style="width: 200px" />
          </el-form-item>
          <el-form-item>
            <el-upload v-hasPermi="['business:sensitive:edit']" action="" :show-file-list="false" accept="image/*" :http-request="uploadImage">
              <el-button icon="el-icon-plus">登记图片</el-button>
            </el-upload>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="images">
          <el-table-column label="SHA-256" prop="sha256" min-width="260" show-overflow-tooltip />
          <el-table-column label="近似识别" width="100">
            <template slot-scope="s">{{ s.row.dhash != null ? '支持' : '仅完全相同' }}</template>
          </el-table-column>
          <el-table-column label="分类" width="120">
            <template slot-scope="s">{{ categoryLabel(s.row.category) }}</template>
          </el-table-column>
          <el-table-column label="备注" prop="note" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template slot-scope="s">{{ s.row.enabled === 1 ? '启用' : '停用' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:sensitive:edit']" type="text" @click="toggleImage(s.row)">
                {{ s.row.enabled === 1 ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="imageTotal > 0" :total="imageTotal" :page.sync="imageQuery.pageNum" :limit.sync="imageQuery.pageSize" @pagination="loadImages" />
      </el-tab-pane>

      <el-tab-pane label="最近命中" name="hits">
        <el-table v-loading="loading" :data="hits">
          <el-table-column label="时间" prop="createTime" width="170" />
          <el-table-column label="来源" width="110">
            <template slot-scope="s">{{ sourceLabel(s.row.source) }}</template>
          </el-table-column>
          <el-table-column label="内容类型" prop="contentType" width="160" />
          <el-table-column label="内容 ID" prop="contentId" width="120" />
          <el-table-column label="分类" width="120">
            <template slot-scope="s">{{ categoryLabel(s.row.category) }}</template>
          </el-table-column>
          <el-table-column label="处理" prop="action" width="100" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="dialog.title" :visible.sync="dialog.open" width="480px" append-to-body>
      <el-form label-width="70px" size="small">
        <template v-if="dialog.kind === 'word'">
          <el-form-item label="词条"><el-input v-model="dialog.word" maxlength="100" /></el-form-item>
          <el-form-item label="分类">
            <el-select v-model="dialog.category">
              <el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="动作">
            <el-radio-group v-model="dialog.action">
              <el-radio label="BLOCK">拦截</el-radio>
              <el-radio label="LOG">只记录</el-radio>
            </el-radio-group>
          </el-form-item>
        </template>
        <el-form-item v-else-if="dialog.kind === 'allow'" label="短语">
          <el-input v-model="dialog.phrase" maxlength="100" />
        </el-form-item>
        <el-form-item v-else label="内容">
          <el-input v-model="dialog.text" type="textarea" :rows="10" :placeholder="dialog.placeholder" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" @click="submitDialog">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listWords, addWord, setWordEnabled, importWords,
  listAllows, addAllow, setAllowEnabled, importAllows,
  listImages, addImage, setImageEnabled, listHits
} from '@/api/business/sensitive'

const CATEGORIES = [
  { value: 'POLITICS', label: '政治' }, { value: 'PORN', label: '色情' }, { value: 'GAMBLING', label: '赌博' },
  { value: 'DRUGS', label: '毒品' }, { value: 'VIOLENCE', label: '暴力' }, { value: 'FRAUD', label: '诈骗' },
  { value: 'ABUSE', label: '辱骂' }, { value: 'AD', label: '广告' }, { value: 'OTHER', label: '其他' }
]

export default {
  name: 'SensitiveLibrary',
  data() {
    return {
      tab: 'words',
      loading: false,
      categories: CATEGORIES,
      words: [], wordTotal: 0, wordQuery: { pageNum: 1, pageSize: 20, word: '', category: '' },
      allows: [], allowTotal: 0, allowQuery: { pageNum: 1, pageSize: 20, phrase: '' },
      images: [], imageTotal: 0, imageQuery: { pageNum: 1, pageSize: 20 },
      imageForm: { category: 'OTHER', note: '' },
      hits: [],
      dialog: { open: false, kind: '', title: '', word: '', category: 'OTHER', action: 'BLOCK', phrase: '', text: '', placeholder: '' }
    }
  },
  created() {
    this.loadWords()
  },
  methods: {
    categoryLabel(v) {
      const c = CATEGORIES.find(x => x.value === v)
      return c ? c.label : v
    },
    sourceLabel(v) {
      return { TEXT: '提交文本', IMAGE: '图片', RESCAN: '存量复查' }[v] || v
    },
    load() {
      if (this.tab === 'words') this.loadWords()
      else if (this.tab === 'allows') this.loadAllows()
      else if (this.tab === 'images') this.loadImages()
      else this.loadHits()
    },
    loadWords() {
      this.loading = true
      listWords(this.wordQuery).then(r => { this.words = r.rows; this.wordTotal = r.total }).finally(() => { this.loading = false })
    },
    loadAllows() {
      this.loading = true
      listAllows(this.allowQuery).then(r => { this.allows = r.rows; this.allowTotal = r.total }).finally(() => { this.loading = false })
    },
    loadImages() {
      this.loading = true
      listImages(this.imageQuery).then(r => { this.images = r.rows; this.imageTotal = r.total }).finally(() => { this.loading = false })
    },
    loadHits() {
      this.loading = true
      listHits(200).then(r => { this.hits = r.data || [] }).finally(() => { this.loading = false })
    },
    toggleWord(row) {
      setWordEnabled(row.id, row.enabled === 1 ? 0 : 1).then(() => this.loadWords())
    },
    toggleAllow(row) {
      setAllowEnabled(row.id, row.enabled === 1 ? 0 : 1).then(() => this.loadAllows())
    },
    toggleImage(row) {
      setImageEnabled(row.id, row.enabled === 1 ? 0 : 1).then(() => this.loadImages())
    },
    openWord() {
      this.dialog = { ...this.dialog, open: true, kind: 'word', title: '新增敏感词', word: '', category: 'OTHER', action: 'BLOCK' }
    },
    openAllow() {
      this.dialog = { ...this.dialog, open: true, kind: 'allow', title: '新增放行短语', phrase: '' }
    },
    openImport(target) {
      this.dialog = {
        ...this.dialog,
        open: true,
        kind: target === 'words' ? 'importWords' : 'importAllows',
        title: '批量导入',
        text: '',
        placeholder: target === 'words' ? '每行一条：词条,分类,动作（分类如 GAMBLING，动作 BLOCK 或 LOG）' : '每行一个放行短语'
      }
    },
    submitDialog() {
      const d = this.dialog
      let p
      if (d.kind === 'word') p = addWord({ word: d.word, category: d.category, action: d.action })
      else if (d.kind === 'allow') p = addAllow({ phrase: d.phrase })
      else if (d.kind === 'importWords') p = importWords(d.text)
      else p = importAllows(d.text)
      p.then(r => {
        if (r.data && r.data.imported !== undefined) {
          this.$modal.msgSuccess('导入 ' + r.data.imported + ' 条，跳过 ' + r.data.skipped + ' 条')
        } else {
          this.$modal.msgSuccess('已保存')
        }
        this.dialog.open = false
        this.load()
      })
    },
    uploadImage(req) {
      const form = new FormData()
      form.append('file', req.file)
      form.append('category', this.imageForm.category)
      form.append('note', this.imageForm.note || '')
      addImage(form).then(() => {
        this.$modal.msgSuccess('已登记')
        this.loadImages()
      })
    }
  }
}
</script>
