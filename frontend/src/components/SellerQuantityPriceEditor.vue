<script setup lang="ts">
import { computed, ref } from 'vue'
import { priceTiers, type PriceTier, type TierQuantityPrices } from '../lib/quantity-pricing'
const model = defineModel<TierQuantityPrices[]>({ required: true })
const props = defineProps<{ fixedPrices: Record<PriceTier, string | number>; disabled: boolean; t: (text: string) => string }>()
const emit = defineEmits<{ fixedPrice: [tier: PriceTier, price: string] }>()
const activeTier = ref<PriceTier>('V1')
const active = computed(() => model.value.find(item => item.tier === activeTier.value))
function normalize() {
  const bands = active.value?.bands || []
  bands.forEach((band, i) => { band.maxQuantity = i + 1 < bands.length ? Number(bands[i + 1]!.minQuantity) - 1 : null })
}
function add() {
  if (!active.value) {
    model.value = [...model.value, { tier: activeTier.value, bands: [{ minQuantity: 1, maxQuantity: null, unitPrice: props.fixedPrices[activeTier.value] || '' }] }]
  } else {
    const last = active.value.bands[active.value.bands.length - 1]!
    active.value.bands.push({ minQuantity: last.minQuantity + 1, maxQuantity: null, unitPrice: last.unitPrice })
    normalize()
  }
}
function remove(i: number) {
  active.value?.bands.splice(i, 1)
  if (!active.value?.bands.length) model.value = model.value.filter(item => item.tier !== activeTier.value)
  else { active.value.bands[0]!.minQuantity = 1; normalize() }
}
</script>
<template>
  <section class="quantity-editor">
    <h3>{{ t('等级定价') }}</h3>
    <div class="quantity-tabs" role="group" :aria-label="t('客户等级')">
      <button v-for="tier in priceTiers" :key="tier" type="button" :aria-pressed="activeTier === tier" :class="{ selected: activeTier === tier }" :disabled="disabled" @click="activeTier = tier">{{ tier }}</button>
    </div>
    <label class="form-group">{{ activeTier }} {{ t('固定单价 (USD)') }}
      <input type="text" inputmode="decimal" :value="fixedPrices[activeTier]" :disabled="disabled" @input="emit('fixedPrice', activeTier, ($event.target as HTMLInputElement).value)">
      <small>{{ t('没有数量档时使用此价格；留空使用基础价格。') }}</small>
    </label>
    <p class="quantity-help">{{ t('每个等级独立配置数量档，按单个商品的购买数量取价。') }}</p>
    <div v-if="active" class="quantity-rows">
      <div v-for="(band, index) in active.bands" :key="index" class="quantity-row">
        <label class="form-group">{{ t('起始数量') }}<input v-model.number="band.minQuantity" type="number" min="1" max="2147483647" step="1" :disabled="disabled || index === 0" @input="normalize"></label>
        <span class="quantity-range">{{ band.maxQuantity === null ? t('及以上') : `– ${band.maxQuantity}` }}</span>
        <label class="form-group">{{ t('单价 (USD)') }}<input v-model="band.unitPrice" type="text" inputmode="decimal" :disabled="disabled"></label>
        <button type="button" class="link-button" :disabled="disabled" :aria-label="`${t('删除数量档')} ${index + 1}`" @click="remove(index)">{{ t('删除') }}</button>
      </div>
    </div>
    <p v-else class="quantity-help">{{ t('当前等级使用固定价，尚未设置数量档。') }}</p>
    <button class="btn btn-outline" type="button" :disabled="disabled || (active?.bands.length || 0) >= 100" @click="add">＋ {{ t('添加数量档') }}</button>
  </section>
</template>
<style scoped>
.quantity-editor { margin: 16px 0 24px; padding: 18px; border: 1px solid var(--color-line, #d8e1da); border-radius: 10px; }
.quantity-editor h3 { margin: 0 0 12px; }
.quantity-tabs { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 18px; }
.quantity-tabs button { flex: 1; min-width: 64px; padding: 8px; border: 1px solid #d8e1da; background: white; border-radius: 6px; color: #20312d; }
.quantity-tabs button.selected { background: #e8f2ec; border-color: #1f5148; }
.quantity-help { color: #667a72; font-size: 13px; line-height: 1.5; }
.quantity-row { display: grid; grid-template-columns: minmax(80px, 1fr) 80px minmax(100px, 1fr) auto; align-items: center; gap: 12px; border-top: 1px solid #d8e1da; padding: 12px 0; }
.quantity-row .form-group { margin: 0; min-width: 0; }
.quantity-row input { width: 100%; min-width: 0; }
.quantity-range { padding-top: 20px; }
@media (max-width: 520px) { .quantity-row { grid-template-columns: minmax(0, 1fr) 64px minmax(0, 1fr); gap: 8px; } .quantity-row button { grid-column: 3; justify-self: end; } .quantity-editor { padding: 12px; } }
</style>
