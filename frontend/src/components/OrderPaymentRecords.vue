<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { orderPaymentApi, type Area, type PaymentRecord, type PaymentRecordPage } from '../lib/api'
const props = defineProps<{ orderId: number; area: Area; t: (text: string) => string; money: (value: unknown) => string; formatDateTime: (value: unknown) => string }>()
const emit = defineEmits<{ copyValue: [value: string, successMessage: string] }>()
const records = ref<PaymentRecordPage>({ items: [], page: 1, total: 0, totalPages: 0 })
const loading = ref(false)
const error = ref('')
let generation = 0
function transactionId(record: PaymentRecord): string {
  if (props.area === 'admin') return record.transactionNo || record.originalTransactionNo || ''
  return (record.status === 'confirmed' || record.submissionId == null ? record.transactionNo : record.originalTransactionNo) || ''
}
onBeforeUnmount(() => { generation += 1 })
async function load(page = 1) {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await orderPaymentApi.records(props.area, props.orderId, page)
    if (current === generation) records.value = result
  } catch (value) {
    if (current === generation) error.value = value instanceof Error ? value.message : 'Request failed.'
  } finally { if (current === generation) loading.value = false }
}
watch(() => [props.orderId, props.area], () => { records.value = { items: [], page: 1, total: 0, totalPages: 0 }; void load() }, { immediate: true })
</script>

<template>
  <section class="order-payment-records" :class="{ 'is-admin': area === 'admin', 'is-customer': area === 'customer' }" :aria-busy="loading">
    <div class="payment-records-heading">
      <h3>{{ t('Payment records') }}</h3>
      <slot name="status" />
    </div>
    <p v-if="loading" role="status">{{ t('Loading…') }}</p>
    <p v-if="error" role="alert">{{ t(error) }} <button type="button" :disabled="loading" @click="load(records.page)">{{ t('Retry') }}</button></p>
    <p v-else-if="!loading && !records.total">{{ t('No payment records yet.') }}</p>
    <div v-if="records.items.length" class="payment-record-list">
      <article v-for="record in records.items" :key="`${record.submissionId || 'direct'}-${record.receiptId || 'pending'}`" class="payment-record">
        <header v-if="area === 'customer'" class="payment-record-header">
          <span class="payment-record-status" :class="record.status === 'pending' ? 'is-pending' : 'is-confirmed'">
            <span class="payment-record-status-dot" aria-hidden="true"></span>
            {{ t(record.status === 'pending' ? 'Pending verification' : 'Receipt confirmed') }}
          </span>
          <time :datetime="record.verifiedAt || record.submittedAt || undefined">{{ formatDateTime(record.verifiedAt || record.submittedAt) }}</time>
        </header>
        <dl class="payment-record-fields" :class="{ 'is-admin': area === 'admin' }">
          <div class="payment-record-field payment-record-channel"><dt>{{ t('Channel') }}</dt><dd>{{ record.channelSnapshot.name || '—' }}</dd></div>
          <template v-if="area === 'admin'">
            <div class="payment-record-field payment-record-amount"><dt>{{ t('Amount') }}</dt><dd>{{ money(record.receivedAmount ?? record.declaredAmount) }}</dd></div>
            <div v-if="record.submissionId != null" class="payment-record-field payment-record-time">
              <dt>{{ t('Submitted at') }}</dt>
              <dd><time v-if="record.submittedAt" :datetime="record.submittedAt">{{ formatDateTime(record.submittedAt) }}</time><span v-else>—</span></dd>
            </div>
          </template>
          <template v-else>
            <div v-if="record.declaredAmount != null" class="payment-record-field payment-record-amount"><dt>{{ t('Declared amount') }}</dt><dd>{{ money(record.status === 'confirmed' ? record.receivedAmount : record.declaredAmount) }}</dd></div>
            <div v-if="record.receivedAmount != null && record.submissionId == null" class="payment-record-field payment-record-amount"><dt>{{ t('Confirmed amount') }}</dt><dd>{{ money(record.receivedAmount) }}</dd></div>
          </template>
          <div v-if="transactionId(record)" class="payment-record-field payment-record-transaction">
            <dt>{{ t('Transaction ID') }}</dt>
            <dd>
              <span>{{ transactionId(record) }}</span>
              <button class="payment-transaction-copy" type="button" :aria-label="t('Copy transaction ID')" :title="t('Copy transaction ID')" @click="emit('copyValue', transactionId(record), 'Transaction ID copied.')">
                <svg viewBox="0 0 18 18" aria-hidden="true"><rect x="6" y="3" width="9" height="11" rx="1"/><path d="M12 15H4a1 1 0 0 1-1-1V5"/></svg>
              </button>
            </dd>
          </div>
          <template v-if="area === 'admin'">
            <div class="payment-record-field payment-record-verifier"><dt>{{ t('Verified by') }}</dt><dd :title="record.verifiedByName || undefined"><span :class="{ 'payment-record-unverified': record.status === 'pending' }">{{ record.status === 'pending' ? t('Not verified') : record.verifiedByName || '—' }}</span></dd></div>
            <div class="payment-record-field payment-record-time">
              <dt>{{ t('Verified at') }}</dt>
              <dd><time v-if="record.verifiedAt" :datetime="record.verifiedAt">{{ formatDateTime(record.verifiedAt) }}</time><span v-else :class="{ 'payment-record-unverified': record.status === 'pending' }">{{ record.status === 'pending' ? t('Not verified') : '—' }}</span></dd>
            </div>
          </template>
        </dl>
      </article>
    </div>
    <div v-if="records.totalPages > 1" class="payment-pagination">
      <button type="button" :disabled="loading || records.page <= 1" @click="load(records.page - 1)">{{ t('Previous') }}</button>
      <span>{{ records.page }} / {{ records.totalPages }}</span>
      <button type="button" :disabled="loading || records.page >= records.totalPages" @click="load(records.page + 1)">{{ t('Next') }}</button>
    </div>
  </section>
</template>

<style scoped>
.order-payment-records { margin: 24px 0; }
.payment-records-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.payment-records-heading h3 { margin: 0; color: var(--color-ink, #1d1d1f); font-size: 20px; font-weight: 650; }
.payment-records-heading :slotted(*) { box-sizing: border-box; max-width: 100%; margin-left: auto; }
.payment-record-list { display: grid; gap: 12px; }
.payment-record { min-width: 0; padding: 18px; border: 1px solid var(--color-line, #e5e5ea); border-radius: 12px; background: var(--color-surface, #fff); }
.payment-record-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px; }
.payment-record-status { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; padding: 5px 9px; border-radius: 6px; font-size: 11px; font-weight: 600; line-height: 1.4; }
.payment-record-status.is-pending { color: #8a5311; background: #fff3de; }
.payment-record-status.is-confirmed { color: #24663b; background: #edf8ef; }
.payment-record-status-dot { width: 5px; height: 5px; flex: 0 0 5px; border-radius: 50%; background: currentColor; }
.payment-record-header time { color: var(--color-muted, #6e6e73); font-size: 11px; line-height: 1.5; }
.payment-record-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px 24px; margin: 16px 0 0; }
.payment-record-fields.is-admin { margin-top: 0; }
.payment-record-field { display: grid; min-width: 0; align-content: start; gap: 6px; }
.payment-record-field dt { color: var(--color-muted, #6e6e73); font-size: 11px; line-height: 1.5; }
.payment-record-field dd { min-width: 0; margin: 0; color: var(--color-ink, #1d1d1f); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.payment-record-channel dd { font-size: 15px; font-weight: 600; }
.payment-record-amount { text-align: right; }
.payment-record-amount dd { font-size: 23px; font-weight: 650; line-height: 1.2; font-variant-numeric: tabular-nums; letter-spacing: -.02em; }
.payment-record-transaction { grid-column: 1 / -1; padding: 12px; border-top: 1px solid var(--color-line, #e5e5ea); border-radius: 0 0 6px 6px; background: var(--color-soft, #f5f5f7); }
.payment-record-transaction dt { display: flex; align-items: center; gap: 8px; }
.payment-transaction-copy { display: inline-grid; width: 28px; height: 28px; margin-left: 6px; vertical-align: middle; place-items: center; padding: 0; color: var(--color-primary, #005bb5); }
.payment-transaction-copy svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.payment-record-transaction dd { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.6; }
.order-payment-records.is-customer { margin: 20px 0 0; }
.is-customer .payment-records-heading { gap: 8px; margin-bottom: 10px; }
.is-customer .payment-record-list { gap: 8px; }
.is-customer .payment-record { padding: 12px 14px; }
.is-customer .payment-record-header { gap: 6px 12px; }
.is-customer .payment-record-header time { width: auto; }
.is-customer .payment-record-status { padding: 3px 7px; }
.is-customer .payment-record-fields { gap: 8px 16px; margin-top: 10px; }
.is-customer .payment-record-field { display: flex; flex-wrap: wrap; align-items: baseline; gap: 3px 8px; }
.is-customer .payment-record-channel dd { font-size: 13px; }
.is-customer .payment-record-amount { justify-content: flex-end; }
.is-customer .payment-record-amount dd { font-size: 18px; }
.is-customer .payment-record-transaction { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 8px; padding: 8px 0 0; border-radius: 0; background: none; }
.is-customer .payment-pagination { gap: 10px; margin-top: 12px; }
.order-payment-records.is-admin { margin: 16px 0; }
.is-admin .payment-records-heading { justify-content: flex-start; gap: 10px; margin-bottom: 12px; }
.is-admin .payment-records-heading :slotted(*) { margin-left: 0; }
.is-admin .payment-record-list { gap: 8px; }
.is-admin .payment-record { padding: 12px; }
.payment-record-fields.is-admin { grid-template-columns: repeat(12, minmax(0, 1fr)); gap: 8px 12px; }
.is-admin .payment-record-field { display: flex; grid-column: span 3; align-items: baseline; gap: 8px; }
.is-admin .payment-record-field dt { flex-shrink: 0; }
.is-admin .payment-record-verifier { grid-column: span 6; }
.is-admin .payment-record-verifier dd { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.is-admin .payment-record-channel dd { font-size: 13px; }
.is-admin .payment-record-amount { text-align: left; }
.is-admin .payment-record-amount dd { font-size: 16px; }
.is-admin .payment-record-transaction { grid-column: 1 / -1; padding: 0; border: 0; border-radius: 0; background: none; }
.is-admin .payment-record-transaction dt { display: block; }
.is-admin .payment-record-transaction dd { font-family: inherit; font-size: 13px; line-height: 1.5; }
.is-admin .payment-record-time { grid-column: span 6; flex-wrap: wrap; gap: 2px 8px; }
.is-admin .payment-record-time dd { color: var(--color-muted, #6e6e73); font-size: 11px; }
.payment-record-unverified { color: var(--color-danger, #d70015); }
.payment-pagination { display: flex; justify-content: center; align-items: center; gap: 14px; margin-top: 16px; }
button { padding: 8px 12px; border: 1px solid var(--color-line, #e5e5ea); border-radius: 6px; background: var(--color-surface, #fff); color: inherit; cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
button:focus-visible { outline: 2px solid var(--color-primary, #005bb5); outline-offset: 3px; }
@media (max-width: 480px) {
  .payment-record { padding: 14px; }
  .payment-record-fields { gap: 14px 16px; }
  .payment-record-amount dd { font-size: 20px; }
  .payment-record-header time { width: 100%; }
  .payment-record-transaction { padding: 10px; }
  .payment-record-fields.is-admin { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 12px; }
  .is-admin .payment-record-field { grid-column: 1 / -1; }
  .is-admin .payment-record-channel, .is-admin .payment-record-amount { grid-column: span 1; }
}
</style>
