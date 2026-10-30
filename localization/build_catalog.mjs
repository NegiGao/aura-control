// Emits an apply_patch document; one catalog feeds both interfaces without dependencies.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'localization/messages.json'), 'utf8'));
const kotlinString = value => JSON.stringify(value).replaceAll('$', '\\$');
let target, contents;
if (process.argv[2] === 'android') {
  target = path.join(root, 'android/app/src/main/java/tech/devline/scropy_ui/UiLanguageCatalog.kt');
  contents = '// Generated from localization/messages.json; edit that catalog and regenerate.\npackage tech.devline.scropy_ui\n\n' +
    'internal val uiMessages = mapOf(\n' + Object.entries(catalog.messages).map(([source, values]) =>
      `    ${kotlinString(source)} to listOf(${values.map(kotlinString).join(', ')}),`).join('\n') + '\n)\n\n' +
    'internal data class UiPattern(val ja: String, val zh: String, val en: String)\n\ninternal val uiPatterns = listOf(\n' +
    catalog.patterns.map(item => `    UiPattern(${[item.ja,item.zh,item.en].map(kotlinString).join(', ')}),`).join('\n') + '\n)\n';
} else if (process.argv[2] === 'web') {
  target = path.join(root, 'web/ui_languages.js');
  contents = '// Generated from localization/messages.json.\n' +
    `globalThis.AuraLanguageCatalog = ${JSON.stringify(catalog, null, 2)};\n` +
    'if (typeof module !== "undefined") module.exports = globalThis.AuraLanguageCatalog;\n';
} else throw new Error('Choose android or web');

let patch = '*** Begin Patch\n';
if (fs.existsSync(target)) {
  patch += `*** Update File: ${target}\n@@\n` + fs.readFileSync(target, 'utf8').trimEnd().split('\n').map(line => '-' + line).join('\n') + '\n';
} else patch += `*** Add File: ${target}\n`;
patch += contents.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n*** End Patch\n';
process.stdout.write(patch);
