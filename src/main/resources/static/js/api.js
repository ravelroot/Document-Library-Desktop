export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `Erro ${status}`);
    this.status = status;
    this.body = body;
  }
}

function buildUrl(path, query) {
  const url = new URL(path.startsWith("/") ? path : `/api/v1/${path}`, location.origin);
  for (const [key, value] of Object.entries(query || {})) {
    if (value !== undefined && value !== null && value !== "" && value !== false) url.searchParams.set(key, value);
  }
  return url;
}

/** Chamada à API local (lume://app/api/v1), atendida pelo processo principal do aplicativo. */
export async function api(path, { method = "GET", body, query, signal, keepalive = false } = {}) {
  const options = { method, headers: { Accept: "application/json" }, signal, keepalive };
  if (body !== undefined) {
    options.body = JSON.stringify(body);
    options.headers["Content-Type"] = "application/json";
  }
  const response = await fetch(buildUrl(path, query), options);
  if (response.status === 204) return null;
  const data = (response.headers.get("content-type") || "").includes("json") ? await response.json() : null;
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}

/** Recursos do sistema expostos pelo preload (importar pelo caminho, diálogos, Explorador...). */
export const desktop = window.lume;
