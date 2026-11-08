<script setup lang="ts">
import { computed, useId } from 'vue'
import { validPaymentAmount } from '../lib/payment-amount'
const props = defineProps<{
  modelValue: string
  remaining: number | string
  disabled: boolean
  t: (text: string) => string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const inputId = useId()
const invalid = computed(() => props.modelValue.length > 0 && !validPaymentAmount(props.modelValue, props.remaining))
</script>

<template>
  <div class="payment-amount-field">
    <div class="payment-amount-heading">
      <label :for="inputId">{{ t('This payment (USD)') }}</label>
      <button type="button" :disabled="disabled" @click="emit('update:modelValue', String(remaining))">{{ t('Pay remaining balance') }}</button>
    </div>
    <input :id="inputId" :value="modelValue" inputmode="decimal" autocomplete="off" maxlength="19" :disabled="disabled" :aria-invalid="invalid" @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)">
    <small v-if="invalid" role="alert">{{ t('Enter a positive amount with at most two decimals, within the remaining balance.') }}</small>
  </div>
</template>

<style scoped>
.payment-amount-field { display: grid; min-width: 0; gap: 8px; }
.payment-amount-heading { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
label { color: #52645c; font-size: 12px; font-weight: 700; }
input { box-sizing: border-box; width: 100%; min-width: 0; min-height: 45px; padding: 0 12px; border: 1px solid #ded8cc; border-radius: 8px; background: white; color: var(--color-ink); font: inherit; font-size: 12px; font-weight: 500; }
button { min-height: 24px; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--color-primary); font-size: 12px; text-decoration: underline; text-underline-offset: 3px; cursor: pointer; }
button:disabled { opacity: .55; cursor: default; }
small { color: #a82e38; }
</style>
