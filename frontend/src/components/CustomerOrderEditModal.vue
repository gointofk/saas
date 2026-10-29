<script setup lang="ts">
import { computed, ref } from 'vue'
import { quantityUnitPrice } from '../lib/quantity-pricing'
import AppModal from './AppModal.vue'
import { amountFromCents, parseAmountCents } from '../lib/payment-amount'
import type { CustomerAddress, CustomerOrder, CustomerOrderItem } from '../types/customer'

type EditableOrder = {
  orderNo?: string
  amountLocked?: boolean
  subtotal?: number | string
  total?: number | string
  shippingFee?: number | string
  discountAmount?: number | string
  allowedActions?: CustomerOrder['allowedActions']
}
type EditableItem = CustomerOrderItem & { productType?: string }

const props = defineProps<{
  order: EditableOrder
  addresses: CustomerAddress[]
  selectedAddressId: number | null
  items: EditableItem[]
  note: string
  pending: boolean
  t: (text: string) => string
  money: (value: unknown) => string
}>()

const emit = defineEmits<{
  'update:selectedAddressId': [id: number]
  'update:quantity': [productId: number, quantity: number]
  'update:note': [value: string]
  addAddress: []
  save: []
  close: []
}>()

const addressPickerOpen = ref(false)
const itemsEditable = computed(() => props.order.allowedActions?.includes('edit_items') === true)
const selectedAddress = computed(() => props.addresses.find(address => address.id === props.selectedAddressId) || null)
function displayedPrice(item: EditableItem) {
  return itemsEditable.value ? quantityUnitPrice(item.quantityPrices, Number(item.quantity), item.unitPrice) : item.unitPrice
}
const subtotalCents = computed(() => {
  if (!itemsEditable.value && props.order.subtotal !== undefined) return parseAmountCents(props.order.subtotal) ?? 0n
  return props.items.reduce((sum, item) => {
  const quantity = Number(item.quantity)
  const priceCents = parseAmountCents(displayedPrice(item)) ?? 0n
  return sum + (Number.isSafeInteger(quantity) && quantity > 0 ? priceCents * BigInt(quantity) : 0n)
}, 0n)
})
const shippingCents = computed(() => parseAmountCents(props.order.shippingFee || '0') ?? 0n)
const discountCents = computed(() => parseAmountCents(props.order.discountAmount || '0') ?? 0n)
const subtotal = computed(() => amountFromCents(subtotalCents.value))
const totalCents = computed(() => !itemsEditable.value && props.order.total !== undefined
  ? parseAmountCents(props.order.total) ?? 0n : subtotalCents.value + shippingCents.value - discountCents.value)
const total = computed(() => amountFromCents(totalCents.value))
const discountInvalid = computed(() => discountCents.value > subtotalCents.value || (discountCents.value > 0n && totalCents.value <= 0n))

function addressLines(address: CustomerAddress) {
  return [
    address.street,
    address.apt,
    [address.city, address.region, address.postalCode].filter(Boolean).join(', '),
    address.phone,
  ].filter((line): line is string => Boolean(line))
}

function itemAmount(item: EditableItem) {
  const quantity = Number(item.quantity)
  if (!Number.isSafeInteger(quantity) || quantity < 1) return '0.00'
  const price = parseAmountCents(displayedPrice(item)) ?? 0n
  return amountFromCents(price * BigInt(quantity))
}
</script>

<template>
  <AppModal :label="`${t('Edit Order')} ${order.orderNo || ''}`" content-class="customer-order-edit-modal" @close="emit('close')">
    <header class="customer-order-modal-header">
      <h2 class="modal-title">{{ t('Order #') }}{{ order.orderNo }} · {{ t('Edit Order') }}</h2>
      <button class="close-button" type="button" :aria-label="t('Close')" @click="emit('close')">×</button>
    </header>

    <div class="customer-order-modal-body customer-order-edit-body">
      <section class="order-edit-section">
        <div class="order-edit-section-heading">
          <h3>{{ t('Shipping Address') }}</h3>
          <button class="order-edit-change-address" type="button" :disabled="pending" @click="addressPickerOpen = !addressPickerOpen">{{ t('Change address') }}</button>
        </div>
        <div v-if="selectedAddress" class="order-address-card">
          <span class="order-address-kicker">{{ selectedAddress.isDefault ? t('Default shipping address') : t('Selected shipping address') }}</span>
          <strong>{{ selectedAddress.name }}</strong>
          <p v-for="line in addressLines(selectedAddress)" :key="line">{{ line }}</p>
        </div>
        <p v-else class="order-detail-empty">{{ t('No shipping address saved yet.') }}</p>
        <div v-if="addressPickerOpen" class="order-address-options" role="radiogroup" :aria-label="t('Choose an address for this order')">
          <button v-for="address in addresses" :key="address.id" type="button" role="radio" :aria-checked="selectedAddressId === address.id" :class="{ selected: selectedAddressId === address.id }" @click="emit('update:selectedAddressId', address.id); addressPickerOpen = false">
            <span><strong>{{ address.name }}</strong><small>{{ addressLines(address).join(' · ') }}</small></span>
            <span v-if="address.isDefault" class="order-address-default-tag">{{ t('Default') }}</span>
          </button>
          <button class="order-address-add" type="button" @click="emit('addAddress')">＋ {{ t('Add new address') }}</button>
        </div>
      </section>

      <section class="order-edit-section">
        <div class="order-edit-section-heading"><h3>{{ t('Items') }}</h3></div>
        <p v-if="!itemsEditable" class="muted">{{ t('Order items and prices are locked. You can still update the shipping address and order note.') }}</p>
        <div class="order-line-table order-edit-line-table">
          <div class="order-line-table-head"><span>{{ t('Product') }}</span><span>{{ t('Unit price') }}</span><span>{{ t('Quantity') }}</span><span>{{ t('Amount') }}</span></div>
          <div v-for="item in items" :key="item.productId" class="order-line-table-row">
            <div class="order-edit-product"><strong>{{ item.name }}</strong><small v-if="item.productType === 'gift'">{{ t('Gift item · quantity cannot be changed') }}</small></div>
            <span>{{ money(displayedPrice(item)) }}</span>
            <label class="sr-only" :for="`edit-quantity-${item.productId}`">{{ t('Quantity for') }} {{ item.name }}</label>
            <input autocomplete="off" :id="`edit-quantity-${item.productId}`" type="number" min="1" step="1" :value="item.quantity" :disabled="pending || !itemsEditable || item.productType === 'gift'" :aria-label="`${t('Quantity for')} ${item.name}`" @input="emit('update:quantity', item.productId, ($event.target as HTMLInputElement).valueAsNumber)">
            <strong>{{ money(itemAmount(item)) }}</strong>
          </div>
        </div>
      </section>

      <section class="order-edit-totals">
        <div><span>{{ t('Subtotal') }}</span><strong>{{ money(subtotal) }}</strong></div>
        <div><span>{{ t('Shipping') }}</span><strong>{{ money(amountFromCents(shippingCents)) }}</strong></div>
        <div v-if="Number(order.discountAmount) > 0"><span>{{ t('Discount') }}</span><strong>−{{ money(amountFromCents(discountCents)) }}</strong></div>
        <div class="order-detail-grand-total"><span>{{ t('Total') }}</span><strong>{{ money(total) }}</strong></div>
      </section>

      <label class="order-edit-note"><span>{{ t('Order note') }}</span><textarea autocomplete="off" :value="note" :disabled="pending" maxlength="1000" :placeholder="t('Optional note for shipping company, batch number, or other instructions')" @input="emit('update:note', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
      <p v-if="discountInvalid" class="muted" role="alert">{{ t('The updated items must cover the discount and leave a positive amount due.') }}</p>
    </div>

    <footer class="customer-order-modal-footer">
      <button class="modal-cancel" type="button" :disabled="pending" @click="emit('close')">{{ t('Cancel') }}</button>
      <button class="modal-save" type="button" :disabled="pending || !selectedAddressId || discountInvalid" :aria-busy="pending" @click="emit('save')">{{ t(pending ? 'Saving…' : 'Save changes') }}</button>
    </footer>
  </AppModal>
</template>
