/** Tailwind config for the phone remote page. Same "ocean" palette and dark mode as the xaqua-web UI. */
module.exports = {
  content: ['./remote.html'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        ocean: { 900: '#102824', 800: '#193730', 700: '#23453c', 600: '#38574b' }
      }
    }
  }
};
