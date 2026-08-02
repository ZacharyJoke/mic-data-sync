import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import eslintConfigPrettier from 'eslint-config-prettier'
import globals from 'globals'

// ESLint flat config：集成 JavaScript、TypeScript 与 Vue 检查
export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'coverage/**'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    // 浏览器全局（window/document/setTimeout 等）
    languageOptions: {
      globals: { ...globals.browser },
    },
  },
  {
    // Vue 单文件组件使用 TypeScript 解析器
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
        sourceType: 'module',
      },
    },
  },
  {
    rules: {
      // 脚手架阶段允许单字组件名（如 App）
      'vue/multi-word-component-names': 'off',
      // 模板表达式未使用变量提示，保持宽松
      'vue/no-v-html': 'off',
    },
  },
  eslintConfigPrettier,
]
