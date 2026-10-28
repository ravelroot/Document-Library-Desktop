// Ponte da janela com o núcleo em Java (Spring Boot). Mesma interface do antigo preload do Electron (window.lume),
// agora por HTTP na API local (/api/v1/desktop) e eventos ao vivo por Server-Sent Events (/api/v1/events).
(function () {
  "use strict";

  function call(path, body, method) {
    return fetch("/api/v1/desktop/" + path, {
      method: method || "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    }).then(function (response) {
      return response.json().catch(function () { return null; }).then(function (data) {
        if (!response.ok) throw new Error((data && data.message) || "Erro " + response.status);
        return data;
      });
    });
  }

  var listeners = new Set();
  var source = null;
  function connect() {
    if (source) return;
    source = new EventSource("/api/v1/events");
    source.onmessage = function (event) {
      var data;
      try { data = JSON.parse(event.data); } catch (e) { return; }
      listeners.forEach(function (fn) { try { fn(data); } catch (e) { console.error(e); } });
    };
    // O EventSource reconecta sozinho; se o Lume foi reiniciado, a página nova pede a sessão de novo.
  }

  // Links externos abrem no navegador padrão, não dentro da janela do Lume.
  document.addEventListener("click", function (event) {
    var link = event.target && event.target.closest ? event.target.closest("a[href]") : null;
    if (!link) return;
    var href = link.getAttribute("href") || "";
    if (/^(https?:|mailto:)/i.test(href) && link.origin !== location.origin) {
      event.preventDefault();
      call("open-external", { url: link.href });
    }
  }, true);

  // Posição e tamanho da janela, para abrir do mesmo jeito da próxima vez.
  var saveTimer = null;
  function saveWindow() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () {
      var maximized = window.outerWidth >= screen.availWidth && window.outerHeight >= screen.availHeight;
      fetch("/api/v1/desktop/window", {
        method: "PUT",
        keepalive: true,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ x: window.screenX, y: window.screenY, width: window.outerWidth, height: window.outerHeight, maximized: maximized }),
      }).catch(function () {});
    }, 800);
  }
  window.addEventListener("resize", saveWindow);
  window.addEventListener("pagehide", saveWindow);

  window.lume = {
    /** No navegador não há caminho de disco para arquivos soltos na janela: eles são enviados com upload(). */
    pathForFile: function () { return ""; },
    importFiles: function (paths, options) { return call("import", { paths: paths, collection: (options && options.collection) || null }); },
    upload: function (file, options) {
      var query = "name=" + encodeURIComponent(file.name) + (options && options.collection ? "&collection=" + encodeURIComponent(options.collection) : "");
      return fetch("/api/v1/desktop/upload?" + query, { method: "POST", headers: { "Content-Type": "application/octet-stream" }, body: file })
        .then(function (response) {
          return response.json().then(function (data) {
            return response.ok ? { ok: true, document: data.document, duplicate: data.duplicate } : { ok: false, error: data.message };
          });
        });
    },
    expandPaths: function (paths) { return call("expand", { paths: paths }); },
    pickFiles: function () { return call("pick-files"); },
    pickFolder: function () { return call("pick-folder").then(function (r) { return r && r.folder ? r : null; }); },
    openFile: function (id) { return call("open-file", { id: id }); },
    showInFolder: function (id) { return call("show-in-folder", { id: id }); },
    saveCopy: function (id) { return call("save-copy", { id: id }); },
    saveText: function (filename, content) { return call("save-text", { filename: filename, content: content }); },
    openDataFolder: function () { return call("open-data-folder"); },
    openPath: function (path) { return call("open-path", { path: path }); },
    openExternal: function (url) { return call("open-external", { url: url }); },
    info: function () { return call("info", undefined, "GET"); },
    onEvent: function (callback) {
      listeners.add(callback);
      connect();
      return function () { listeners.delete(callback); };
    },
  };
})();
