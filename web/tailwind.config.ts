import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        true: {
          black: 'var(--background)',
          surface: 'var(--surface)',
          'surface-variant': 'var(--surface-variant)',
        },
        brand: {
          primary: 'var(--primary)',
          secondary: 'var(--secondary)',
          muted: 'var(--muted)',
          outline: 'var(--outline)',
        },
        note: {
          'red-light': '#FFDADA',
          'red-dark': '#2D1616',
          'orange-light': '#FFE5C0',
          'orange-dark': '#2D2014',
          'yellow-light': '#FFF9C0',
          'yellow-dark': '#2D2B14',
          'green-light': '#D4FFD4',
          'green-dark': '#162D16',
          'teal-light': '#D4FFF9',
          'teal-dark': '#142D2B',
          'blue-light': '#D4E8FF',
          'blue-dark': '#141F2D',
          'dark-blue-light': '#D4DCFF',
          'dark-blue-dark': '#181C2D',
          'purple-light': '#E8D4FF',
          'purple-dark': '#20162D',
          'pink-light': '#FFD4EC',
          'pink-dark': '#2D1624',
          'brown-light': '#E8DAC0',
          'brown-dark': '#211B14',
          'gray-light': '#EEEEEE',
          'gray-dark': '#1A1A1A',
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
        shell: '90rem',
        content: '72rem',
        editor: '48rem',
      },
    },
  },
  plugins: [],
};

export default config;
