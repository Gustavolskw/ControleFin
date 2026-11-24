// Aplica o tema salvo (ou o do sistema) antes do Angular subir, sem "piscar" no tema errado.
(function () {
  var t = null;
  try { t = localStorage.getItem('cf.theme'); } catch (e) {}
  if (t !== 'light' && t !== 'dark') t = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  document.documentElement.setAttribute('data-theme', t);
})();
