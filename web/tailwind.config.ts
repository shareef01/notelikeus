import type { Config } from 'tailwindcss';
import tailwindcssAnimate from 'tailwindcss-animate';

const config: Config = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        true: {
          black: '#000000', // OLED Absolute Black — intentionally static, not theme-reactive
          surface: 'rgb(var(--surface-rgb) / <alpha-value>)',
          'surface-variant': 'rgb(var(--surface-variant-rgb) / <alpha-value>)',
        },
        brand: {
          primary: 'rgb(var(--primary-rgb) / <alpha-value>)',
          secondary: 'rgb(var(--secondary-rgb) / <alpha-value>)',
          muted: 'rgb(var(--muted-rgb) / <alpha-value>)',
          outline: 'rgb(var(--outline-rgb) / <alpha-value>)',
        },
        note: {
          'red-light': '#FFCDD2',
          'red-dark': '#6D2B2B',
          'orange-light': '#FFE0B2',
          'orange-dark': '#6B4520',
          'yellow-light': '#FFF59D',
          'yellow-dark': '#6B5C18',
          'green-light': '#C8E6C9',
          'green-dark': '#2E5A32',
          'teal-light': '#B2DFDB',
          'teal-dark': '#1E5650',
          'blue-light': '#BBDEFB',
          'blue-dark': '#2A4A6E',
          'purple-light': '#E1BEE7',
          'purple-dark': '#4A2D62',
          'pink-light': '#F8BBD0',
          'pink-dark': '#6B2D48',
          default: '#121212',
        },
      },
      fontFamily: {
        sans: [
          'Inter',
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'Roboto',
          'Helvetica Neue',
          'Arial',
          'sans-serif',
        ],
      },
      fontSize: {
        overline: [
          '11px',
          { lineHeight: '14px', letterSpacing: '0.08em', fontWeight: '600' },
        ],
        'note-title': ['18px', { lineHeight: '28px', letterSpacing: '-0.02em', fontWeight: '600' }],
        'note-body': ['15px', { lineHeight: '1.6', letterSpacing: '0.005em', fontWeight: '400' }],
        'section-label': [
          '11px',
          { lineHeight: '16px', letterSpacing: '0.08em', fontWeight: '600' },
        ],
      },
      spacing: {
        'note-gap': '14px',
        'layout-gap': '20px',
      },
      borderRadius: {
        note: '18px',
        sheet: '16px',
      },
      maxWidth: {
        shell: '116rem',
        content: '88rem',
        editor: '48rem',
      },
      boxShadow: {
        'header-scroll': '0 1px 0 0 rgb(var(--outline-rgb) / 0.4)',
        card: '0 1px 3px 0 rgb(0 0 0 / 0.12), 0 1px 2px -1px rgb(0 0 0 / 0.12)',
        'card-lg': '0 4px 12px -2px rgb(0 0 0 / 0.18), 0 2px 6px -2px rgb(0 0 0 / 0.12)',
      },
    },
  },
  plugins: [tailwindcssAnimate],
};

export default config;
