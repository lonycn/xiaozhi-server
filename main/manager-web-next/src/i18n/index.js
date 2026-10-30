import { createI18n } from 'vue-i18n';
import zhCN from './zh_CN';
import zhTW from './zh_TW';
import en from './en';
import de from './de';
import vi from './vi';
import ptBR from './pt_BR';

import enLocale from 'element-plus/es/locale/lang/en.mjs'
import zhLocale from 'element-plus/es/locale/lang/zh-cn.mjs'
import twLocale from 'element-plus/es/locale/lang/zh-tw.mjs'
import deLocale from 'element-plus/es/locale/lang/de.mjs'
import viLocale from 'element-plus/es/locale/lang/vi.mjs'
import ptBRLocale from 'element-plus/es/locale/lang/pt-br.mjs'




// 从本地存储获取语言设置，如果没有则使用浏览器语言或默认语言
const getDefaultLanguage = () => {
  const savedLang = localStorage.getItem('userLanguage');
  if (['zh_CN', 'zh_TW', 'en', 'de', 'vi', 'pt_BR'].includes(savedLang)) {
    return savedLang;
  }
  const browserLang = navigator.language || navigator.userLanguage;
  if (browserLang.indexOf('zh') === 0) {
    if (browserLang === 'zh-TW' || browserLang === 'zh-HK' || browserLang === 'zh-MO') {
      return 'zh_TW';
    }
    return 'zh_CN';
  }
  if (browserLang.indexOf('de') === 0) {
    return 'de';
  }
  if (browserLang.indexOf('vi') === 0) {
    return 'vi';
  }
  if (browserLang === 'pt-BR' || browserLang === 'pt') {
    return 'pt_BR';
  }
  return 'en';
};

const i18n = createI18n({
  legacy: true,
  globalInjection: true,
  locale: getDefaultLanguage(),
  fallbackLocale: 'zh_CN',
  messages: {
    'zh_CN': { ...zhLocale, ...zhCN },
    'zh_TW': { ...twLocale, ...zhTW },
    'en': { ...en, ...enLocale },
    'de': { ...de, ...deLocale },
    'vi': { ...vi, ...viLocale },
    'pt_BR': { ...ptBR, ...ptBRLocale }
  }
});

// Preserve module consumers while Vue templates use the installed Vue 3 plugin.
i18n.t = (...args) => i18n.global.t(...args);
Object.defineProperty(i18n, 'locale', { get: () => i18n.global.locale, set: value => { i18n.global.locale = value; } });
export const elementLocales = { zh_CN: zhLocale, zh_TW: twLocale, en: enLocale, de: deLocale, vi: viLocale, pt_BR: ptBRLocale };
export default i18n;

// 提供一个方法来切换语言
export const changeLanguage = (lang) => {
  if (!Object.hasOwn(elementLocales, lang)) return;
  i18n.locale = lang;
  localStorage.setItem('userLanguage', lang);
  // 通知组件语言已更改

};