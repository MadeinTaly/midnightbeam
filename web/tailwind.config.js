/** Tailwind config for the phone remote page. Arcade oxblood palette, dark mode. */
module.exports = {
  content: ['./remote.html'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        brand: { primary: '#d73337', accent: '#edc23e', light: '#f3eee4', bg: '#18080b', surface: '#241015' }
      }
    }
  }
};
