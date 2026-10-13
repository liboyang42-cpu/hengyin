<template>
  <el-cascader
    ref="categoryChange"
    @change="handleMallCatergoryChange"
    v-model="value1"
    :options="menuOptions"
    v-bind="$props"
    clearable
    placeholder="选择类目"
  />
</template>

<script>
import "@riophae/vue-treeselect/dist/vue-treeselect.css";
import {mapGetters} from "vuex";
import {listCategory} from "@/api/system/category"

export default {
  name: "ProductCategorySelect",
  props: ['value', 'props'],
  data() {
    return {
      productCategories: []
    }
  },
  computed: {
    ...mapGetters(['productCategories']),
    value1: {
      get() {
        return this.value;
      },
      set(v) {
        this.$emit('input', v);
      }
    },
    menuOptions() {
      if (!this.productCategories || this.productCategories.length === 0) {
        return [];
      }
      this.recurs(this.productCategories)
      return this.productCategories
    }
  },
  methods: {
    handleMallCatergoryChange(e) {
      if (e.length > 0) {
        let arr = this.$refs['categoryChange'].getCheckedNodes()[0].pathLabels
        console.log("aaa"+arr)
        this.$emit('change', arr);
      } else {
        this.$emit('change', '');
      }
    },
    recurs(list) {
      list.forEach(it => {
        it.label = it.categoryName
        it.value = it.id
        if (it.children) {
          this.recurs(it.children)
        }
      })
    },
    getProductCategoryList() {
      listCategory({
        parentId: null,
        nameLike: null,
        level: null,
        showStatus: null,
        sort: null,
      }).then((rows) => {
        this.productCategories = this.handleTree(rows, "id");
      });
    }
  },
  created() {
    this.getProductCategoryList()
  }
}
</script>

<style scoped>

</style>
