import { computed, ref } from 'vue'
import enUS from '../locales/en-US'
import zhCN from '../locales/zh-CN'

export type Locale = 'zh-CN' | 'en-US'

type Messages = typeof zhCN

type Path<T> = T extends string | readonly unknown[]
  ? never
  : {
      [K in keyof T & string]: T[K] extends string | readonly unknown[]
        ? K
        : `${K}.${Path<T[K]>}`
    }[keyof T & string]

const messages: Record<Locale, Messages> = {
  'zh-CN': zhCN,
  'en-US': enUS,
}

const savedLocale = localStorage.getItem('jvuln-locale') as Locale | null
const currentLocale = ref<Locale>(savedLocale === 'en-US' ? 'en-US' : 'zh-CN')

const readPath = (obj: unknown, key: string): unknown =>
  key.split('.').reduce<unknown>((cur, part) => {
    if (cur && typeof cur === 'object' && part in cur) return (cur as Record<string, unknown>)[part]
    return undefined
  }, obj)

const interpolate = (value: string, params?: Record<string, string | number>) => {
  if (!params) return value
  return value.replace(/\{(\w+)\}/g, (_, key) => String(params[key] ?? `{${key}}`))
}

export const setLocale = (locale: Locale) => {
  currentLocale.value = locale
  localStorage.setItem('jvuln-locale', locale)
}

export const useI18n = () => {
  const t = (key: string, params?: Record<string, string | number>) => {
    const value = readPath(messages[currentLocale.value], key)
    return typeof value === 'string' ? interpolate(value, params) : key
  }

  const array = <T = string>(key: Path<Messages>) => {
    const value = readPath(messages[currentLocale.value], key)
    return Array.isArray(value) ? value as T[] : []
  }

  return {
    locale: computed(() => currentLocale.value),
    setLocale,
    t,
    array,
  }
}
