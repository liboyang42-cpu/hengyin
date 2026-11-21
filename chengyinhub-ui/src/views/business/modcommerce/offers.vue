<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="资金与售后"
      title="工坊内购商品"
      description="把已审核的模板发布版本对应到 App Store Connect 里的非消耗型商品。价格必须与该商品在 App Store 的价格档位一致；要改价请新建商品。新建的商品默认下架。"
    />

    <el-alert
      v-if="!open"
      type="info"
      :closable="false"
      show-icon
      title="App 内购买当前关闭：需要服务端 chengyin.mod-commerce.enabled 打开，且资金开关的「通行证」区域打开（仅测试环境）。这里仍可准备商品。"
      style="margin-bottom: 16px"
    />

    <section class="cy-panel" aria-label="新建商品">
      <div class="cy-panel__heading"><div><h2>新建商品</h2><p>版本必须已审核通过；商品 ID 在 App Store Connect 先建好。</p></div></div>
      <el-form ref="form" :model="form" :rules="rules" label-width="120px" size="small" inline>
        <el-form-item label="发布版本 ID" prop="versionId">
          <el-input v-model="form.versionId" placeholder="WKV_…" style="width: 300px" />
        </el-form-item>
        <el-form-item label="市场" prop="market">
          <el-select v-model="form.market" style="width: 140px">
            <el-option label="中国 (CNY)" value="CN" />
            <el-option label="美国 (USD)" value="US" />
          </el-select>
        </el-form-item>
        <el-form-item label="App Store 商品 ID" prop="productId">
          <el-input v-model="form.productId" placeholder="例如 com.example.template.oldtown" style="width: 300px" />
        </el-form-item>
        <el-form-item label="价格（分/美分）" prop="priceMinor">
          <el-input-number v-model="form.priceMinor" :min="1" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['business:modcommerce:edit']" type="primary" @click="submit">新建</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="cy-panel" aria-label="商品列表" style="margin-top: 16px">
      <el-table v-loading="loading" :data="rows" size="small" empty-text="还没有商品">
        <el-table-column label="标题" prop="title" min-width="160" />
        <el-table-column label="发布版本" prop="versionId" min-width="200" />
        <el-table-column label="审核" prop="reviewState" min-width="90" />
        <el-table-column label="商品 ID" prop="productId" min-width="220" />
        <el-table-column label="价格" min-width="100">
          <template slot-scope="scope">{{ (scope.row.priceMinor / 100).toFixed(2) }} {{ scope.row.currency }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="80">
          <template slot-scope="scope">{{ scope.row.enabled ? '上架' : '下架' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="120">
          <template slot-scope="scope">
            <el-button
              v-hasPermi="['business:modcommerce:edit']"
              size="mini"
              :type="scope.row.enabled ? 'danger' : 'warning'"
              plain
              @click="toggle(scope.row)"
            >{{ scope.row.enabled ? '下架' : '上架' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script>
import { listModCommerceOffers, createModCommerceOffer, setModCommerceOfferEnabled } from '@/api/business/modcommerce'

export default {
  name: 'ModCommerceOffers',
  data() {
    return {
      loading: false,
      open: false,
      rows: [],
      form: { versionId: '', market: 'CN', productId: '', priceMinor: 600 },
      rules: {
        versionId: [{ required: true, message: '请填写发布版本 ID', trigger: 'blur' }],
        productId: [{ required: true, message: '请填写 App Store 商品 ID', trigger: 'blur' }],
        priceMinor: [{ required: true, message: '请填写价格', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      listModCommerceOffers().then(res => {
        this.rows = res.data || []
        this.open = res.open === true
      }).finally(() => { this.loading = false })
    },
    submit() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        createModCommerceOffer({ ...this.form, versionId: this.form.versionId.trim(), productId: this.form.productId.trim() })
          .then(res => {
            if (res && res.code === 200) {
              this.$modal.msgSuccess('已新建（下架）')
              this.load()
            }
          })
      })
    },
    toggle(row) {
      const enabled = !row.enabled
      this.$modal.confirm(`确认${enabled ? '上架' : '下架'}「${row.title || row.productId}」？`)
        .then(() => setModCommerceOfferEnabled(row.offerId, enabled))
        .then(() => {
          this.$modal.msgSuccess('已更新')
          this.load()
        })
        .catch(() => {})
    }
  }
}
</script>
