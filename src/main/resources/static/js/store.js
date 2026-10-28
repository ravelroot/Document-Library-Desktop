import { api } from "./api.js";

const boot = JSON.parse(document.getElementById("boot")?.textContent || "{}");

export const store = {
  user: boot.user,
  brand: boot.brand || { name: "Lume" },
  maxUploadMb: boot.maxUploadMb || 200,
  ai: boot.ai || {},
  stats: null,
  collections: [],
  staticRoot: boot.staticRoot || "/",
  app: boot.app || {},
};

const listeners = new Set();
export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
function notify() {
  listeners.forEach((fn) => fn(store));
}

export async function refreshStats() {
  store.stats = await api("stats");
  notify();
  return store.stats;
}

export async function refreshCollections() {
  store.collections = await api("collections");
  notify();
  return store.collections;
}

export function setUser(user) {
  store.user = user;
  notify();
}

export const refreshAll = () => Promise.all([refreshStats(), refreshCollections()]);
