/** Tailwind config for the phone remote page. App palette, dark mode. */
module.exports = {
  content: ['./remote.html'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        brand: { primary: '#ff2b2b', accent: '#ff6b4d', light: '#ffd9b3', bg: '#0b0b0f', surface: '#1c1513' }
      }
    }
  }
};
