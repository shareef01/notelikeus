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
          'red-light': '#F28B82',
          'red-dark': '#4A2B2B',
          'orange-light': '#FBBC04',
          'orange-dark': '#4B3621',
          'yellow-light': '#FFF475',
          'yellow-dark': '#4B451A',
          'green-light': '#CCFF90',
          'green-dark': '#2E3D23',
          'teal-light': '#A7FFEB',
          'teal-dark': '#233D3A',
          'blue-light': '#CBF0F8',
          'blue-dark': '#23353D',
          'dark-blue-light': '#AECBFA',
          'dark-blue-dark': '#2B2E4A',
          'purple-light': '#D7AEFB',
          'purple-dark': '#3B2B4A',
          'pink-light': '#FDCFE8',
          'pink-dark': '#4A2B3E',
          'brown-light': '#E6C9A8',
          'brown-dark': '#3D2E23',
          'gray-light': '#E8EAED',
          'gray-dark': '#2A2A2A',
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
