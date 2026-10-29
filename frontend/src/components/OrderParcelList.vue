<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useSellerTranslation } from '../lib/seller-locale'
import AppModal from './AppModal.vue'
import type { OrderAddressSnapshot, OrderParcel, ParcelEvent } from '../lib/api'

const sellerText = useSellerTranslation()

type PackingInfo = { orderNo: string; address?: OrderAddressSnapshot; customerEmail?: string }

const props = withDefaults(defineProps<{
  parcels: OrderParcel[]
  archivedView?: boolean
  seller?: boolean
  showActions?: boolean
  disabled?: boolean
  packingInfo?: PackingInfo
  t?: (text: string, values?: readonly unknown[]) => string
  formatDateTime: (value: unknown) => string
}>(), { seller: false, showActions: false, disabled: false })
const emit = defineEmits<{
  ship: [id: number]
  receive: [id: number]
  copyValue: [value: string, successMessage: string]
}>()
const preview = ref<{ url: string; label: string } | null>(null)
const copyMessage = ref('')
const packedOrderCopyFeedback = ref<{ parcelId: number; message: string; kind: 'success' | 'error' } | null>(null)
let packedOrderCopyTimer: number | undefined
const unavailable = ref(new Set<string>())
const active = computed(() => props.parcels.filter(p => props.archivedView || p.status !== 'repacked'))
const archived = computed(() => props.archivedView ? [] : props.parcels.filter(p => p.status === 'repacked'))
onUnmounted(() => window.clearTimeout(packedOrderCopyTimer))
function translate(text: string, values?: readonly unknown[]) { return props.t ? props.t(text, values) : sellerText(text, values) }
function statusLabel(status: string) { return translate(({ packed: 'Packed', shipped: 'Shipped', received: 'Received', repacked: 'Repacked archive' } as Record<string, string>)[status] || status) }
async function copyTracking(value: string) {
  if (!props.seller) {
    emit('copyValue', value, 'Tracking number copied.')
    return
  }
  try {
    await navigator.clipboard.writeText(value)
    if (props.seller) copyMessage.value = translate('Copied')
  } catch {
    if (props.seller) copyMessage.value = translate('Copy failed; select the number to copy')
  }
}
async function copyPackedOrder(parcel: OrderParcel) {
  const info = props.packingInfo
  if (!info) return
  const address = info.address
  const street = [address?.street, address?.apt].filter(value => value?.trim()).join(' ')
  const lines = [
    translate('订单号：{0}', [info.orderNo]),
    translate('包裹编号：{0}', [parcel.parcelNo]),
    translate('收件人名称：{0}', [address?.name || '']),
    translate('联系电话：{0}', [address?.phone || '']),
    translate('地址：{0}', [street]),
    translate('城市：{0}', [address?.city || '']),
    translate('州：{0}', [address?.region || '']),
    translate('邮编：{0}', [address?.postalCode || '']),
    translate('邮箱：{0}', [info.customerEmail || '']),
    '------------------------------------------------',
    translate('产品代号\t数量'),
    ...parcel.items.map(item => `${item.sku}\t${item.quantity}`),
  ]
  window.clearTimeout(packedOrderCopyTimer)
  packedOrderCopyFeedback.value = null
  try {
    await navigator.clipboard.writeText(lines.join('\n'))
    packedOrderCopyFeedback.value = { parcelId: parcel.id, message: '复制成功', kind: 'success' }
  } catch {
    packedOrderCopyFeedback.value = { parcelId: parcel.id, message: '复制失败，请检查浏览器剪贴板权限。', kind: 'error' }
  }
  packedOrderCopyTimer = window.setTimeout(() => { packedOrderCopyFeedback.value = null }, 3000)
}
function images(event: ParcelEvent) { return event.files.flatMap(uuid => event.signedFileUrls[uuid] ? [{ uuid, url: event.signedFileUrls[uuid] }] : []) }
</script>

<template>
  <div class="parcel-list"><p v-if="seller && copyMessage" role="status" class="parcel-empty">{{ translate(copyMessage) }}</p>
    <p v-if="!active.length" class="parcel-empty">{{ translate('No parcels yet') }}</p>
    <article v-for="parcel in active" :key="parcel.id" class="parcel-card">
      <header class="parcel-heading">
        <strong>{{ translate('Parcel') }} {{ parcel.parcelNo }}</strong><span class="parcel-state">{{ statusLabel(parcel.status) }}</span>
        <div v-if="packingInfo || showActions" class="parcel-heading-actions">
          <button v-if="packingInfo" class="parcel-copy-order" type="button" :disabled="disabled" :aria-label="translate('复制订单信息')" :title="translate('复制订单信息')" @click="copyPackedOrder(parcel)"><svg aria-hidden="true" viewBox="0 0 18 18"><rect x="6" y="3" width="9" height="11" rx="1"></rect><path d="M12 15H4a1 1 0 0 1-1-1V5"></path></svg><span>{{ translate('复制订单') }}</span></button>
          <span v-if="packedOrderCopyFeedback?.parcelId === parcel.id" :class="['parcel-copy-feedback', `is-${packedOrderCopyFeedback.kind}`]" :role="packedOrderCopyFeedback.kind === 'error' ? 'alert' : 'status'">{{ translate(packedOrderCopyFeedback.message) }}</span>
          <div v-if="showActions" class="parcel-actions"><button v-if="parcel.status === 'packed'" type="button" :disabled="disabled" @click="emit('ship', parcel.id)">{{ translate('Ship') }}</button><button v-if="parcel.status === 'shipped'" type="button" :disabled="disabled" @click="emit('receive', parcel.id)">{{ translate('Confirm received') }}</button></div>
        </div>
      </header>
      <section v-if="packingInfo" class="parcel-address" :aria-label="translate('收货地址')">
        <h4>{{ translate('收货地址') }}</h4>
        <dl>
          <div><dt>{{ translate('收件人') }}</dt><dd>{{ packingInfo.address?.name || '—' }}</dd></div>
          <div><dt>{{ translate('街道地址') }}</dt><dd>{{ packingInfo.address?.street || '—' }}</dd></div>
          <div><dt>{{ translate('公寓 / 门牌') }}</dt><dd>{{ packingInfo.address?.apt || '—' }}</dd></div>
          <div><dt>{{ translate('城市') }}</dt><dd>{{ packingInfo.address?.city || '—' }}</dd></div>
          <div><dt>{{ translate('州 / 地区') }}</dt><dd>{{ packingInfo.address?.region || '—' }}</dd></div>
          <div><dt>{{ translate('邮政编码') }}</dt><dd>{{ packingInfo.address?.postalCode || '—' }}</dd></div>
          <div><dt>{{ translate('电话') }}</dt><dd>{{ packingInfo.address?.phone || '—' }}</dd></div>
          <div><dt>{{ translate('电子邮箱') }}</dt><dd>{{ packingInfo.customerEmail || '—' }}</dd></div>
        </dl>
      </section>
      <section class="parcel-items" :aria-label="translate('Items')">
        <div class="parcel-items-heading"><strong>{{ translate('Items') }}</strong><span>{{ translate('Quantity') }}</span></div>
        <ul>
          <li v-for="item in parcel.items" :key="item.orderItemId">
            <div class="parcel-item-description">
              <div class="parcel-item-name"><strong>{{ item.name }}</strong><em v-if="item.productType === 'gift'" class="parcel-gift">{{ translate('Gift') }}</em></div>
              <small>{{ translate('SKU') }}：{{ item.sku }}</small>
            </div>
            <div class="parcel-item-quantity"><strong>{{ item.quantity }}</strong></div>
          </li>
        </ul>
      </section>
      <div class="parcel-tracking">
        <span>{{ parcel.carrier || '' }} {{ parcel.trackingNo || translate('Tracking number not recorded') }}</span>
        <button v-if="parcel.trackingNo" :class="seller ? 'parcel-copy' : 'detail-copy-button parcel-copy-icon'" type="button" :aria-label="translate('Copy tracking number')" :title="translate('Copy tracking number')" @click="copyTracking(parcel.trackingNo)">
          <template v-if="seller">{{ translate('Copy tracking number') }}</template>
          <svg v-else viewBox="0 0 18 18" aria-hidden="true"><rect x="6" y="6" width="9" height="11" rx="1.2"/><path d="M12 6V4.5A1.5 1.5 0 0 0 10.5 3h-7A1.5 1.5 0 0 0 2 4.5v8A1.5 1.5 0 0 0 3.5 14H6"/></svg>
        </button>
        <a v-if="parcel.trackingNo" :href="`https://t.17track.net/en#nums=${encodeURIComponent(parcel.trackingNo)}`" target="_blank" rel="noopener noreferrer" :aria-label="`${translate('Track parcel')} ${parcel.parcelNo}`">{{ translate('Track parcel') }}</a>
      </div>
      <div class="parcel-events">
        <div v-for="(event, index) in parcel.events" :key="index" class="parcel-event">
          <div><strong>{{ statusLabel(event.toStatus || '') }}</strong><time>{{ event.occurredAt ? formatDateTime(event.occurredAt) : translate('Time not recorded') }}</time><span v-if="seller && event.actorName">{{ event.actorName }}</span></div>
          <p v-if="event.note">{{ event.note }}</p>
          <div v-if="images(event).length" class="parcel-images"><button v-for="file in images(event)" :key="file.uuid" type="button" :disabled="unavailable.has(file.url)" :aria-label="translate('Fulfillment image')" @click="preview = { url: file.url, label: translate('Fulfillment image') }"><span v-if="unavailable.has(file.url)">{{ translate('Image unavailable') }}</span><img v-else :src="file.url" :alt="translate('Fulfillment image')" loading="lazy" @error="unavailable.add(file.url)"></button></div>
          <div v-if="event.labels?.length" class="parcel-labels">
            <strong>{{ translate('Shipping label') }}</strong>
            <div class="parcel-images">
              <template v-for="(file, labelIndex) in event.labels" :key="file.fileUuid">
                <a v-if="file.mediaType === 'application/pdf'" class="parcel-pdf-label" :href="file.url" target="_blank" rel="noopener noreferrer" :aria-label="`${translate('Shipping label')} ${labelIndex + 1} PDF`"><span>PDF</span><small>{{ labelIndex + 1 }}</small></a>
                <button v-else type="button" :disabled="unavailable.has(file.url)" :aria-label="`${translate('Shipping label')} ${labelIndex + 1}`" @click="preview = { url: file.url, label: translate('Shipping label') }"><span v-if="unavailable.has(file.url)">{{ translate('Image unavailable') }}</span><img v-else :src="file.url" :alt="`${translate('Shipping label')} ${labelIndex + 1}`" loading="lazy" @error="unavailable.add(file.url)"></button>
              </template>
            </div>
          </div>
        </div>
      </div>
    </article>
    <details v-if="archived.length" class="parcel-archive"><summary>{{ translate('Repacked archive') }} ({{ archived.length }})</summary><OrderParcelList :parcels="archived" archived-view :seller="seller" :packing-info="packingInfo" :t="t" :format-date-time="formatDateTime" @copy-value="(value, message) => emit('copyValue', value, message)" /></details>
    <AppModal v-if="preview" @keydown.stop :label="preview.label" content-class="parcel-image-modal" @close="preview = null"><img class="parcel-full-image" :src="preview.url" :alt="preview.label"></AppModal>
  </div>
</template>

<style scoped>
.parcel-list { display: grid; gap: 12px; min-width: 0; }
.parcel-card { border: 1px solid var(--color-border, #dce7e1); border-radius: 10px; padding: 14px; background: var(--color-surface, #fff); min-width: 0; }
.parcel-heading, .parcel-tracking, .parcel-event > div:first-child { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; }
.parcel-heading-actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 8px; margin-left: auto; }
.parcel-copy-order { display: inline-flex; align-items: center; gap: 5px; min-height: 30px; padding: 4px 8px; border: 1px solid #d1ded6; border-radius: 6px; background: var(--color-surface, #fff); color: #285e45; cursor: pointer; font-size: 12px; }
.parcel-copy-order:disabled { opacity: .55; cursor: not-allowed; }
.parcel-copy-order svg { width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.4; }
.parcel-copy-feedback { font-size: 12px; }
.parcel-copy-feedback.is-success { color: var(--color-success); }
.parcel-copy-feedback.is-error { color: var(--color-danger); }
.parcel-copy { border: 0; padding: 0; background: transparent; color: #286347; cursor: pointer; font-size: 12px; }
.parcel-copy-icon { padding: 0; cursor: pointer; }
.parcel-state { background: #edf5f0; color: #3d6852; border-radius: 5px; padding: 3px 7px; font-size: 12px; }
.parcel-actions { display: flex; gap: 8px; }
.parcel-actions button { border: 1px solid #d1ded6; border-radius: 5px; padding: 4px 8px; background: #fff; color: #285e45; cursor: pointer; }
.parcel-address { margin: 10px 0 12px; padding: 11px 12px; border: 1px solid var(--color-border, #dce7e1); border-radius: 8px; background: var(--color-page, #f7f9f7); }
.parcel-address h4 { margin: 0 0 8px; color: #718074; font-size: 11px; font-weight: 600; }
.parcel-address dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 14px; margin: 0; }
.parcel-address dl > div { display: grid; grid-template-columns: 82px minmax(0, 1fr); gap: 7px; min-width: 0; }
.parcel-address dt, .parcel-address dd { margin: 0; font-size: 11px; line-height: 1.5; }
.parcel-address dt { color: #718074; }
.parcel-address dd { min-width: 0; color: var(--color-ink, #24322b); overflow-wrap: anywhere; }
.parcel-items { margin: 12px 0; overflow: hidden; border: 1px solid var(--color-border, #dce7e1); border-radius: 8px; background: var(--color-surface, #fff); }
.parcel-items-heading, .parcel-items li { display: grid; grid-template-columns: minmax(0, 1fr) 68px; align-items: center; gap: 12px; }
.parcel-items-heading { padding: 8px 11px; border-bottom: 1px solid var(--color-border, #dce7e1); background: var(--color-page, #f7f9f7); color: #718074; font-size: 10px; font-weight: 600; }
.parcel-items-heading > span { text-align: right; }
.parcel-items ul { margin: 0; padding: 0; list-style: none; }
.parcel-items li { min-width: 0; padding: 9px 11px; border-bottom: 1px solid #edf0ed; }
.parcel-items li:last-child { border-bottom: 0; }
.parcel-item-description { min-width: 0; }
.parcel-item-name { display: flex; flex-wrap: wrap; align-items: center; gap: 5px 7px; }
.parcel-item-name > strong { min-width: 0; color: var(--color-ink, #24322b); font-size: 12px; font-weight: 600; line-height: 1.45; overflow-wrap: anywhere; }
.parcel-gift { flex: 0 0 auto; padding: 2px 5px; border-radius: 4px; background: #edf5f0; color: #3d6852; font-size: 10px; font-style: normal; line-height: 1.4; }
.parcel-item-description > small { display: block; margin-top: 3px; color: #788579; font-size: 10px; line-height: 1.4; overflow-wrap: anywhere; }
.parcel-item-quantity { display: flex; justify-content: flex-end; }
.parcel-item-quantity strong { display: inline-flex; min-width: 38px; justify-content: center; padding: 4px 7px; border-radius: 5px; background: var(--color-page, #f7f9f7); color: var(--color-ink, #24322b); font-size: 12px; font-variant-numeric: tabular-nums; }
.parcel-tracking { overflow-wrap: anywhere; font-size: 13px; }
.parcel-tracking a { color: var(--color-primary, #286347); text-decoration: underline; }
.parcel-events { margin-top: 12px; display: grid; gap: 10px; }
.parcel-event { padding: 8px 0 0; border-top: 1px solid #edf0ed; font-size: 12px; }
.parcel-event time, .parcel-event > div > span { color: #718074; }
.parcel-event p { margin: 5px 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.parcel-images { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.parcel-labels { margin-top: 8px; }
.parcel-labels > strong { color: #61786e; font-size: 12px; }
.parcel-images button { width: 68px; height: 68px; padding: 0; border: 1px solid #dbe4dc; border-radius: 6px; overflow: hidden; background: #f7f9f7; cursor: pointer; }
.parcel-images img { width: 100%; height: 100%; object-fit: cover; }
.parcel-images span { font-size: 10px; }
.parcel-images .parcel-pdf-label { display: grid; width: 68px; height: 68px; place-content: center; gap: 2px; border: 1px solid #dbe4dc; border-radius: 6px; background: #f7f9f7; color: #286347; text-align: center; text-decoration: none; }
.parcel-pdf-label span { font-size: 13px; font-weight: 700; }
.parcel-pdf-label small { font-size: 10px; }
.parcel-full-image { display: block; max-width: 100%; max-height: 75vh; margin: auto; }
.parcel-empty { color: #748074; margin: 4px 0; }
.parcel-archive summary { cursor: pointer; color: #758078; font-size: 12px; margin-bottom: 10px; }
@media (max-width: 480px) { .parcel-card { padding: 10px; } .parcel-heading-actions { margin-left: 0; width: 100%; justify-content: flex-start; } .parcel-address dl { grid-template-columns: minmax(0, 1fr); gap: 6px; } .parcel-items-heading, .parcel-items li { grid-template-columns: minmax(0, 1fr) 56px; gap: 8px; } .parcel-items-heading, .parcel-items li { padding-right: 9px; padding-left: 9px; } .parcel-actions { width: 100%; } }
</style>
