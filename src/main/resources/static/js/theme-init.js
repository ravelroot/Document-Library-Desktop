// Aplica o tema antes da primeira pintura (evita piscar claro→escuro).
(function () {
  var pref = "system";
  try {
    pref = localStorage.getItem("theme") || "system";
  } catch (e) {}
  var dark = pref === "dark" || (pref === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
})();
