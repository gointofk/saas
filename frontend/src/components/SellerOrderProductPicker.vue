<script setup lang="ts">
import { useSellerTranslation } from '../lib/seller-locale'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppModal from './AppModal.vue'
import SellerSearchField from './SellerSearchField.vue'
import { auditOrderApi, type AuditEditProduct, type AuditEditProductPage } from '../lib/api'

const t = useSellerTranslation()

const props = defineProps<{
  orderId: number
  giftsOnly: boolean
  unavailableReason: (product: AuditEditProduct) => string
  additionHint: (product: AuditEditProduct) => string
  money: (value: string | number) => string
}>()
const emit = defineEmits<{ close: []; confirm: [product: AuditEditProduct] }>()
const searchInput = ref<HTMLInputElement | null>(null)
const query = ref('')
const products = ref<AuditEditProductPage>({ items: [], page: 1, total: 0, totalPages: 0 })
const selected = ref<AuditEditProduct | null>(null)
const searching = ref(false)
const error = ref('')
let mounted = true
let requestSequence = 0
const canConfirm = computed(() => selected.value !== null && !searching.value && !error.value && !props.unavailableReason(selected.value))
onBeforeUnmount(() => { mounted = false; requestSequence += 1 })

async function search(page = 1) {
  const sequence = ++requestSequence
  searching.value = true
  error.value = ''
  try {
    const result = await auditOrderApi.products(props.orderId, query.value.trim(), page)
    if (!mounted || sequence !== requestSequence) return
    products.value = result
    const refreshed = result.items.find(product => product.id === selected.value?.id)
    if (refreshed) selected.value = refreshed
  } catch (value) {
    if (mounted && sequence === requestSequence) {
      products.value = { items: [], page: 1, total: 0, totalPages: 0 }
      error.value = value instanceof Error ? value.message : '商品加载失败，请重试。'
    }
  } finally { if (mounted && sequence === requestSequence) searching.value = false }
}

function confirm() {
  if (selected.value && canConfirm.value) emit('confirm', selected.value)
}

onMounted(() => {
  void search()
  requestAnimationFrame(() => { if (mounted) searchInput.value?.focus() })
})
</script>

<template>
  <AppModal :label="t('新增订单商品')" content-class="seller-modal seller-order-product-picker" @close="emit('close')">
    <div class="panel-header"><h2 class="modal-title">{{ giftsOnly ? t('新增赠品') : t('新增商品') }}</h2><button class="close-button" type="button" :aria-label="t('关闭商品选择')" @click="emit('close')">×</button></div>
    <form class="product-picker-search" @submit.prevent="search(1)">
      <SellerSearchField :loading="searching" @refresh="search(1)">
        <input ref="searchInput" v-model="query" type="search" :placeholder="t('搜索商品名称 / SKU')" :aria-label="t('搜索商品名称或 SKU')" autocomplete="off">
      </SellerSearchField>
    </form>
    <p class="product-picker-help">{{ giftsOnly ? t('付款已申报，仅可选择零价赠品。') : t('选择一个商品，确认后加入订单。') }}</p>
    <div class="product-picker-results" :aria-busy="searching">
      <div v-if="error" class="product-picker-message product-picker-error" role="alert"><p>{{ t(error) }}</p><button class="btn btn-outline" type="button" @click="search(1)">{{ t('重新加载') }}</button></div>
      <p v-else-if="searching" class="product-picker-message" role="status">{{ t('正在加载商品…') }}</p>
      <div v-else-if="!products.items.length" class="product-picker-message"><p>{{ t('没有匹配的') }}{{ giftsOnly ? t('赠品') : t('商品') }}</p><small>{{ t('请尝试其他名称或 SKU。') }}</small></div>
      <fieldset v-else class="product-picker-options"><legend class="sr-only">{{ t('选择要添加的商品') }}</legend>
        <label v-for="product in products.items" :key="product.id" :class="['product-picker-option', { selected: selected?.id === product.id, unavailable: !!unavailableReason(product) }]">
          <input v-model="selected" type="radio" name="order-add-product" :value="product" :disabled="!!unavailableReason(product)">
          <span class="product-picker-description"><strong>{{ product.name }}</strong><small>SKU：{{ product.sku }}</small><small v-if="unavailableReason(product)" class="product-picker-unavailable-reason">{{ t(unavailableReason(product)) }}</small></span>
          <span class="product-picker-facts"><strong>{{ money(product.unitPrice) }}</strong><small>{{ product.productType === 'gift' ? t('赠品') : t('正品') }} {{ t('· 库存') }} {{ product.stock }}</small></span>
        </label>
      </fieldset>
    </div>
    <div v-if="products.totalPages > 1" class="product-picker-pagination"><button class="btn btn-outline" type="button" :disabled="searching || products.page <= 1" @click="search(products.page - 1)">{{ t('上一页') }}</button><span>{{ products.page }} / {{ products.totalPages }}</span><button class="btn btn-outline" type="button" :disabled="searching || products.page >= products.totalPages" @click="search(products.page + 1)">{{ t('下一页') }}</button></div>
    <div class="product-picker-selection" aria-live="polite"><template v-if="selected"><strong>{{ t('已选：') }}{{ selected.name }}</strong><small>{{ t(unavailableReason(selected) || additionHint(selected)) }}</small></template><span v-else>{{ t('请选择一个商品') }}</span></div>
    <div class="modal-actions"><button class="modal-cancel" type="button" @click="emit('close')">{{ t('取消') }}</button><button class="modal-save" type="button" :disabled="!canConfirm" @click="confirm">{{ t('确认添加') }}</button></div>
  </AppModal>
</template>

<style scoped>
.product-picker-search { display: flex; gap: 10px; }
.product-picker-search > .seller-search-field { flex: 1; }
.product-picker-search input { flex: 1; min-width: 0; }
.product-picker-search button { flex-shrink: 0; }
.product-picker-help { margin: 12px 0 16px; color: #6e6e73; font-size: 13px; }
.product-picker-results { min-height: 180px; max-height: min(44vh, 400px); overflow-y: auto; }
.product-picker-options { margin: 0; padding: 0; border: 0; display: grid; gap: 8px; }
.product-picker-option { display: flex; align-items: center; gap: 12px; padding: 14px; border: 1px solid #e5e5ea; border-radius: 10px; cursor: pointer; }
.product-picker-option.selected { border-color: #06c; background: #f2f7ff; }
.product-picker-option:focus-within { outline: 2px solid #06c; outline-offset: 2px; }
.product-picker-option.unavailable { color: #6e6e73; background: #f5f5f7; cursor: default; }
.product-picker-options .product-picker-option input[type="radio"] { width: 16px; height: 16px; margin: 0; padding: 0; flex-shrink: 0; accent-color: #06c; }
.product-picker-description { display: grid; gap: 6px; flex: 1; min-width: 0; overflow-wrap: anywhere; }
.product-picker-facts { display: grid; gap: 6px; text-align: right; flex-shrink: 0; }
.product-picker-option small { color: #6e6e73; font-size: 12px; }
.product-picker-option .product-picker-unavailable-reason { color: #a82e38; }
.product-picker-message { margin: 0; padding: 36px 16px; text-align: center; color: #6e6e73; }
.product-picker-message p { margin: 0 0 12px; }
.product-picker-error { color: #a82e38; }
.product-picker-pagination { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 14px; }
.product-picker-selection { display: grid; gap: 6px; margin-top: 20px; color: #1d1d1f; overflow-wrap: anywhere; }
.product-picker-selection small, .product-picker-selection > span { color: #6e6e73; font-size: 13px; }
@media (max-width: 500px) {
  .product-picker-option { flex-wrap: wrap; padding: 12px; }
  .product-picker-description { flex-basis: calc(100% - 32px); }
  .product-picker-facts { width: 100%; padding-left: 26px; text-align: left; }
  .product-picker-pagination { justify-content: center; gap: 8px; }
}
</style>
