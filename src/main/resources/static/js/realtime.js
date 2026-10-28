/** Eventos do processo principal (documento criado, atualizado, lido...), recebidos por IPC. Emitidos em `bus`. */
export const bus = new EventTarget();

export function connect() {
  window.lume?.onEvent((data) => {
    if (!data?.type) return;
    bus.dispatchEvent(new CustomEvent(data.type, { detail: data }));
    bus.dispatchEvent(new CustomEvent("any", { detail: data }));
  });
}

export function on(type, handler) {
  const listener = (e) => handler(e.detail);
  bus.addEventListener(type, listener);
  return () => bus.removeEventListener(type, listener);
}
