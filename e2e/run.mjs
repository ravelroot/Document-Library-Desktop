/**
 * Teste de interface de ponta a ponta: sobe o núcleo em Java (Spring Boot) sem janela, abre a interface no Chromium
 * do Playwright, importa os 12 documentos de exemplo, espera o processamento (PDFBox, capas, DOCX, descrição) e
 * percorre as telas principais, incluindo as novidades da 2.1: seleção em lote, paleta de comandos, "Pergunte ao
 * documento", Estatísticas, Ferramentas e Integrações.
 *
 *   mvn -q package -DskipTests && mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
 *   npm --prefix e2e install
 *   npm --prefix e2e test              # só verifica
 *   npm --prefix e2e test -- --prints  # também salva as imagens em docs/images
 */

import { chromium } from "playwright";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const EXAMPLES = path.join(ROOT, "exemplos");
const SHOTS = process.argv.includes("--prints") ? path.join(ROOT, "docs", "images") : null;
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "lume-e2e-"));
const problems = [];
let step = "";

const log = (msg) => console.log(`  ${msg}`);
function check(condition, message) {
  if (!condition) throw new Error(`${step}: ${message}`);
}

const PORT = Number(process.env.LUME_PORT || 47931);
const KEY = "teste-e2e";
const classpath = [path.join(ROOT, "target", "classes"), path.join(ROOT, "target", "lib", "*")].join(path.delimiter);
const server = spawn(process.env.JAVA || "java", ["-cp", classpath, "io.github.ravelroot.lume.LumeApplication", "--sem-janela", `--porta=${PORT}`, `--chave=${KEY}`], {
  env: { ...process.env, LUME_DATA_DIR: dataDir },
  stdio: ["ignore", "pipe", "pipe"],
});
const ready = new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("O Lume não subiu em 60 s")), 60_000);
  server.stdout.on("data", (d) => {
    const text = String(d);
    if (/ ERROR /.test(text) && !/favicon/.test(text)) problems.push(`núcleo: ${text.trim().split("\n")[0]}`);
    if (text.includes("Lume pronto")) {
      clearTimeout(timer);
      resolve();
    }
  });
  server.on("exit", (code) => reject(new Error(`O Lume saiu com código ${code}`)));
});
await ready;

const browser = await chromium.launch({ args: ["--lang=pt-BR"], ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: "pt-BR" });
const page = await context.newPage();
page.on("pageerror", (err) => problems.push(`erro na página: ${err.message}`));
page.on("console", (msg) => {
  if (msg.type() === "error") problems.push(`console: ${msg.text()}`);
});
await page.goto(`http://127.0.0.1:${PORT}/entrar?chave=${KEY}`);
await page.waitForSelector(".sidebar .brand");
// Nome fictício para os prints (o padrão é o nome do usuário do Windows).
await page.evaluate(() => fetch("/api/v1/me", { method: "PATCH", headers: { "content-type": "application/json" }, body: JSON.stringify({ name: "Marina Couto" }) }));
await page.reload();
await page.waitForSelector(".sidebar .brand");

const api = (url, init) => page.evaluate(async ([u, i]) => {
  const r = await fetch(`/api/v1/${u}`, i ? { ...i, headers: { "content-type": "application/json" } } : undefined);
  return r.status === 204 ? null : r.json();
}, [url, init]);
const shot = async (name, target = page) => {
  if (!SHOTS) return;
  fs.mkdirSync(SHOTS, { recursive: true });
  await page.waitForTimeout(350);
  await target.screenshot({ path: path.join(SHOTS, `${name}.png`) });
  log(`print: ${name}.png`);
};
const go = async (url) => {
  await page.evaluate((u) => {
    history.pushState({}, "", u);
    dispatchEvent(new PopStateEvent("popstate"));
  }, url);
  await page.waitForTimeout(600);
};

try {
  step = "biblioteca vazia";
  await page.waitForSelector(".empty");
  check(await page.isVisible("text=Sua biblioteca está vazia"), "mostra o estado vazio");
  await shot("tela-vazia");

  step = "importação";
  const manifest = JSON.parse(fs.readFileSync(path.join(EXAMPLES, "manifest.json"), "utf8"));
  const files = manifest.map((m) => path.join(EXAMPLES, m.file));
  await page.evaluate((paths) => import("/js/upload.js").then((m) => m.addPaths(paths)), files);
  await page.waitForSelector(".tray");
  await page.waitForTimeout(1500);
  await shot("tela-importando");
  let pending = 12;
  for (let i = 0; i < 240 && pending; i += 1) {
    await page.waitForTimeout(500);
    const list = await api("documents?page_size=50");
    pending = list.results.filter((d) => !["ready", "failed"].includes(d.status)).length;
    if (list.count < 12) pending = 12;
  }
  const docs = (await api("documents?page_size=50")).results;
  check(docs.length === 12, `12 documentos importados (veio ${docs.length})`);
  const failed = docs.filter((d) => d.status !== "ready");
  check(!failed.length, `todos prontos (${failed.map((d) => `${d.title}: ${d.status_detail}`).join("; ")})`);
  check(docs.every((d) => d.cover_url && d.summary), "todos com capa e descrição");
  const pdfs = docs.filter((d) => d.kind === "pdf");
  check(pdfs.every((d) => !d.cover_generated && d.page_count > 0), "PDFs com a capa da primeira página");
  log(`12 documentos prontos (${pdfs.length} PDF, ${docs.length - pdfs.length} DOCX)`);

  step = "duplicado";
  const dup = await page.evaluate((p) => window.lume.importFiles([p]), files[0]);
  check(dup[0].duplicate === true, "reimportar o mesmo arquivo não duplica");

  step = "estado da demonstração";
  const byFile = Object.fromEntries(docs.map((d) => [d.original_name, d]));
  const collections = {};
  for (const m of manifest) {
    const doc = byFile[m.file];
    if (m.reading_state || m.favorite) {
      const body = { favorite: Boolean(m.favorite) };
      if (m.reading_state) body.shelf = m.reading_state;
      if (m.progress_page) Object.assign(body, { page: m.progress_page, progress: (m.progress_page - 1) / Math.max(1, (doc.page_count || 1) - 1) });
      await api(`documents/${doc.id}/reading`, { method: "PATCH", body: JSON.stringify(body) });
      if (m.reading_state === "lendo") await api(`documents/${doc.id}/open`, { method: "POST" });
    }
    if (m.collection) {
      collections[m.collection] ||= (await api("collections", { method: "POST", body: JSON.stringify({ name: m.collection }) })).id;
      await api(`collections/${collections[m.collection]}/documents`, { method: "POST", body: JSON.stringify({ document_ids: [doc.id] }) });
    }
  }
  await page.evaluate(() => document.querySelector("[data-nav-tray-close]")?.click());
  await page.evaluate(() => document.querySelector(".tray .tray-head button[aria-label='Fechar']")?.click());
  await go("/");
  await page.waitForSelector(".grid .card");
  check((await page.locator(".grid .card").count()) === 12, "a grade mostra os 12");
  check(await page.isVisible("text=Continuar lendo"), "mostra o continuar lendo");
  await page.mouse.move(700, 120);
  await page.waitForTimeout(400);
  await shot("tela-biblioteca");

  step = "prévia ao passar o mouse";
  const python = byFile["guia-pratico-de-python.pdf"];
  await page.hover(`.card[data-id="${python.id}"] .cover`);
  await page.waitForSelector(".preview.show");
  check((await page.textContent(".preview")).includes("Descrição por IA"), "a prévia mostra a descrição");
  await shot("tela-previa");
  await page.mouse.move(5, 890);

  step = "busca";
  await page.fill(".topbar input[type=search]", "cooperativa");
  await page.waitForTimeout(900);
  await page.waitForSelector(".snippet mark");
  await shot("tela-busca");
  await page.fill(".topbar input[type=search]", "");
  await page.waitForTimeout(600);

  step = "seleção em lote";
  await page.click(".toolbar button:has-text('Selecionar')");
  await page.waitForSelector(".bulkbar.show");
  const picks = page.locator(".grid .card");
  for (const i of [0, 2, 5]) await picks.nth(i).click();
  check((await page.textContent(".bulkbar .count")).includes("3 selecionados"), "três documentos selecionados");
  await page.mouse.move(700, 880);
  await shot("tela-selecao");
  await page.keyboard.press("Escape");
  await page.waitForTimeout(300);
  check(!(await page.isVisible(".bulkbar.show")), "Esc sai da seleção");
  const loteIds = docs.slice(0, 2).map((d) => d.id);
  const lote = await api("documents/batch", { method: "POST", body: JSON.stringify({ ids: loteIds, action: "tags_add", value: ["revisar"] }) });
  check(lote.affected === 2, `ação em lote aplicada (${lote.affected})`);

  step = "paleta de comandos";
  await page.keyboard.press("Control+k");
  await page.waitForSelector(".palette input");
  await page.keyboard.type("python");
  await page.waitForSelector(".palette-item:has-text('Guia prático de Python')", { timeout: 5000 }).catch(() => {});
  await page.waitForTimeout(300);
  await shot("tela-paleta");
  await page.fill(".palette input", "estatisticas");
  await page.waitForTimeout(200);
  await page.keyboard.press("Enter");
  await page.waitForSelector(".bars");
  check((await page.textContent(".kpis")).includes("12"), "as estatísticas contam os 12 documentos");
  await shot("tela-estatisticas");

  step = "detalhes do documento";
  await go(`/documento/${python.id}`);
  await page.waitForSelector(".detail h1");
  check((await page.textContent(".detail h1")) === python.title, "abre o documento certo");
  check(await page.isVisible("text=Sumário"), "mostra o sumário do PDF");
  await shot("tela-documento");

  step = "pergunte ao documento";
  await page.fill(".ask-form input", "Como instalar o Python no Windows?");
  await page.click(".ask-form button[type=submit]");
  await page.waitForSelector(".ask-answer");
  check((await page.locator(".passage").count()) > 0, "a resposta traz os trechos");
  await page.locator(".ask").scrollIntoViewIfNeeded();
  await page.evaluate(() => scrollBy(0, -90));
  await shot("tela-pergunta");
  await page.evaluate(() => scrollTo(0, 0));

  step = "leitor de PDF";
  await go(`/ler/${python.id}?p=4`);
  await page.waitForSelector(".pg.ready canvas");
  await page.waitForSelector(".textLayer span");
  await page.waitForTimeout(800);
  // Destaca um trecho e escreve uma nota.
  const span = page.locator('.pg[data-page="4"] .textLayer span').filter({ hasText: /Python foi desenhado/ }).first();
  await span.scrollIntoViewIfNeeded();
  await span.click({ clickCount: 3 });
  await page.waitForSelector(".rd-selpop:not([hidden])");
  await page.click(".rd-selpop .rd-dot.c-salvia");
  await page.waitForSelector(".hl.c-salvia");
  await page.click("button[aria-label='Anotações']");
  await page.fill(".rd-compose textarea", "Voltar a este trecho no fim de semana.");
  await page.click(".rd-compose .btn.primary");
  await page.waitForSelector(".rd-note p");
  const notes = await api(`documents/${python.id}/annotations`);
  check(notes.length === 2, `duas anotações salvas (${notes.length})`);
  await shot("tela-leitor-pdf");

  step = "leitor no tema noite";
  await page.click(".rd-tools .seg button[title='Noite']");
  await page.waitForTimeout(400);
  await shot("tela-leitor-noite");
  await page.click(".rd-tools .seg button[title='Papel']");
  await page.waitForTimeout(200);
  await page.keyboard.press("Escape");
  await page.waitForTimeout(400);

  step = "leitor de DOCX";
  const manual = byFile["manual-do-colaborador.docx"];
  await go(`/ler/${manual.id}`);
  await page.waitForSelector(".docx-article h2");
  check(await page.isVisible(".docx-article img"), "a imagem do DOCX aparece");
  await shot("tela-leitor-docx");
  await page.keyboard.press("Escape");

  step = "estantes";
  await go("/estantes");
  await page.waitForSelector(".board .column");
  await shot("tela-estantes");

  step = "coleções";
  await go("/colecoes");
  await page.waitForSelector(".collection-tile");
  await shot("tela-colecoes");
  await go(`/colecoes/${collections.Tecnologia}`);
  await page.waitForSelector(".grid .card");
  check((await page.locator(".grid .card").count()) === 4, "a coleção Tecnologia tem 4 documentos");
  await shot("tela-colecao");

  step = "sistema";
  await go("/sistema");
  await page.waitForSelector(".kpis");
  check((await page.textContent(".kpis")).includes("12"), "o sistema conta os 12 documentos");
  await shot("tela-sistema");

  step = "ferramentas";
  await go("/ferramentas");
  await page.waitForSelector(".tool-grid");
  await page.click(".tool button:has-text('Verificar agora')");
  await page.waitForSelector(".check-list");
  check((await page.textContent(".check-list")).includes("íntegro"), "o banco está íntegro");
  const csv = await api("tools/export/catalog?format=csv");
  check(csv.count === 12 && csv.content.includes("Guia prático de Python"), "o catálogo em CSV tem os 12 documentos");
  await shot("tela-ferramentas");

  step = "integrações";
  const watchRoot = fs.mkdtempSync(path.join(os.tmpdir(), "lume-pasta-"));
  const watch = path.join(watchRoot, "Downloads");
  fs.mkdirSync(watch);
  await api("integrations/watch-folders", { method: "POST", body: JSON.stringify({ path: watch }) });
  await api("integrations/webhooks", { method: "POST", body: JSON.stringify({ url: "https://hooks.slack.com/services/T000/B000/XXXX", events: ["document.ready", "shelf.finished"] }) });
  await go("/integracoes");
  await page.waitForSelector(".integration");
  check((await page.textContent("body")).includes("hooks.slack.com"), "o webhook aparece na lista");
  await shot("tela-integracoes");
  fs.rmSync(watchRoot, { recursive: true, force: true });

  step = "preferências";
  await go("/preferencias");
  await page.waitForSelector("#ia");
  await page.selectOption("#ai-provider", "openai");
  check(await page.isVisible("#ai-model"), "os campos do provedor externo aparecem");
  await page.selectOption("#ai-provider", "local");
  await shot("tela-preferencias");

  step = "tema escuro";
  await api("me", { method: "PATCH", body: JSON.stringify({ theme: "dark" }) });
  await page.evaluate(() => {
    localStorage.setItem("theme", "dark");
    document.documentElement.dataset.theme = "dark";
  });
  await go("/");
  await page.waitForSelector(".grid .card");
  await shot("tela-escuro");
  await go(`/documento/${byFile["contos-da-serra.pdf"].id}`);
  await page.waitForSelector(".detail h1");
  await shot("tela-documento-escuro");
  await api("me", { method: "PATCH", body: JSON.stringify({ theme: "system" }) });
  await page.evaluate(() => {
    localStorage.setItem("theme", "light");
    document.documentElement.dataset.theme = "light";
  });

  step = "excluir";
  const ata = byFile["ata-reuniao-planejamento-4-trimestre.docx"];
  await api(`documents/${ata.id}`, { method: "DELETE" });
  check((await api("documents")).count === 11, "o documento sai da biblioteca");
  check(!fs.existsSync(path.join(dataDir, "documentos", ata.id)), "a pasta do documento some");

  step = "erros";
  const relevant = problems.filter((p) => !/favicon|ResizeObserver/i.test(p));
  check(!relevant.length, `sem erros na interface:\n${relevant.join("\n")}`);
  console.log("✔ teste de interface concluído");
} catch (err) {
  console.error(`✖ ${err.message}`);
  if (problems.length) console.error(problems.join("\n"));
  await page.screenshot({ path: path.join(os.tmpdir(), "lume-e2e-falha.png") }).catch(() => {});
  process.exitCode = 1;
} finally {
  await browser.close().catch(() => {});
  server.kill();
  fs.rmSync(dataDir, { recursive: true, force: true });
}
