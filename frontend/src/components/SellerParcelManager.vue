<script setup lang="ts">
import { useSellerTranslation } from '../lib/seller-locale'
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import AppModal from './AppModal.vue'
import SellerSearchField from './SellerSearchField.vue'
import OrderParcelList from './OrderParcelList.vue'
import ParcelPhotoPicker from './ParcelPhotoPicker.vue'
import { ApiRequestError, orderParcelApi } from '../lib/api'
import type { OrderParcel, ParcelOrder, PackParcelInput, ShipParcelInput } from '../lib/api'
import { formatOrderCopyRows } from '../lib/order-copy-format'
import { MAX_ORDER_ATTACHMENT_BYTES, ORDER_IMAGE_MIME_TYPES } from '../lib/order-file-rules'

const t = useSellerTranslation()

const props = defineProps<{ orderId: number; formatDateTime: (value: unknown) => string }>()
const emit = defineEmits<{ close: []; updated: [order: ParcelOrder] }>()
const order = ref<ParcelOrder | null>(null)
const loading = ref(true)
const saving = ref(false)
const stale = ref(false)
const error = ref('')
const success = ref('')
const mode = ref<'view' | 'pack' | 'ship' | 'receive'>('view')
const confirmed = ref(false)
const receiveNote = ref('')
const showPhotoErrors = ref(false)
const showLabelErrors = ref(false)
const savingMessage = ref('')
const copyFeedback = ref('')
const copyFeedbackKind = ref<'success' | 'error'>('success')
let copyFeedbackTimer: number | undefined
const itemSearch = ref('')
const formScroll = ref<HTMLElement | null>(null)
type PackDraft = { uuid: string; selection: 'all' | 'partial'; quantities: Record<number, number | string>; files: File[]; note: string }
type ShipDraft = { parcel: OrderParcel; trackingNo: string; carrier: string; labels: File[]; note: string }
const activeDraft = ref<PackDraft | null>(null)
const shipping = ref<ShipDraft | null>(null)
const receiving = ref<OrderParcel | null>(null)
const active = computed(() => (order.value?.parcels || []).filter(parcel => parcel.status !== 'repacked'))
const isPacking = computed(() => mode.value === 'pack')
const available = computed(() => {
  const counts: Record<number, number> = {}
  for (const item of order.value?.items || []) counts[item.orderItemId] = item.quantity
  for (const parcel of active.value) for (const item of parcel.items) counts[item.orderItemId] = (counts[item.orderItemId] || 0) - item.quantity
  return counts
})
const currentItems = computed(() => (order.value?.items || []).filter(item => (available.value[item.orderItemId] || 0) > 0))
const filteredItems = computed(() => {
  const query = itemSearch.value.trim().toLowerCase()
  return currentItems.value.filter(item => !query || `${item.name} ${item.sku}`.toLowerCase().includes(query))
})
const currentParcelNo = computed(() => Math.max(0, ...(order.value?.parcels || []).map(parcel => parcel.parcelNo)) + 1)
const currentQuantity = computed(() => activeDraft.value ? Object.values(activeDraft.value.quantities).reduce<number>((sum, value) => sum + displayQuantity(value), 0) : 0)
const currentItemCount = computed(() => activeDraft.value ? Object.keys(activeDraft.value.quantities).length : 0)
const unallocatedQuantity = computed(() => currentItems.value.reduce((sum, item) => sum + Math.max(0, (available.value[item.orderItemId] || 0) - displayQuantity(activeDraft.value?.quantities[item.orderItemId])), 0))
const submitLabel = computed(() => saving.value ? mode.value === 'ship' ? '发货中…' : '保存中…' : isPacking.value ? '保存（仅此包）' : mode.value === 'ship' ? '发货' : '确认收货')
function permits(action: string) { return order.value?.allowedActions.includes(action) || false }
async function load() {
  if (saving.value) return
  loading.value = true; error.value = ''; stale.value = false; mode.value = 'view'; order.value = null
  activeDraft.value = null; shipping.value = null; receiving.value = null; success.value = ''
  try { order.value = await orderParcelApi.detail(props.orderId) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '订单加载失败。' }
  finally { loading.value = false }
}
onMounted(load)
onUnmounted(() => window.clearTimeout(copyFeedbackTimer))
function close() { if (!saving.value) emit('close') }
function displayQuantity(value: number | string | undefined) { return Number.isFinite(Number(value)) ? Math.max(0, Number(value)) : 0 }
function remaining(itemId: number) { return Math.max(0, available.value[itemId] || 0) }
function includesItem(draft: PackDraft, itemId: number) { return Object.prototype.hasOwnProperty.call(draft.quantities, itemId) }
function quantityError(draft: PackDraft, itemId: number) {
  const quantity = Number(draft.quantities[itemId])
  if (!Number.isSafeInteger(quantity) || quantity < 1) return '数量至少为 1，须为整数'
  if (quantity > remaining(itemId)) return `最多可装 ${remaining(itemId)} 件`
  return ''
}
function clearFormError() { if (!stale.value) error.value = '' }
function updatePackPhotos(draft: PackDraft, files: File[]) { draft.files = files; clearFormError() }
function updateLabels(draft: ShipDraft, files: File[]) { draft.labels = files; showLabelErrors.value = false; clearFormError() }
function focusField(id: string) {
  void nextTick(() => {
    const field = document.getElementById(id)
    field?.focus({ preventScroll: true })
    const list = field?.closest<HTMLElement>('.packing-item-list')
    if (list && field) {
      const bounds = list.getBoundingClientRect()
      const control = field.getBoundingClientRect()
      list.scrollBy({ top: control.top < bounds.top ? control.top - bounds.top : Math.max(0, control.bottom - bounds.bottom) })
    }
    if (formScroll.value && field) {
      const bounds = formScroll.value.getBoundingClientRect()
      const control = field.getBoundingClientRect()
      formScroll.value.scrollBy({ top: control.top < bounds.top ? control.top - bounds.top : Math.max(0, control.bottom - bounds.bottom) })
    }
  })
}
function toggleItem(draft: PackDraft, itemId: number) {
  if (includesItem(draft, itemId)) delete draft.quantities[itemId]
  else if (remaining(itemId) > 0) draft.quantities[itemId] = remaining(itemId)
  draft.selection = 'partial'; clearFormError()
}
function changeQuantity(draft: PackDraft) { draft.selection = 'partial'; clearFormError() }
function showCopyFeedback(message: string, kind: 'success' | 'error') {
  window.clearTimeout(copyFeedbackTimer)
  copyFeedbackKind.value = kind
  copyFeedback.value = message
  copyFeedbackTimer = window.setTimeout(() => { copyFeedback.value = '' }, 3000)
}
async function copyPackingDetails() {
  if (!order.value || !activeDraft.value) return
  const address = order.value.address
  const street = [address?.street, address?.apt].filter(value => value?.trim()).join(' ')
  const selectedItems = currentItems.value.filter(item => includesItem(activeDraft.value!, item.orderItemId))
  const lines = [
    t('订单号：{0}', [order.value.orderNo]),
    t('包裹编号：{0}', [currentParcelNo.value]),
    t('收件人名称：{0}', [address?.name || '']),
    t('联系电话：{0}', [address?.phone || '']),
    t('地址：{0}', [street]),
    t('城市：{0}', [address?.city || '']),
    t('州：{0}', [address?.region || '']),
    t('邮编：{0}', [address?.postalCode || '']),
    t('邮箱：{0}', [order.value.customer?.email || '']),
    '------------------------------------------------',
    ...formatOrderCopyRows(selectedItems.map(item => ({ sku: item.sku, quantity: activeDraft.value?.quantities[item.orderItemId] ?? '' })), t('产品代号'), t('数量')),
  ]
  try {
    await navigator.clipboard.writeText(lines.join('\n'))
    showCopyFeedback('复制成功', 'success')
  } catch {
    showCopyFeedback('复制失败，请检查浏览器剪贴板权限。', 'error')
  }
}
function setSelection(draft: PackDraft, selection: 'all' | 'partial') {
  if (draft.selection === selection) return
  draft.selection = selection
  const quantities: Record<number, number> = {}
  if (selection === 'all') for (const item of currentItems.value) quantities[item.orderItemId] = remaining(item.orderItemId)
  draft.quantities = quantities; itemSearch.value = ''; clearFormError()
}
function newParcelUuid() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
function scrollFormToTop() {
  void nextTick(() => {
    formScroll.value?.scrollTo({ top: 0 })
    formScroll.value?.querySelector('.packing-item-list')?.scrollTo({ top: 0 })
  })
}
function addParcel() {
  activeDraft.value = { uuid: newParcelUuid(), selection: 'partial', quantities: {}, files: [], note: '' }
  showPhotoErrors.value = false; itemSearch.value = ''; scrollFormToTop()
}
function beginPack() {
  mode.value = 'pack'; error.value = ''; success.value = ''
  if (!activeDraft.value) addParcel()
}
function revealPackingError() { itemSearch.value = ''; scrollFormToTop() }
function validatePacking() {
  const draft = activeDraft.value
  if (!draft || !Object.keys(draft.quantities).length) {
    revealPackingError()
    const first = currentItems.value[0]
    if (first && draft) focusField(`${draft.uuid}-${first.orderItemId}-select`)
    throw new Error('请勾选装入本包的商品，或选择“这一包装剩余全部”。')
  }
  for (const itemId of Object.keys(draft.quantities).map(Number)) {
    const message = quantityError(draft, itemId)
    if (message) {
      revealPackingError(); focusField(`${draft.uuid}-${itemId}`)
      const item = order.value?.items.find(line => line.orderItemId === itemId)
      throw new Error(`包裹 ${currentParcelNo.value} · ${item?.name || '商品'}：${message}。`)
    }
  }
  try { validateFiles(draft.files) }
  catch (cause) {
    showPhotoErrors.value = true; focusField('current-parcel-photos')
    throw cause
  }
}
function beginShip(id: number) {
  const parcel = active.value.find(p => p.id === id && p.status === 'packed')
  if (!parcel || !permits('ship_parcel')) return
  shipping.value = { parcel, trackingNo: '', carrier: '', labels: [], note: '' }
  mode.value = 'ship'; error.value = ''; success.value = ''; showLabelErrors.value = false; scrollFormToTop()
}
function beginReceive(id: number) {
  const parcel = active.value.find(p => p.id === id && p.status === 'shipped')
  if (!parcel || !permits('receive_parcel')) return
  receiving.value = parcel; confirmed.value = false; receiveNote.value = ''; mode.value = 'receive'; error.value = ''; success.value = ''
}
function validateFiles(files: File[], label = false) {
  if (!files.length && !label) throw new Error('请添加至少一张打包图片。')
  if (files.length > (label ? 10 : 1)) throw new Error(label ? '面单最多添加 10 个附件。' : '每个包裹只能添加一张打包图片。')
  if (files.some(f => f.size > MAX_ORDER_ATTACHMENT_BYTES || f.size === 0)) throw new Error('单个文件应为 1 字节至 15 MiB。')
  const allowedTypes = new Set<string>([...ORDER_IMAGE_MIME_TYPES, ...(label ? ['application/pdf'] : [])])
  if (files.some(f => !allowedTypes.has(f.type))) throw new Error(label ? '面单仅支持 JPG、PNG、GIF、BMP 或 PDF。' : '打包图片仅支持 JPG、PNG、GIF 或 BMP。')
}
function validate() {
  if (!order.value) throw new Error('请先加载订单。')
  if (isPacking.value) validatePacking()
  else if (mode.value === 'ship') {
    const draft = shipping.value
    if (!draft) throw new Error('请打开需要发货的包裹。')
    if (!draft.trackingNo.trim() || draft.trackingNo.trim().length > 128) { focusField('shipping-tracking'); throw new Error('请填写运单号，最多 128 字。') }
    if (draft.carrier.length > 64) throw new Error('承运商最多 64 字。')
    try { validateFiles(draft.labels, true) }
    catch (cause) { showLabelErrors.value = true; focusField('shipping-labels'); throw cause }
  } else if (!confirmed.value) throw new Error('请勾选确认后提交。')
}
async function submit(continuePacking = false) {
  if (saving.value || stale.value || !order.value) return
  error.value = ''; success.value = ''
  try { validate() } catch (cause) { error.value = cause instanceof Error ? cause.message : '请检查表单。'; return }
  saving.value = true
  const currentMode = mode.value
  try {
    let result: ParcelOrder
    if (currentMode === 'pack' && activeDraft.value) {
      const draft = activeDraft.value
      savingMessage.value = `正在上传包裹 ${currentParcelNo.value} 的打包图片…`
      const files = await Promise.all(draft.files.map(file => orderParcelApi.upload(props.orderId, draft.uuid, 'packed', file)))
      const parcel: PackParcelInput = { parcelUuid: draft.uuid, items: Object.entries(draft.quantities).map(([id, quantity]) => ({ orderItemId: Number(id), quantity: Number(quantity) })), files, publicNote: draft.note.trim() || null }
      savingMessage.value = '正在保存包裹…'
      result = await orderParcelApi.pack(props.orderId, [parcel], order.value.updatedAt)
    } else if (currentMode === 'ship' && shipping.value) {
      const draft = shipping.value
      if (draft.labels.length) savingMessage.value = '正在上传快递面单…'
      const labelFiles = await Promise.all(draft.labels.map(file => orderParcelApi.upload(props.orderId, draft.parcel.parcelUuid, 'shipped', file)))
      const parcel: ShipParcelInput = { parcelId: draft.parcel.id, trackingNo: draft.trackingNo.trim(), carrier: draft.carrier.trim() || null, labelFiles, publicNote: draft.note.trim() || null }
      savingMessage.value = '正在发货…'
      result = await orderParcelApi.ship(props.orderId, [parcel], order.value.updatedAt)
    } else if (currentMode === 'receive' && receiving.value) {
      result = await orderParcelApi.receive(props.orderId, [receiving.value.id], receiveNote.value.trim() || null, order.value.updatedAt)
    } else { throw new Error('请先选择有效的包裹操作。') }
    const savedParcelNo = currentMode === 'pack' ? result.parcels.find(parcel => parcel.parcelUuid === activeDraft.value?.uuid)?.parcelNo : null
    order.value = result; mode.value = 'view'; emit('updated', result)
    if (currentMode === 'pack') activeDraft.value = null
    shipping.value = null; receiving.value = null
    if (currentMode === 'pack' && continuePacking && permits('pack_parcel')) { mode.value = 'pack'; addParcel() }
    success.value = ({ pack: `包裹 ${savedParcelNo || ''} 已保存${mode.value === 'pack' ? '，可继续打下一包' : ''}。`, ship: '包裹已发货。', receive: '包裹已收货。', view: '' })[currentMode]
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '操作失败。'
    if (!(cause instanceof ApiRequestError) || ['ORDER_CHANGED', 'PARCEL_ALREADY_EXISTS', 'INVALID_PARCEL_TRANSITION'].includes(cause.code)) stale.value = true
  } finally { saving.value = false; savingMessage.value = '' }
}
</script>

<template>
  <AppModal :label="t('包裹管理 {0}', [order?.orderNo || ''])" content-class="seller-modal parcel-manager-modal" @close="close">
    <div class="panel-header parcel-manager-header">
      <div>
        <h2 class="modal-title">{{ isPacking ? t('订单打包') : t('包裹管理') }}</h2>
        <p>{{ order?.orderNo || t('正在读取订单…') }}</p>
      </div>
      <div class="parcel-manager-header-actions">
        <button v-if="mode !== 'view'" class="link-button" type="button" :disabled="saving" @click="mode = 'view'; error = ''">{{ t('返回列表') }}</button>
        <button class="close-button" type="button" :aria-label="t('关闭包裹管理')" :disabled="saving" @click="close">×</button>
      </div>
    </div>
    <p v-if="loading" role="status">{{ t('正在加载包裹及商品…') }}</p>
    <p v-if="error" class="parcel-error" role="alert">{{ t(error) }}</p>
    <p v-if="success" class="parcel-success" role="status">{{ t(success) }}</p>
    <div v-if="stale || (!loading && !order)">
      <p>{{ t('请重新读取订单，核对已提交结果后再操作。') }}</p>
      <button type="button" class="btn btn-outline" :disabled="loading || saving" @click="load">{{ t('重新读取订单') }}</button>
    </div>
    <template v-if="order && !loading">
      <div class="parcel-summary">
        <span>{{ t('共') }} <strong>{{ order.fulfillmentSummary.parcelCount }}</strong> {{ t('包') }}</span>
        <span>{{ t('已发') }} <strong>{{ order.fulfillmentSummary.shippedParcelCount }}</strong> {{ t('包') }}</span>
        <span>{{ t('已收') }} <strong>{{ order.fulfillmentSummary.receivedParcelCount }}</strong> {{ t('包') }}</span>
        <span>{{ t('未打包') }} <strong>{{ order.fulfillmentSummary.unpackedQuantity }}</strong> {{ t('件') }}</span>
      </div>
      <div v-if="mode === 'view'" class="parcel-view">
        <div class="parcel-toolbar">
          <button v-if="permits('pack_parcel')" class="btn" type="button" :disabled="saving || stale" @click="beginPack()">{{ activeDraft ? t('继续本次打包') : active.length ? t('打包剩余商品') : t('开始打包') }}</button>
        </div>
        <OrderParcelList
          :parcels="order.parcels"
          seller
          :packing-info="{ orderNo: order.orderNo, address: order.address, customerEmail: order.customer?.email }"
          :show-actions="!['completed', 'cancelled'].includes(order.status)"
          :disabled="saving || stale"
          :format-date-time="formatDateTime"
          @ship="beginShip($event)"
          @receive="beginReceive($event)"
        />
      </div>
      <form v-else class="parcel-manager-form" novalidate :aria-busy="saving" @submit.prevent="submit()">
        <div ref="formScroll" class="parcel-form-scroll">
          <fieldset :disabled="saving || stale" class="parcel-form-fields">
            <template v-if="isPacking && activeDraft">
              <section class="packing-box" :aria-label="t('打包包裹 {0}', [currentParcelNo])">
                <header class="packing-box-heading">
                  <h3>{{ t('包裹') }} {{ currentParcelNo }}</h3>
                  <div class="packing-copy-actions">
                    <button class="packing-copy-button" type="button" :aria-label="t('复制订单信息')" :title="t('复制订单信息')" @click="copyPackingDetails">
                      <svg aria-hidden="true" viewBox="0 0 18 18"><rect x="6" y="3" width="9" height="11" rx="1"></rect><path d="M12 15H4a1 1 0 0 1-1-1V5"></path></svg>
                      <span>{{ t('复制订单') }}</span>
                    </button>
                    <span v-if="copyFeedback" :class="['packing-copy-feedback', `is-${copyFeedbackKind}`]" :role="copyFeedbackKind === 'error' ? 'alert' : 'status'">{{ t(copyFeedback) }}</span>
                  </div>
                </header>
                <section class="packing-address" :aria-label="t('收货地址')">
                  <h4>{{ t('收货地址') }}</h4>
                  <dl class="packing-address-grid">
                    <div><dt>{{ t('收件人') }}</dt><dd>{{ order.address?.name || '—' }}</dd></div>
                    <div><dt>{{ t('街道地址') }}</dt><dd>{{ order.address?.street || '—' }}</dd></div>
                    <div><dt>{{ t('公寓 / 门牌') }}</dt><dd>{{ order.address?.apt || '—' }}</dd></div>
                    <div><dt>{{ t('城市') }}</dt><dd>{{ order.address?.city || '—' }}</dd></div>
                    <div><dt>{{ t('州 / 地区') }}</dt><dd>{{ order.address?.region || '—' }}</dd></div>
                    <div><dt>{{ t('邮政编码') }}</dt><dd>{{ order.address?.postalCode || '—' }}</dd></div>
                    <div><dt>{{ t('电话') }}</dt><dd>{{ order.address?.phone || '—' }}</dd></div>
                    <div><dt>{{ t('电子邮箱') }}</dt><dd>{{ order.customer?.email || '—' }}</dd></div>
                  </dl>
                </section>
                <div class="packing-choice" role="group" :aria-label="t('本包装入范围')">
                  <button type="button" :class="{ active: activeDraft.selection === 'all' }" :aria-pressed="activeDraft.selection === 'all'" @click="setSelection(activeDraft, 'all')">{{ t('这一包装剩余全部') }}</button>
                  <button type="button" :class="{ active: activeDraft.selection === 'partial' }" :aria-pressed="activeDraft.selection === 'partial'" @click="setSelection(activeDraft, 'partial')">{{ t('只装一部分') }}</button>
                </div>
                <div v-if="currentItems.length > 5 || itemSearch" class="packing-item-search">
                  <SellerSearchField :disabled="saving || stale" @refresh="itemSearch = itemSearch.trim()"><input v-model="itemSearch" type="search" :aria-label="t('搜索待打包商品')" :placeholder="t('搜索商品名称或 SKU')"></SellerSearchField>
                  <span>{{ t('已选') }} {{ currentItemCount }} {{ t('种 ·') }} {{ currentQuantity }} {{ t('件') }}</span>
                </div>
                <ul class="packing-item-list">
                  <li
                    v-for="item in filteredItems"
                    :key="item.orderItemId"
                    :class="{ selected: includesItem(activeDraft, item.orderItemId) }"
                  >
                    <label class="packing-item-selector">
                      <input
                        :id="`${activeDraft.uuid}-${item.orderItemId}-select`"
                        type="checkbox"
                        :checked="includesItem(activeDraft, item.orderItemId)"
                        :aria-label="t('装入 {0}（{1}）', [item.name, item.sku])"
                        @change="toggleItem(activeDraft, item.orderItemId)"
                      >
                      <span>
                        <strong>{{ item.name }}</strong>
                        <em v-if="item.productType === 'gift'" class="packing-gift">{{ t('赠品') }}</em>
                        <small>{{ item.sku }} <span>{{ t('· 可装') }} {{ remaining(item.orderItemId) }} {{ t('件') }}</span></small>
                      </span>
                    </label>
                    <div v-if="includesItem(activeDraft, item.orderItemId)" class="packing-item-quantity">
                      <label>
                        <span class="sr-only">{{ item.name }} {{ t('的本包数量') }}</span>
                        <input
                          :id="`${activeDraft.uuid}-${item.orderItemId}`"
                          v-model.number="activeDraft.quantities[item.orderItemId]"
                          type="number"
                          inputmode="numeric"
                          min="1"
                          :max="remaining(item.orderItemId)"
                          step="1"
                          :class="{ 'quantity-invalid': quantityError(activeDraft, item.orderItemId) }"
                          :aria-invalid="!!quantityError(activeDraft, item.orderItemId)"
                          :aria-describedby="quantityError(activeDraft, item.orderItemId) ? `${activeDraft.uuid}-${item.orderItemId}-error` : undefined"
                          @input="changeQuantity(activeDraft)"
                          @focus="($event.target as HTMLInputElement).select()"
                        >
                        <span>{{ t('件') }}</span>
                      </label>
                      <small v-if="quantityError(activeDraft, item.orderItemId)" :id="`${activeDraft.uuid}-${item.orderItemId}-error`" class="quantity-error">{{ t(quantityError(activeDraft, item.orderItemId)) }}</small>
                    </div>
                    <span v-else class="packing-item-remaining">{{ remaining(item.orderItemId) }} {{ t('件') }}</span>
                  </li>
                  <li v-if="!filteredItems.length" class="packing-search-empty">{{ itemSearch ? t('没有匹配的商品，请调整搜索条件。') : t('暂无待打包商品。') }}</li>
                </ul>
                <div id="current-parcel-photos" class="packing-box-photos" tabindex="-1">
                  <ParcelPhotoPicker
                    :key="activeDraft.uuid"
                    :model-value="activeDraft.files"
                    :label="t('本包打包图片')"
                    compact
                    :max-files="1"
                    allow-camera
                    :disabled="saving || stale"
                    :invalid="showPhotoErrors"
                    @update:model-value="updatePackPhotos(activeDraft, $event)"
                  />
                </div>
                <details :key="`${activeDraft.uuid}-note`" class="packing-note" :open="activeDraft.note ? true : undefined">
                  <summary>{{ t('添加买家可见备注') }} <span>{{ t('选填') }}</span></summary>
                  <label class="sr-only" :for="`${activeDraft.uuid}-note`">{{ t('包裹') }} {{ currentParcelNo }} {{ t('的买家可见备注') }}</label>
                  <textarea :id="`${activeDraft.uuid}-note`" v-model="activeDraft.note" maxlength="256" :placeholder="t('例如：本包包含的配件或装箱说明')"></textarea>
                </details>
              </section>
              <p v-if="!order.fulfillmentSummary.started" class="packing-operation-note">{{ t('保存首个包裹后，订单商品、地址和取消操作将锁定。') }}</p>
            </template>
            <template v-else-if="mode === 'ship' && shipping">
              <article class="parcel-draft">
                <h3>{{ t('发货 · 包裹') }} {{ shipping.parcel.parcelNo }}</h3>
                <p>{{ shipping.parcel.items.map(item => `${item.name} × ${item.quantity}`).join('；') }}</p>
                <label class="form-group"><span>{{ t('承运商') }} <span class="shipping-optional">{{ t('选填') }}</span></span><input v-model="shipping.carrier" maxlength="64" autocomplete="off"></label>
                <label class="form-group"><span><span class="shipping-required" aria-hidden="true">*</span> {{ t('运单号') }}</span><input id="shipping-tracking" v-model="shipping.trackingNo" maxlength="128" required autocomplete="off"></label>
                <div id="shipping-labels" class="shipping-label-input" tabindex="-1">
                  <ParcelPhotoPicker
                    :model-value="shipping.labels"
                    :label="t('快递面单')"
                    allow-pdf
                    optional
                    :invalid="showLabelErrors"
                    :disabled="saving || stale"
                    @update:model-value="updateLabels(shipping, $event)"
                  />
                </div>
                <label class="form-group">{{ t('买家可见备注') }}<textarea v-model="shipping.note" maxlength="256"></textarea></label>
              </article>
            </template>
            <template v-else-if="mode === 'receive'">
              <h3>{{ t('确认包裹收货') }}</h3>
              <p>{{ t('包裹') }} {{ receiving?.parcelNo }}</p>
              <label class="checkbox-field"><input v-model="confirmed" type="checkbox" required> {{ t('确认本包裹已经实际收货') }}</label>
              <label class="form-group">{{ t('买家可见备注') }}<textarea v-model="receiveNote" maxlength="256"></textarea></label>
            </template>
          </fieldset>
        </div>
        <div class="modal-actions parcel-manager-actions">
          <div v-if="isPacking" class="packing-submit-summary">
            <strong>{{ t('本包 {0} 种 · {1} 件', [currentItemCount, currentQuantity]) }}</strong>
            <span v-if="saving" role="status">{{ t(savingMessage) }}</span>
            <span v-else>{{ unallocatedQuantity ? t('还有 {0} 件留待后续打包', [unallocatedQuantity]) : t('全部剩余商品已装入包裹') }}</span>
          </div>
          <span v-if="!isPacking && saving" class="parcel-saving" role="status">{{ t(savingMessage) }}</span>
          <div class="parcel-manager-action-buttons">
            <button v-if="!isPacking" class="modal-cancel" type="button" :disabled="saving" @click="mode = 'view'; error = ''">{{ t('返回包裹列表') }}</button>
            <button class="modal-save" type="submit" :disabled="saving || stale">{{ t(submitLabel) }}</button>
            <button v-if="isPacking" class="modal-cancel packing-save-next" type="button" :disabled="saving || stale || !unallocatedQuantity" :title="!unallocatedQuantity ? t('全部剩余商品已装入包裹，无需新增包裹') : undefined" @click="submit(true)">{{ t('保存，继续下一包') }}</button>
          </div>
        </div>
      </form>
    </template>
  </AppModal>
</template>

<style scoped>
.parcel-manager-header { flex: 0 0 auto; align-items: flex-start; margin-bottom: 2px; }
.parcel-manager-header .modal-title { margin: 0; font-size: 22px; }
.parcel-manager-header p { margin: 6px 0 0; color: var(--color-muted); font-size: 12px; overflow-wrap: anywhere; }
.parcel-summary, .parcel-toolbar { display: flex; flex-wrap: wrap; gap: 8px 18px; margin: 16px 0; }
.parcel-summary { flex: 0 0 auto; padding: 12px 0; border-top: 1px solid var(--color-line); border-bottom: 1px solid var(--color-line); color: var(--color-muted); font-size: 12px; }
.parcel-summary strong { color: var(--color-ink); font-variant-numeric: tabular-nums; }
.parcel-toolbar { margin-top: 0; gap: 8px; }
.parcel-error { flex: 0 0 auto; margin: 10px 0; color: var(--color-danger); background: var(--color-danger-soft); padding: 10px 12px; border-radius: 6px; font-size: 12px; line-height: 1.5; }
.parcel-success { color: var(--color-success); font-size: 13px; }
.parcel-view { min-height: 0; overflow-y: auto; }
.parcel-intro { color: var(--color-muted); font-size: 13px; line-height: 1.6; }
.parcel-manager-form { display: flex; flex-direction: column; min-height: 0; overflow: hidden; }
.parcel-form-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding-bottom: 2px; }
.parcel-form-fields { min-width: 0; margin: 0; padding: 0 2px; border: 0; }
.parcel-draft p { color: var(--color-muted); line-height: 1.6; font-size: 13px; }
.parcel-draft { border: 1px solid var(--color-line); padding: 16px; border-radius: 8px; margin: 0 0 14px; }
.parcel-draft h3 { font-size: 15px; margin: 0 0 10px; }
.shipping-label-input { margin: 18px 0 20px; }
.shipping-label-input:focus { outline: none; }
.shipping-required { color: var(--color-danger); }
.shipping-optional { color: var(--color-muted); font-size: 12px; font-weight: 400; }
.parcel-saving { color: var(--color-muted); font-size: 12px; }
.parcel-manager-header-actions { display: flex; align-items: center; gap: 14px; }
.parcel-manager-header-actions .link-button { white-space: nowrap; font-size: 12px; }
.packing-box { border: 1px solid var(--color-line); border-radius: 10px; padding: 18px; }
.packing-box-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 18px; }
.packing-box-heading h3 { margin: 0; font-size: 16px; font-weight: 600; }
.packing-copy-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; }
.packing-copy-button { display: inline-flex; flex: 0 0 auto; align-items: center; justify-content: center; gap: 6px; min-height: 34px; padding: 6px 10px; border: 1px solid var(--color-line-strong); border-radius: 7px; background: var(--color-surface); color: var(--color-ink); font-size: 12px; cursor: pointer; }
.packing-copy-button svg { width: 15px; height: 15px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.4; }
.packing-copy-feedback { font-size: 12px; }
.packing-copy-feedback.is-success { color: var(--color-success); }
.packing-copy-feedback.is-error { color: var(--color-danger); }
.packing-address { margin: 0 0 18px; padding: 13px 15px; border: 1px solid var(--color-line); border-radius: 8px; background: var(--color-page); }
.packing-address h4 { margin: 0 0 10px; color: var(--color-muted); font-size: 12px; font-weight: 600; }
.packing-address-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 18px; margin: 0; }
.packing-address-grid > div { display: grid; grid-template-columns: 88px minmax(0, 1fr); gap: 8px; min-width: 0; }
.packing-address-grid dt, .packing-address-grid dd { margin: 0; font-size: 12px; line-height: 1.5; }
.packing-address-grid dt { color: var(--color-muted); }
.packing-address-grid dd { min-width: 0; color: var(--color-ink); overflow-wrap: anywhere; }
.packing-choice { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; padding: 5px; border-radius: 8px; background: var(--color-page); }
.packing-choice button { min-height: 40px; padding: 7px 12px; border: 1px solid transparent; border-radius: 6px; background: transparent; color: var(--color-muted); font-size: 13px; font-weight: 500; }
.packing-choice button.active { border-color: var(--color-primary); background: var(--color-surface); color: var(--color-primary); }
.packing-item-search { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.packing-item-search > .seller-search-field { flex: 1; }
.packing-item-search input { flex: 1; min-width: 0; }
.packing-item-search > span { flex: 0 0 auto; color: var(--color-muted); font-size: 11px; }
.packing-item-list { max-height: min(32vh, 300px); margin: 0; padding: 0; overflow-y: auto; list-style: none; border-top: 1px solid var(--color-line); border-bottom: 1px solid var(--color-line); }
.packing-item-list > li { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 64px; padding: 12px 8px; border-bottom: 1px solid var(--color-line); }
.packing-item-list > li:last-child { border-bottom: 0; }
.packing-item-list > li.selected { background: var(--color-info-soft); }
.packing-item-selector { display: flex; flex: 1; min-width: 0; align-items: center; gap: 12px; cursor: pointer; }
.packing-item-selector input { flex: 0 0 auto; width: 17px; height: 17px; margin: 0; accent-color: var(--color-primary); }
.packing-item-selector > span { min-width: 0; }
.packing-item-selector strong { color: var(--color-ink); font-size: 13px; font-weight: 500; line-height: 1.5; overflow-wrap: anywhere; }
.packing-item-selector small { display: block; margin-top: 4px; color: var(--color-muted); font-size: 11px; overflow-wrap: anywhere; }
.packing-item-selector small > span { white-space: nowrap; }
.packing-item-quantity { flex: 0 0 auto; width: 116px; text-align: right; }
.packing-item-quantity label { display: flex; align-items: center; justify-content: flex-end; gap: 7px; color: var(--color-muted); font-size: 12px; }
.packing-item-quantity input { width: 80px; min-height: 34px; padding: 5px 8px; border: 1px solid var(--color-line-strong); border-radius: 6px; background: var(--color-surface); color: var(--color-ink); font-size: 13px; text-align: center; }
.packing-item-quantity input.quantity-invalid { border-color: var(--color-danger); background: var(--color-danger-soft); }
.quantity-error { display: block; margin-top: 5px; color: var(--color-danger); font-size: 11px; font-weight: 400; line-height: 1.4; }
.packing-item-remaining { flex: 0 0 auto; color: var(--color-muted); font-size: 12px; }
.packing-item-list > li.packing-search-empty { justify-content: center; padding: 22px 12px; color: var(--color-muted); font-size: 12px; text-align: center; }
.packing-gift { display: inline-block; margin-left: 6px; padding: 2px 5px; border-radius: 4px; background: var(--color-page); color: var(--color-muted); font-size: 10px; font-style: normal; font-weight: 400; }
.packing-box-photos { margin-top: 20px; }
.packing-box-photos:focus { outline: none; }
.packing-operation-note { margin: 14px 0 0; color: var(--color-muted); font-size: 11px; line-height: 1.6; }
.packing-note { margin-top: 18px; font-size: 12px; }
.packing-note summary { color: var(--color-primary); cursor: pointer; }
.packing-note summary span { margin-left: 5px; color: var(--color-muted); font-size: 11px; }
.packing-note textarea { width: 100%; min-height: 76px; margin-top: 10px; padding: 10px; border: 1px solid var(--color-line-strong); border-radius: 7px; resize: vertical; }
.parcel-manager-actions { flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 18px; }
.packing-submit-summary { display: grid; gap: 6px; font-size: 12px; }
.packing-submit-summary strong { color: var(--color-ink); font-weight: 550; }
.packing-submit-summary span { color: var(--color-muted); font-size: 11px; }
.parcel-manager-action-buttons { display: flex; flex: 0 0 auto; flex-wrap: wrap; justify-content: flex-end; gap: 8px; margin-left: auto; }
@media (max-width: 600px) {
  .parcel-manager-actions { align-items: stretch; flex-direction: column; gap: 12px; }
  .parcel-manager-action-buttons { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin-left: 0; }
  .parcel-manager-action-buttons button { padding: 0 10px; }
  .parcel-manager-header .modal-title { font-size: 19px; }
  .parcel-summary { gap: 8px 12px; margin: 12px 0; padding: 8px 0; }
  .packing-box { padding: 12px; }
  .packing-box-heading { margin-bottom: 12px; }
  .packing-address { margin-bottom: 14px; padding: 11px; }
  .packing-address-grid { grid-template-columns: minmax(0, 1fr); gap: 7px; }
  .packing-address-grid > div { grid-template-columns: 88px minmax(0, 1fr); gap: 8px; }
  .packing-choice button { min-height: 38px; padding: 7px 4px; font-size: 12px; }
  .packing-item-list > li { gap: 8px; padding: 10px 5px; }
  .packing-item-selector { gap: 8px; }
  .packing-item-selector strong { font-size: 12px; }
  .packing-item-quantity { width: 83px; }
  .packing-item-quantity input { width: 60px; }
  .packing-item-search { align-items: stretch; flex-direction: column; gap: 7px; }
  .packing-item-list { max-height: 34vh; }
  .parcel-draft { padding: 12px; }
}
</style>
