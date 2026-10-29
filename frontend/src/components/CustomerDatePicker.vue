<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  modelValue: string
  locale: string
  label: string
  required?: boolean
  disabled: boolean
  t: (text: string) => string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  apply: []
}>()

function parseDate(value: string): Date | null {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return date.getFullYear() === Number(match[1]) && date.getMonth() === Number(match[2]) - 1 && date.getDate() === Number(match[3])
    ? date
    : null
}

function dateValue(date: Date): string {
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')
}

function firstOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

const root = ref<HTMLElement | null>(null)
const isOpen = ref(false)
const month = ref(firstOfMonth(parseDate(props.modelValue) || new Date()))
const dateLocale = computed(() => props.locale === 'zh-CN' ? 'zh-CN' : 'en-US')
const monthLabel = computed(() => new Intl.DateTimeFormat(dateLocale.value, { year: 'numeric', month: 'long' }).format(month.value))
const weekLabels = computed(() => {
  const firstDay = dateLocale.value === 'zh-CN' ? 1 : 0
  const start = new Date(2024, 0, 7 + firstDay)
  const formatter = new Intl.DateTimeFormat(dateLocale.value, { weekday: 'short' })
  return Array.from({ length: 7 }, (_, index) => formatter.format(new Date(start.getFullYear(), start.getMonth(), start.getDate() + index)))
})
const days = computed(() => {
  const firstDay = dateLocale.value === 'zh-CN' ? 1 : 0
  const offset = (month.value.getDay() - firstDay + 7) % 7
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(month.value.getFullYear(), month.value.getMonth(), index - offset + 1)
    return {
      value: dateValue(date),
      date,
      inMonth: date.getMonth() === month.value.getMonth(),
    }
  })
})
const today = dateValue(new Date())

watch(() => props.modelValue, (value) => {
  const date = parseDate(value)
  if (date) month.value = firstOfMonth(date)
})

watch(() => props.disabled, disabled => { if (disabled) isOpen.value = false })

function moveMonth(amount: number) {
  if (props.disabled) return
  month.value = new Date(month.value.getFullYear(), month.value.getMonth() + amount, 1)
}

function formatDate(date: Date): string {
  return new Intl.DateTimeFormat(dateLocale.value, { dateStyle: 'full' }).format(date)
}

function updateInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

function selectDate(value: string) {
  if (props.disabled) return
  emit('update:modelValue', value)
  emit('apply')
  isOpen.value = false
}

function clearDate() {
  if (props.disabled) return
  emit('update:modelValue', '')
  emit('apply')
  isOpen.value = false
}

function toggleCalendar() {
  if (props.disabled) return
  if (!isOpen.value) {
    const selected = parseDate(props.modelValue)
    month.value = firstOfMonth(selected || new Date())
  }
  isOpen.value = !isOpen.value
}

function closeWhenOutside(event: PointerEvent) {
  if (!root.value?.contains(event.target as Node)) isOpen.value = false
}

onMounted(() => document.addEventListener('pointerdown', closeWhenOutside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', closeWhenOutside))
</script>

<template>
  <div ref="root" class="history-date-control" @keydown.esc.stop.prevent="isOpen = false">
    <input
      autocomplete="off"
      type="text"
      maxlength="10"
      pattern="[0-9]{4}-[0-9]{2}-[0-9]{2}"
      :disabled="disabled"
      :required="required"
      :value="modelValue"
      :placeholder="t('YYYY-MM-DD')"
      :aria-label="label"
      @input="updateInput"
      @change="emit('apply')"
    >
    <button
      type="button"
      class="history-date-picker-button"
      :disabled="disabled"
      :aria-label="t('Choose date')"
      :aria-expanded="isOpen"
      aria-haspopup="dialog"
      @click.stop="toggleCalendar"
    >
      <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="4" width="15" height="13" rx="2"/><path d="M6 2.5v3M14 2.5v3M2.5 8h15"/></svg>
    </button>

    <div v-if="isOpen" class="history-calendar-popover" role="dialog" :aria-label="t('Choose date')" @click.stop>
      <div class="history-calendar-header">
        <button type="button" :aria-label="t('Previous month')" @click="moveMonth(-1)">‹</button>
        <strong>{{ monthLabel }}</strong>
        <button type="button" :aria-label="t('Next month')" @click="moveMonth(1)">›</button>
      </div>
      <div class="history-calendar-weekdays" aria-hidden="true">
        <span v-for="weekday in weekLabels" :key="weekday">{{ weekday }}</span>
      </div>
      <div class="history-calendar-days">
        <button
          v-for="day in days"
          :key="day.value"
          type="button"
          :class="{ 'outside-month': !day.inMonth, today: day.value === today, selected: day.value === modelValue }"
          :aria-label="formatDate(day.date)"
          :aria-pressed="day.value === modelValue"
          @click="selectDate(day.value)"
        >{{ day.date.getDate() }}</button>
      </div>
      <footer class="history-calendar-actions">
        <button type="button" @click="clearDate">{{ t('Clear') }}</button>
        <button type="button" @click="selectDate(today)">{{ t('Today') }}</button>
      </footer>
    </div>
  </div>
</template>
