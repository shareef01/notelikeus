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
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      fontSize: {
        'note-title': ['18px', { lineHeight: '25px', letterSpacing: '-0.5px', fontWeight: '600' }],
        'note-body': ['14px', { lineHeight: '19.6px', letterSpacing: '0.15px', fontWeight: '400' }],
        'section-label': ['12px', { lineHeight: '16px', letterSpacing: '1px', fontWeight: '700' }],
      },
      spacing: {
        'note-gap': '12px',
        'layout-gap': '16px',
      },
      borderRadius: {
        note: '16px',
        sheet: '16px',
      },
      maxWidth: {
        shell: '116rem',
        content: '88rem',
        editor: '48rem',
      },
    },
  },
  plugins: [tailwindcssAnimate],
};

export default config;
