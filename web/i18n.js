(function (root) {
  'use strict';
  const catalog = root.AuraLanguageCatalog || (typeof require === 'function' ? require('./ui_languages.js') : null);
  const placeholder = /\{(\d+)\}/g;
  const escaped = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const patterns = catalog.patterns.map(item => ({...item,
    indexes: [...item.ja.matchAll(placeholder)].map(match => Number(match[1])),
    regex: new RegExp('^' + item.ja.split(/\{\d+\}/).map(escaped).join('([\\s\\S]*?)') + '$')
  }));
  const supported = value => ['zh','en','ja'].includes(value) ? value : 'ja';
  let language = 'ja';
  try { language = supported(root.localStorage?.getItem('auraUiLanguage')); } catch (_) {}

  function translate(source, locale = language, depth = 0) {
    locale = supported(locale);
    if (locale === 'ja' || !source.trim() || depth > 5) return source;
    const message = Object.prototype.hasOwnProperty.call(catalog.messages, source) ? catalog.messages[source] : null;
    if (message) return message[locale === 'zh' ? 0 : 1];
    for (const pattern of patterns) {
      const match = source.match(pattern.regex);
      if (match) return pattern[locale].replace(placeholder, (_, index) => {
        const group = pattern.indexes.indexOf(Number(index)) + 1;
        return group ? translate(match[group], locale, depth + 1) : `{${index}}`;
      });
    }
    if (source.includes('\n')) return source.split('\n').map(line => translate(line, locale, depth + 1)).join('\n');
    const trimmed = source.trim();
    if (trimmed !== source) return source.match(/^\s*/)[0] + translate(trimmed, locale, depth + 1) + source.match(/\s*$/)[0];
    return source;
  }

  const sources = new WeakMap();
  const attributes = new WeakMap();
  function translatedValue(record, current) {
    const source = record && current === record.result ? record.source : current;
    return {source, result:translate(source)};
  }
  function localize(container = root.document?.querySelector('main.shell')) {
    if (!container) return;
    const walker = root.document.createTreeWalker(container, 4);
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (node.parentElement?.closest('script,style,pre,[data-no-translate]')) continue;
      const record = translatedValue(sources.get(node), node.data);
      sources.set(node, record);
      if (node.data !== record.result) node.data = record.result;
    }
    [container, ...container.querySelectorAll('[title],[aria-label],[placeholder]')].forEach(element => {
      if (!element.getAttribute || element.closest('[data-no-translate]')) return;
      const records = attributes.get(element) || {};
      ['title','aria-label','placeholder'].forEach(name => {
        const current = element.getAttribute(name);
        if (current === null) return;
        const record = translatedValue(records[name], current);
        records[name] = record;
        if (record.result !== current) element.setAttribute(name, record.result);
      });
      attributes.set(element, records);
    });
  }
  function setLanguage(value) {
    language = supported(value);
    try { root.localStorage?.setItem('auraUiLanguage', language); } catch (_) {}
    if (root.document) {
      root.document.documentElement.lang = language === 'zh' ? 'zh-Hans' : language;
      root.document.querySelectorAll('[data-language]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.language === language)));
      localize();
      localize(root.document.getElementById('aura-connection-dialog'));
    }
    return language;
  }
  function bind() {
    root.document.querySelectorAll('[data-language]').forEach(button => button.addEventListener('click', () => setLanguage(button.dataset.language)));
    setLanguage(language);
  }
  root.AuraI18n = {translate,localize,setLanguage,bind,get language() { return language; }};
  if (typeof module !== 'undefined') module.exports = root.AuraI18n;
})(globalThis);
