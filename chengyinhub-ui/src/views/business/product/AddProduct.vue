<template>
  <div class="app-container add-product-wrapper">
    <cy-page-header
      eyebrow="商品与积分"
      :title="form.id ? '编辑积分商城商品' : '新增积分商城商品'"
      description="维护商品资料、图片和 SKU；上架或下架请返回商品目录执行。"
    >
      <el-button icon="el-icon-arrow-left" @click="cancel">返回商品目录</el-button>
    </cy-page-header>
    <el-form label-width="130px" :model="form" ref="form" :rules="rules">
      <el-card style="margin: 20px 20px; font-size: 14px">
        <div slot="header">
          <span>基础资料</span>
        </div>
        
        <el-row>
          <!--          商家-->
          <el-col :span="8">
            <el-form-item label="所属商家" prop="merchantId">
               <el-select style="width: 100%" v-model="form.merchantId" placeholder="请选择">
              <el-option v-for="item in merchantList" :key="item.id" :label="item.name" :value="item.id" >
              </el-option>
            </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            
          </el-col>
        </el-row>
        <el-row>
          <!--          商品名称-->
          <el-col :span="8">
            <el-form-item label="商品名称" prop="productName">
              <el-input v-model="form.productName"  placeholder="请输入商品名称"></el-input>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            
          </el-col>
        </el-row>
        <el-row>
       
          <el-col :span="8">
            <el-form-item  label="售价" prop="price">
              <el-input v-model="form.price"
                placeholder="请输入售价"></el-input>
            </el-form-item>
         
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="8">
            <el-form-item label="排序" prop="sortId">
              <el-input v-model="form.sortId"
                 placeholder="请输入排序"></el-input>
            </el-form-item>
          </el-col>
        </el-row>
      </el-card>
      <!--商品图片-->
      <el-card style="margin: 20px 20px; font-size: 14px">
        <div slot="header">
          <span>商品图片</span>
        </div>
        <el-form-item label="商品主图" prop="pic">
          <image-upload v-model="form.pic" :limit="1"></image-upload>
        </el-form-item>
        <el-form-item   label="轮播图" prop="albumPics">
          <image-upload v-model="form.albumPics" :limit="5"></image-upload>
        </el-form-item>
      </el-card>
      <!--产品规格-->
      <el-card style="margin: 20px 20px; font-size: 14px">
        <div slot="header">
          <span>产品规格</span>
        </div>
        <el-form-item  label="规格类型">
          <div class="sku-wrapper">
            <div class="sku_sorts">
              <div class="sku_sort" v-for="(s, idx0) in productAttr" >
                <div class="label flex-center">
                  <el-row>
                    <el-col :span="18">
                      <div>
                        规格类型：
                        <el-input v-model="s.name" placeholder="请输入内容"
                          @input="refreshSku" style="width: 200px"></el-input>
                      </div>
                    </el-col>
                    <el-col :span="6">
                      <el-button type="text" style="color: red;float:right;" @click="deleteSkuSort(idx0)">
                        删除规格类型
                      </el-button>
                    </el-col>
                  </el-row>
                </div>
                <el-row class="values">
                  <el-col :span="23">
                    <el-row :gutter="10" v-if="s.name || s.nameAr">
                      <el-col :span="8" class="value" v-for="(it2, idx1) in s.options" :key="idx1">
                        <el-row :gutter="10">
                          <el-col :span="10">
                            <el-input v-model="it2.name" placeholder="请输入内容"
                              @input="refreshSku"></el-input>
                          </el-col>
                          <el-col :span="4">
                            <el-button type="text" style="color: red;margin-left: 5px" @click="deleteOption(s, idx1)">删除</el-button>
                          </el-col>
                        </el-row>
                      </el-col>
                    </el-row>
                  </el-col>
                  <el-col :span="1">
                    <el-button type="text" style="margin-left: 5px;" @click="addOption(s, idx1, it2)">新增</el-button>

                  </el-col>
                </el-row>
              </div>
            </div>
            <el-button v-if="productAttr.length < 2" @click="addSkuSort">添加规格类型</el-button>
          </div>
        </el-form-item>
        <el-form-item label="规格信息">
          <el-button @click="refreshSku()" class="mb20">刷新列表</el-button>
          <el-table :data="form.skuList" :max-height="600">
            <el-table-column v-for="s in skuAttr" :label="s.name" :key="s.name" :prop="s.name">
            </el-table-column>
            <el-table-column label="显示图片">
              <template v-slot="{ row }">
                <image-upload class="img-upload-mini" v-model="row.pic" :limit="1" :is-show-tip="false"></image-upload>
              </template>
            </el-table-column>
            <el-table-column label="售价">
              <template v-slot="{ row, $index }">
                <el-input v-model="row.price" type="number"></el-input>
              </template>
            </el-table-column>
            <el-table-column  label="库存">
              <template v-slot="{ row, $index }">
                <el-input v-model="row.stock" type="number"></el-input>
              </template>
            </el-table-column>
            <el-table-column label="商品重量">
              <template v-slot="{ row }">
                <el-input v-model="row.weight" type="number"></el-input>
              </template>
            </el-table-column>
  
          </el-table>
        </el-form-item>
      </el-card>
      <!--      商品详情-->
      <el-tabs type="border-card" style="margin: 20px 20px; font-size: 14px">
        <el-tab-pane  label="商品详情页">
          <el-form-item label-width="auto" prop="detailHtml">
            <Editor min-height="300" v-model="form.detailHtml"  placeholder="请输入内容" type="url"></Editor>
          </el-form-item>
        </el-tab-pane>
      </el-tabs>
      <div class="tc">
        <el-button type="primary" @click="submitForm">提交</el-button>
        <el-button @click="cancel">取消</el-button>
      </div>
    </el-form>
  </div>
</template>

<script>
import { addProduct, getProduct, updateProduct  } from  "@/api/business/product"
import { listMerchant} from "@/api/business/merchant"
import CyPageHeader from '@/components/CyPageHeader'

export default {
  name: "AddProduct",
  components: { CyPageHeader },
  data() {
    return {
      rules: {
        productName: [
          { required: true, message: '请输入商品名称', trigger: 'blur' },
        ],
        productNameAr: [
          { required: true, message: '请输入商品名称', trigger: 'blur' },
        ],
        price: [{ required: true, trigger: "price", message: "请输入价格" }],
        pic: [{ required: true, trigger: "pic", message: "请选择主图" }],
        albumPics: [{ required: true, trigger: "albumPics", message: "请选择轮播图" }],
      },
      form: {
        showChannel: 1,
        cityIds: [],
        countryId: null,
        sortId: 1000
      },
      skuAttr: [],
      albumPics: null,
      productAttr: [
        {
          name: '',
          nameAr: '',
          options: [
            { name: '', nameAr: '' }
          ]
        }
      ],
      maxOptionNum: 44,
      productCategories: [],
      cityList: [],
      countryList: [],
      merchantList:[],
      // tabTitle: [],
      // getData: {},
      // tab: [],
      // remaining: 0,
    }
  },
  created() {
    const { id } = this.$route.params
    const { action } = this.$route.query

    console.log("id"+id)
    if (id) {
      this.action = action
      this.getInfo(id);
    }
    else{
      this.getMerchant()
    }
  },
  methods: {
    getMerchant() {
      listMerchant({
        pageNum: 1,
        pageSize: 1000
      }).then(res => {
        this.merchantList = res.rows       
      })
    },
    refreshSku() {
      let skus = [];
      let skuMap = new Map()
      this.skuAttr = [...this.productAttr]
      if (this.form.skuList) {
        this.form.skuList.forEach(sku => {
          skuMap.set(sku.spData, sku)
        })
      }
      this.productAttr.forEach((attr, index) => {
        const attrSku = [];
        attr.options.forEach((option) => {
          if (!option.name) {
            return
          }
          if (index === 0) {
            attrSku.push({ [attr.name]: option.name });
          } else {
            skus.forEach(it3 => {
              attrSku.push({ ...it3, [attr.name]: option.name })
            })
          }
        })
        skus = attrSku;
      })
      skus.forEach(it => {
        if (it) {
          it.spData = JSON.stringify(it)
        }
      })
      skus.forEach(it => {
        let sku = skuMap.get(it.spData);
        if (sku) {
          it.outSkuId = sku.outSkuId;
          it.price = sku.price;
          it.pic = sku.pic;
          it.stock = sku.stock;
          it.weight = sku.weight
          it.id = sku.id
        } else {
          it.outSkuId = null;
          it.price = null;
          it.pic = null;
          it.stock = null;
          it.weight = null
          it.id = null
        }
        if (this.action == "copy") {
          it.id = null;
        }
      })
      this.form.productAttr = JSON.stringify(this.productAttr)
      this.form.skuList = skus
    },
    handleChange(value) {
      console.log(value);
    },
    recurs(list) {
      list.forEach(it => {
        it.label = it.categoryName
        it.value = it.id
        if (it.children) {
          this.recurs(it.children)
        }
      })
      console.log(list)
    },
    getInfo(id) {
      getProduct(id).then(response => {
        const { albumPics } = response.data
        if (albumPics) {
          this.form.albumPics = albumPics.split(',')
        }
        this.form = response.data;
        if (this.action == "copy") {
          this.form.id = null;
        }
        if (this.form.productAttr) {
          this.productAttr = JSON.parse(this.form.productAttr)
        }
        this.refreshSku()
      });
    },
    /** 提交按钮 */
    submitForm() {


      this.$refs["form"].validate(valid => {
        if (valid) {

          if (this.skuAttr.length == 0) {
            // console.log(this.form.skuList)
            this.$modal.msgError("请录入规格信息");
            return;
          }

          if (this.form.skuList) {
            for (var i = 0; i < this.form.skuList.length; i++) {
              var sku = this.form.skuList[i];
              if (!sku.price) {
                this.$modal.msgError("请录入规格价格");
                return;
              }
              if (!sku.stock) {
                sku.stock = 0;
              }
              if (!sku.weight) {
                this.$modal.msgError("请录入规格重量");
                return;
              }
            }
          }

          if (this.form.albumPics) {
            this.form.albumPics = this.form.albumPics.toString()
          }
          //商品价格没填时取sku的最低价
          if (!this.form.price) {
            this.form.price = Math.min.apply(Math, this.form.skuList.map(it => it.price))
          }
          const request = this.form.id != null ? updateProduct(this.form) : addProduct(this.form)
          const successMessage = this.form.id != null ? "修改成功" : "新增成功"
          request.then(() => {
            this.$modal.msgSuccess(successMessage)
            this.cancel()
          })
        }
      }
      )
        ;
    },
    cancel() {
      this.$tab.closeOpenPage({ path: '/config/points-product' })
    },
    addOption(s, idx, val) {

      s.options.push({ name: null, nameAr: '' })
    }
    ,
    addSkuSort() {
      this.productAttr.push({
        name: "",
        nameAr: "",
        options: [{ name: "", nameAr: '' }]
      })
    }
    ,
    deleteSkuSort(idx) {
      this.productAttr.splice(idx);
    }
    ,
    deleteOption(s, idx) {
      s.options.splice(idx, 1);
    },
    getCity() {
      getList({
        countryId: this.form.countryId==null?0:this.form.countryId
      }).then(res => {
        this.cityList = res.data
        this.cityList.unshift({
          countryId: this.form.countryId,
          name: '全部',
          id: '0',
          nameAr: '',
          status: 0
        });
      })
    },
    getCountry() {
      listCountry({
        pageNum: 1,
        pageSize: 1000
      }).then(res => {
        this.countryList = res.rows       
      })
    },
    handleCityChange() {
      console.log(this.form.cityIds)
    },
  }
}
</script>
 

<style lang="stylus">
.add-product-wrapper {
  padding: var(--cy-space-3);

  .content {
    margin: 0 auto;
    width: 75%;
    min-width: 800px;
  }

  .sku-wrapper {
    background-color: #f7f8fa;
    padding: var(--cy-space-3);
  }

  .sku_sorts .sku_sort {
    background-color: var(--cy-color-bg-surface);
    margin-bottom: var(--cy-space-3);

  }

  .label {
    padding: var(--cy-space-2);
  }

  .values {
    border-top: #ccc solid 1px;
    padding: var(--cy-space-2) 0 0 var(--cy-space-2);
    border-top: 1px solid $border-color;
    //display: flex;
    //flex-wrap: wrap;
  }

  .value {
    padding: 0 var(--cy-space-7) var(--cy-space-2) 0; /* ds-ok: 32px 无匹配 token */
    min-width: 400px !important;
    //display: flex;
    //align-items: center;

  }

  .img-upload-mini .el-upload--picture-card {
    width: 50px;
    height: 50px;
    line-height: 50px;

  }


}

</style>
