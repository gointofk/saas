import { inject, type InjectionKey } from 'vue'
import { chineseCustomerText } from './customer-locale'
import { englishSellerText, chineseSellerText } from './seller-messages'

export type Translate = (text: string, values?: readonly unknown[]) => string
export const sellerTranslationKey: InjectionKey<Translate> = Symbol('sellerTranslation')

const english = { ...Object.fromEntries(Object.entries(chineseCustomerText).map(([en, zh]) => [zh, en])), ...englishSellerText }
const chinese = { ...Object.fromEntries(Object.entries(englishSellerText).map(([zh, en]) => [en, zh])), ...chineseCustomerText, ...chineseSellerText }

function interpolate(text: string, values: readonly unknown[]) {
  return text.replace(/\{(\d+)\}/g, (placeholder, index: string) => index in values ? String(values[Number(index)]) : placeholder)
}

// Existing asynchronous errors contain interpolated text. Match complete catalog
// messages so an open error can change language without translating business data.
function patterns(dictionary: Record<string, string>) {
  return Object.entries(dictionary).filter(([key]) => /\{\d+\}/.test(key))
    .sort(([a], [b]) => b.replace(/\{\d+\}/g, '').length - a.replace(/\{\d+\}/g, '').length)
    .map(([key, value]) => {
      const indices: number[] = []
      const source = key.split(/(\{\d+\})/g).map(part => {
        if (/^\{\d+\}$/.test(part)) { indices.push(Number(part.slice(1, -1))); return '([\\s\\S]*?)' }
        return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      }).join('')
      return { regex: new RegExp(`^${source}$`), indices, value }
    })
}
const englishPatterns = patterns(english)
const chinesePatterns = patterns(chinese)

export function translateSeller(text: string, language: string, values: readonly unknown[] = []): string {
  const dictionary = language === 'en' ? english : chinese
  if (Object.prototype.hasOwnProperty.call(dictionary, text)) return interpolate(dictionary[text], values)
  // Already-localized messages and neutral text remain stable, including on a
  // second render pass. Never perform substring replacements on user content.
  const sourceDictionary = language === 'en' ? chinese : english
  if (Object.prototype.hasOwnProperty.call(sourceDictionary, text)) return interpolate(text, values)
  if (!values.length) {
    for (const entry of language === 'en' ? englishPatterns : chinesePatterns) {
      const match = entry.regex.exec(text)
      if (!match) continue
      const parameters: string[] = []
      entry.indices.forEach((index, position) => { parameters[index] = match[position + 1] })
      return interpolate(entry.value, parameters)
    }
  }
  if (!values.length && text.includes('\n')) return text.split('\n').map(line => translateSeller(line, language)).join('\n')
  const fieldError = /^([a-zA-Z][a-zA-Z0-9.]*)：(.+)$/.exec(text)
  if (fieldError) return `${translateSeller(fieldError[1], language)}${language === 'en' ? ': ' : '：'}${translateSeller(fieldError[2], language)}`
  return interpolate(text, values)
}

export function useSellerTranslation(): Translate {
  return inject(sellerTranslationKey, (text, values) => translateSeller(text, 'zh-CN', values))
}
