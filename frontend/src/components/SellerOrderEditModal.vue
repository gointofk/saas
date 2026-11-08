<script setup lang="ts">
import { useSellerTranslation } from '../lib/seller-locale'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import AppModal from './AppModal.vue'
import SellerOrderProductPicker from './SellerOrderProductPicker.vue'
import { ApiRequestError, auditOrderApi, type AuditEditableOrder, type AuditEditProduct, type AuditOrderItem } from '../lib/api'
import { quantityUnitPrice } from '../lib/quantity-pricing'
import { amountFromCents } from '../lib/payment-amount'

const t = useSellerTranslation()

const props = defineProps<{ orderId: number; orderNo: string }>()
const emit = defineEmits<{ close: []; saved: [order: AuditEditableOrder] }>()
type DraftItem = Omit<AuditOrderItem, 'unitPrice'> & { unitPrice: string; manualOverride?: boolean }
const order = ref<AuditEditableOrder | null>(null)
const items = ref<DraftItem[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const reason = ref('')
const discountAmount = ref('0.00')
const productPickerOpen = ref(false)
const addProductButton = ref<HTMLButtonElement | null>(null)
const stale = ref(false)
let mounted = true
onBeforeUnmount(() => { mounted = false })
const locked = computed(() => !order.value?.allowedActions.includes('audit_discount'))
const disabled = computed(() => loading.value || saving.value || stale.value)

function cents(value: string | number): bigint | null {
  const match = String(value).match(/^(\d+)(?:\.(\d{1,2}))?$/)
  return match ? BigInt(match[1]) * 100n + BigInt((match[2] || '').padEnd(2, '0')) : null
}
function effectivePrice(item: DraftItem): string | number {
  return item.manualOverride ? item.unitPrice : quantityUnitPrice(item.quantityPrices, item.quantity, item.unitPrice)
}
function setManualPrice(item: DraftItem, value: string) { item.unitPrice = value; item.manualOverride = true }
function lineAmount(item: DraftItem): bigint | null {
  const price = cents(effectivePrice(item))
  return price !== null && Number.isSafeInteger(item.quantity) && item.quantity > 0 ? price * BigInt(item.quantity) : null
}
function money(value: bigint | null) {
  if (value === null) return '—'
  return `$${(value / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${(value % 100n).toString().padStart(2, '0')}`
}
const subtotal = computed(() => {
  let amount = 0n
  for (const item of items.value) {
    const line = lineAmount(item)
    if (line === null) return null
    amount += line
  }
  return amount
})
const shipping = computed(() => cents(order.value?.shippingFee ?? '0'))
const discount = computed(() => cents(discountAmount.value.trim() || '0'))
const total = computed(() => subtotal.value !== null && shipping.value !== null && discount.value !== null ? subtotal.value + shipping.value - discount.value : null)
const discountError = computed(() => {
  if (discount.value === null) return '优惠额必须为非负金额，最多两位小数。'
  if (subtotal.value !== null && discount.value > subtotal.value) return '优惠额不能超过商品金额。'
  if (discount.value > 0n && total.value !== null && total.value <= 0n) return '优惠后应付金额必须大于 0。'
  return ''
})
const priceChanged = computed(() => items.value.some(item => {
  return item.manualOverride === true
}))
function errorText(value: unknown) { return value instanceof Error ? value.message : '请求失败，请重试。' }

async function load() {
  if (saving.value || !mounted) return
  loading.value = true
  stale.value = false
  error.value = ''
  try {
    const result = await auditOrderApi.detail(props.orderId)
    if (!mounted) return
    if (!result.allowedActions.includes('audit_edit')) {
      stale.value = true
      error.value = '订单已审核或取消，无法编辑。请关闭弹窗并刷新列表。'
      order.value = null
      return
    }
    order.value = result
    discountAmount.value = String(result.discountAmount)
    items.value = result.items.map(item => ({ ...item, unitPrice: String(item.unitPrice) }))
    reason.value = ''
  } catch (value) { if (mounted) { order.value = null; error.value = errorText(value) } }
  finally { if (mounted) loading.value = false }
}

function unavailableReason(product: AuditEditProduct) {
  const original = order.value?.items.find(item => item.productId === product.id)
  const current = items.value.find(item => item.productId === product.id)
  const type = current?.productType ?? original?.productType ?? product.productType
  if (locked.value && type !== 'gift') return '已有确认收款，不能调整正品。'
  if (current && (!Number.isSafeInteger(current.quantity) || current.quantity < 1)) return '请先修正订单中该商品的数量。'
  if (current && current.quantity >= 2147483647) return '该商品数量已达上限。'
  const quantityAfter = current ? current.quantity + 1 : original?.quantity ?? 1
  return quantityAfter - (original?.quantity ?? 0) > product.stock ? '可用库存不足。' : ''
}

function additionHint(product: AuditEditProduct) {
  const current = items.value.find(item => item.productId === product.id)
  if (current) return `订单中已有 ${current.quantity} 件，确认后增加至 ${current.quantity + 1} 件。`
  const original = order.value?.items.find(item => item.productId === product.id)
  if (original) return `确认后恢复原商品 ${original.quantity} 件，沿用原成交单价 ${money(cents(original.unitPrice))}。`
  return `确认后加入 1 件，单价 ${money(cents(product.unitPrice))}。`
}

async function closeProductPicker() {
  productPickerOpen.value = false
  await nextTick()
  addProductButton.value?.focus()
}

function add(product: AuditEditProduct) {
  if (disabled.value || unavailableReason(product)) return
  const current = items.value.find(item => item.productId === product.id)
  if (current) {
    if (locked.value && current.productType !== 'gift') return
    if (Number.isSafeInteger(current.quantity) && current.quantity < 2147483647) current.quantity += 1
  } else {
    // Restoring a row removed in this draft keeps its original price and type snapshot.
    const original = order.value?.items.find(item => item.productId === product.id)
    if (original) items.value.push({ ...original, unitPrice: String(original.unitPrice) })
    else items.value.push({ productId: product.id, sku: product.sku, name: product.name, productType: product.productType, quantity: 1, quantityPrices: product.quantityPrices, unitPrice: String(product.unitPrice), lineTotal: product.unitPrice })
  }
  error.value = ''
  void closeProductPicker()
}

async function save() {
  if (!order.value || disabled.value || productPickerOpen.value) return
  if (!items.value.length) { error.value = '订单至少保留一行商品。'; return }
  for (const item of items.value) {
    const price = cents(effectivePrice(item))
    if (!Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 2147483647) { error.value = `${item.name}：数量必须为正整数，最大 2,147,483,647。`; return }
    if (price === null || (item.productType === 'gift' ? price !== 0n : price <= 0n)) { error.value = `${item.name}：正品单价必须大于 0 且最多两位小数；赠品固定为 0。`; return }
  }
  if (priceChanged.value && !reason.value.trim()) { error.value = '调整单价时请填写修改原因。'; return }
  if (discountError.value) { error.value = discountError.value; return }
  saving.value = true
  error.value = ''
  try {
    const result = await auditOrderApi.update(props.orderId, {
      items: items.value.map(item => ({ productId: item.productId, quantity: item.quantity, unitPrice: item.manualOverride ? item.unitPrice : null, manualPrice: item.manualOverride === true })),
      reason: reason.value.trim() || null,
      expectedUpdatedAt: order.value.updatedAt,
      discountAmount: locked.value ? null : amountFromCents(discount.value!),
    })
    emit('saved', result)
  } catch (value) {
    error.value = errorText(value)
    if (value instanceof ApiRequestError && ['ORDER_CHANGED', 'ORDER_NOT_EDITABLE', 'ORDER_AMOUNT_LOCKED'].includes(value.code)) stale.value = true
  } finally { saving.value = false }
}
function close() { if (!saving.value && !productPickerOpen.value) emit('close') }
onMounted(load)
</script>

<template>
  <AppModal :label="t('编辑订单 {0}', [orderNo])" content-class="seller-modal seller-order-edit-modal" :inert="productPickerOpen" :aria-hidden="productPickerOpen || undefined" @close="close">
    <div class="panel-header"><div><p class="eyebrow">{{ t('订单审核') }}</p><h2 class="modal-title">{{ t('编辑订单 ·') }} {{ orderNo }}</h2></div><button class="close-button" type="button" :aria-label="t('关闭')" :disabled="saving" @click="close">×</button></div>
    <p v-if="loading" class="muted" role="status">{{ t('正在加载订单…') }}</p>
    <div v-if="error" class="seller-order-edit-error" role="alert">{{ t(error) }} <button v-if="!saving && (stale || !order)" class="link-button" type="button" @click="load">{{ t('重新加载订单') }}</button></div>
    <template v-if="order && !loading">
      <p v-if="locked" class="muted">{{ t('已有确认收款，正品和金额已锁定；仅可调整零价赠品。') }}</p>
      <div class="seller-order-edit-items">
        <div v-for="item in items" :key="item.productId" class="seller-order-edit-row">
          <div class="seller-order-edit-product"><strong>{{ item.name }}</strong><small>{{ item.sku }} · {{ item.productType === 'gift' ? t('赠品') : t('正品') }}</small></div>
          <label class="form-group">{{ t('单价') }}<input :value="effectivePrice(item)" @input="setManualPrice(item, ($event.target as HTMLInputElement).value)" type="text" inputmode="decimal" :disabled="disabled || locked || item.productType === 'gift'" :aria-label="t('{0} 单价', [item.name])"></label>
          <label class="form-group">{{ t('数量') }}<input v-model.number="item.quantity" type="number" min="1" max="2147483647" step="1" :disabled="disabled || (locked && item.productType !== 'gift')" :aria-label="t('{0} 数量', [item.name])"></label>
          <div class="seller-order-edit-amount"><small>{{ t('金额') }}</small><strong>{{ money(lineAmount(item)) }}</strong></div>
          <button class="btn btn-outline" type="button" :disabled="disabled || items.length <= 1 || (locked && item.productType !== 'gift')" :aria-label="t('删除 {0}', [item.name])" @click="items = items.filter(row => row.productId !== item.productId)">{{ t('删除') }}</button>
        </div>
      </div>
      <div class="seller-order-add-product"><button ref="addProductButton" class="btn btn-outline" type="button" :disabled="disabled" aria-haspopup="dialog" @click="productPickerOpen = true"><span aria-hidden="true">＋</span> {{ t('新增商品') }}</button><small v-if="locked">{{ t('仅可添加零价赠品') }}</small></div>
      <label class="form-group">{{ t('优惠额 (USD)') }}<input v-model="discountAmount" type="text" inputmode="decimal" autocomplete="off" maxlength="19" :disabled="disabled || locked" :aria-invalid="Boolean(discountError)" :aria-describedby="[locked && 'edit-discount-hint', discountError && 'edit-discount-error'].filter(Boolean).join(' ') || undefined"><small v-if="locked" id="edit-discount-hint">{{ t('已有确认收款，优惠额已锁定。') }}</small><small v-if="discountError" id="edit-discount-error" role="alert">{{ t(discountError) }}</small></label>
      <div class="summary-lines"><div><span>{{ t('商品小计（预览）') }}</span><strong>{{ money(subtotal) }}</strong></div><div><span>{{ t('运费') }}</span><strong>{{ money(shipping) }}</strong></div><div v-if="discount && discount > 0n"><span>{{ t('优惠额') }}</span><strong>−{{ money(discount) }}</strong></div><div class="summary-total"><span>{{ t('应付（预览）') }}</span><strong>{{ discountError ? '—' : money(total) }}</strong></div></div>
      <label class="form-group">{{ t('修改原因') }}{{ priceChanged ? t('（必填）') : t('（选填，调价时必填）') }}<textarea v-model="reason" maxlength="256" :disabled="disabled" :placeholder="t('例如：客户调整商品或协商价格')"></textarea></label>
    </template>
    <div class="modal-actions"><button class="modal-cancel" type="button" :disabled="saving" @click="close">{{ t('取消') }}</button><button class="modal-save" type="button" :disabled="disabled || !order || Boolean(discountError)" :aria-busy="saving" @click="save">{{ saving ? t('保存中…') : t('保存修改') }}</button></div>
  </AppModal>
  <SellerOrderProductPicker v-if="productPickerOpen" :order-id="orderId" :gifts-only="locked" :unavailable-reason="unavailableReason" :addition-hint="additionHint" :money="value => money(cents(value))" @close="closeProductPicker" @confirm="add" />
</template>

<style scoped>
.seller-order-edit-items { margin: 20px 0; }
.seller-order-edit-row { display: grid; grid-template-columns: minmax(140px, 1fr) 115px 100px 110px 60px; gap: 14px; align-items: center; padding: 14px 0; border-bottom: 1px solid #e6ebe7; }
.seller-order-edit-product, .seller-order-edit-amount { display: grid; gap: 6px; overflow-wrap: anywhere; }
.seller-order-edit-product small, .seller-order-edit-amount small { color: #738078; }
.seller-order-edit-row .form-group { margin: 0; }
.seller-order-edit-row input { width: 100%; min-width: 0; }
.seller-order-edit-error { padding: 12px; margin: 12px 0; border-radius: 8px; color: #a82e38; background: #fff2f2; }
.seller-order-add-product { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 24px; }
.seller-order-add-product button { display: inline-flex; align-items: center; gap: 6px; }
.seller-order-add-product small { color: #6e6e73; }
.summary-lines { margin: 20px 0; }
@media (max-width: 650px) {
  .seller-order-edit-row { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
  .seller-order-edit-product { grid-column: 1 / -1; }
  .seller-order-edit-row > button { justify-self: end; }
}
</style>
