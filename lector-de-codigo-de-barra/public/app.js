// Lector de Código de Barra: la venta actual vive en memoria; el servidor valida stock al cobrar.
const $ = (id) => document.getElementById(id);
const money = (cents) => "C$ " + (cents / 100).toLocaleString("es-NI", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const cart = new Map(); // barcode -> { product, qty }
let products = [];
let shownTotal = 0;

async function api(path, body) {
  const res = await fetch(path, body ? {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  } : undefined);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || "El servidor no respondió"), { status: res.status });
  return data;
}

function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children);
  return node;
}

function icon(path) {
  const s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  s.setAttribute("viewBox", "0 0 24 24");
  s.innerHTML = `<path d="${path}"/>`;
  return s;
}
const ICONS = { ok: "M5 12l5 5 9-10", bad: "M6 6l12 12M18 6L6 18", info: "M12 8v.01M11 12h1v5h1", minus: "M5 12h14", plus: "M12 5v14M5 12h14", trash: "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" };

// Reinicia una animación CSS aunque ya se esté reproduciendo.
function replay(node, cls) {
  node.classList.remove(cls);
  void node.offsetWidth;
  node.classList.add(cls);
}

// Cada producto tiene su color, calculado de su código: siempre el mismo.
const PALETTE = ["#059669", "#2563eb", "#d97706", "#db2777", "#7c3aed", "#0891b2", "#ea580c", "#65a30d"];
function avatar(p) {
  let h = 0;
  for (const ch of p.barcode) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  const initials = p.name.split(/\s+/).filter((w) => /\p{L}/u.test(w[0] || "")).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
  const a = el("span", { className: "avatar", textContent: initials || "#" });
  a.style.setProperty("--av", PALETTE[h % PALETTE.length]); // el CSS lo aclara u oscurece según el tema
  return a;
}

const calm = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

// ---------- Modo claro / oscuro ----------
function setTheme(theme) {
  const root = document.documentElement;
  root.classList.add("theming");
  root.dataset.theme = theme;
  try { localStorage.setItem("theme", theme); } catch { /* sin almacenamiento: solo dura esta sesión */ }
  $("theme").setAttribute("aria-label", theme === "dark" ? "Cambiar a modo claro" : "Cambiar a modo oscuro");
  clearTimeout(setTheme.t);
  setTheme.t = setTimeout(() => root.classList.remove("theming"), 450);
}
$("theme").onclick = () => { setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"); focusScan(); };
$("theme").setAttribute("aria-label", document.documentElement.dataset.theme === "dark" ? "Cambiar a modo claro" : "Cambiar a modo oscuro");

// El avatar del producto vuela desde el botón hasta "Venta actual".
function flyToCart(fromEl) {
  if (calm() || !fromEl) return;
  const target = $("count").getBoundingClientRect();
  const r = fromEl.getBoundingClientRect();
  const clone = fromEl.cloneNode(true);
  clone.classList.add("flyer");
  Object.assign(clone.style, { left: r.left + "px", top: r.top + "px", width: r.width + "px", height: r.height + "px" });
  document.body.append(clone);
  const dx = target.left + target.width / 2 - (r.left + r.width / 2);
  const dy = target.top + target.height / 2 - (r.top + r.height / 2);
  clone.animate([
    { transform: "translate(0, 0) scale(1)", opacity: 1 },
    { transform: `translate(${dx * 0.5}px, ${dy * 0.5 - 60}px) scale(1.15)`, opacity: 1, offset: 0.45 },
    { transform: `translate(${dx}px, ${dy}px) scale(0.4)`, opacity: 0.2 },
  ], { duration: 650, easing: "cubic-bezier(0.45, 0, 0.2, 1)" }).finished.then(() => {
    clone.remove();
    replay($("count"), "bump");
  });
}

function confetti() {
  if (calm()) return;
  const box = $("confetti");
  box.replaceChildren();
  const colors = ["#10b981", "#34d399", "#fbbf24", "#60a5fa", "#f472b6", "#a78bfa"];
  for (let i = 0; i < 36; i++) {
    const p = el("i");
    p.style.background = colors[i % colors.length];
    box.append(p);
    const angle = Math.random() * Math.PI * 2, dist = 140 + Math.random() * 220;
    p.animate([
      { transform: "translate(-50%, -50%) rotate(0) scale(1)", opacity: 1 },
      { transform: `translate(${Math.cos(angle) * dist}px, ${Math.sin(angle) * dist * 0.7 + 260}px) rotate(${Math.random() * 720}deg) scale(0.7)`, opacity: 0 },
    ], { duration: 1100 + Math.random() * 600, easing: "cubic-bezier(0.15, 0.7, 0.4, 1)", delay: 120 + Math.random() * 80, fill: "forwards" });
  }
}

// ---------- Avisos ----------
function toast(text, kind = "info") {
  const t = el("div", { className: "toast " + kind }, el("span", { className: "toast-icon" }, icon(ICONS[kind])), el("span", { textContent: text }));
  const box = $("toasts");
  box.append(t);
  while (box.children.length > 3) box.firstChild.remove();
  setTimeout(() => { t.classList.add("out"); t.addEventListener("animationend", () => t.remove()); }, 2600);
}

// ---------- Sonido tipo caja registradora ----------
let audio;
function beep(ok) {
  try {
    audio ||= new AudioContext();
    const t = audio.currentTime, o = audio.createOscillator(), g = audio.createGain();
    o.type = ok ? "sine" : "triangle";
    o.frequency.value = ok ? 1650 : 220;
    const len = ok ? 0.08 : 0.22;
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(ok ? 0.2 : 0.2, t + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, t + len);
    o.connect(g).connect(audio.destination);
    o.start(t);
    o.stop(t + len + 0.02);
  } catch { /* sin audio no pasa nada */ }
}

function scanFeedback(ok) {
  const s = $("scan-form");
  s.classList.remove("ok", "bad");
  replay(s, ok ? "ok" : "bad");
  beep(ok);
}

// Cuenta de un número a otro en vez de saltar (como el display de una caja).
function countTo(node, from, to, format = money) {
  cancelAnimationFrame(node._raf);
  clearTimeout(node._end);
  if (document.hidden || matchMedia("(prefers-reduced-motion: reduce)").matches || from === to) { node.textContent = format(to); return; }
  const start = performance.now(), dur = 380;
  // el número final siempre queda, aunque el navegador pause la animación (pestaña en segundo plano)
  node._end = setTimeout(() => { cancelAnimationFrame(node._raf); node.textContent = format(to); }, dur + 60);
  const step = (now) => {
    const k = Math.min(1, (now - start) / dur);
    const eased = 1 - Math.pow(1 - k, 3);
    node.textContent = format(Math.round(from + (to - from) * eased));
    if (k < 1) node._raf = requestAnimationFrame(step);
  };
  node._raf = requestAnimationFrame(step);
}

// ---------- Lector de barras ----------
// Un lector USB en modo teclado "escribe" el código en pocos milisegundos y manda Enter.
// Medimos el tiempo entre teclas: si es muy rápido, es el lector y no una persona.
const SCAN_GAP_MS = 50;
const buf = { chars: "", last: 0 };

function setReader(state) {
  $("reader").dataset.state = state;
  $("reader-text").textContent = {
    waiting: "Escaneá algo para detectar el lector",
    ready: "Lector USB conectado",
    serial: "Lector USB (serie) conectado",
  }[state];
}

document.addEventListener("keydown", (ev) => {
  const done = !$("done").hidden;
  if (ev.key === "F2") { ev.preventDefault(); if (!done) pay(); return; }
  const now = performance.now();
  const t = ev.target;

  if (ev.key === "Enter") {
    const code = buf.chars;
    const fromScanner = code.length >= 4 && now - buf.last < 120;
    buf.chars = "";
    if (!fromScanner) {
      if (done) { ev.preventDefault(); closeDone(); }
      return;
    }
    if ($("reader").dataset.state !== "serial") setReader("ready");
    if (done) closeDone();
    // estos campos ya reciben el código ellos mismos
    if (!done && (t.id === "scan" || t.name === "barcode" || t.id === "search")) return;
    // el lector escribió dentro de otro campo (por ejemplo "paga con"): lo devolvemos como estaba
    if (t.matches?.("input") && t.value.endsWith(code)) t.value = t.value.slice(0, -code.length);
    ev.preventDefault();
    handleScanned(code);
    return;
  }
  if (ev.key === "Escape" && done) { closeDone(); return; }
  if (ev.key.length === 1 && !ev.ctrlKey && !ev.altKey && !ev.metaKey) {
    if (now - buf.last > SCAN_GAP_MS) buf.chars = ""; // pausa larga: empieza otro código
    buf.chars += ev.key;
    buf.last = now;
  }
});

function handleScanned(code) {
  code = code.trim();
  if ($("view-caja").hidden) fillProductForm(code);
  else scan(code);
}

// Lectores configurados como puerto serie (COM) por USB: Chrome y Edge los leen con Web Serial.
async function connectSerial() {
  let port;
  try {
    port = await navigator.serial.requestPort();
    await port.open({ baudRate: 9600 });
  } catch (e) {
    if (e.name !== "NotFoundError") toast("No se pudo abrir el lector: " + e.message, "bad");
    return;
  }
  setReader("serial");
  toast("Lector USB conectado", "ok");
  $("connect-serial").hidden = true;
  const decoder = new TextDecoderStream();
  port.readable.pipeTo(decoder.writable).catch(() => {});
  const reader = decoder.readable.getReader();
  let pending = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      pending += value;
      let i;
      while ((i = pending.search(/[\r\n]/)) >= 0) {
        const line = pending.slice(0, i).trim();
        pending = pending.slice(i + 1);
        if (line) handleScanned(line);
      }
    }
  } catch { /* el lector se desconectó */ }
  setReader("waiting");
  $("connect-serial").hidden = false;
  toast("Se desconectó el lector USB", "bad");
}
if ("serial" in navigator) {
  $("connect-serial").hidden = false;
  $("connect-serial").onclick = connectSerial;
}

// ---------- Caja ----------
function renderCart(changed, isNew) {
  const list = $("cart-list");
  list.replaceChildren();
  let total = 0, units = 0;
  for (const [code, { product, qty }] of cart) {
    const sub = qty * product.price_cents;
    total += sub;
    units += qty;
    const qtyOut = el("output", { textContent: qty });
    const li = el("li", { className: "line" },
      avatar(product),
      el("div", { className: "line-info" },
        el("div", { className: "line-name", textContent: product.name, title: product.name }),
        el("div", { className: "line-meta", textContent: `${money(product.price_cents)} c/u · ${code}` })),
      el("div", { className: "stepper" },
        el("button", { title: "Quitar uno", onclick: () => changeQty(code, -1) }, icon(ICONS.minus)),
        qtyOut,
        el("button", { title: "Agregar uno", onclick: () => changeQty(code, +1) }, icon(ICONS.plus))),
      el("div", { className: "line-sub", textContent: money(sub) }),
      el("button", { className: "remove", title: "Quitar " + product.name, onclick: () => removeLine(code) }, icon(ICONS.trash)));
    if (code === changed) {
      if (isNew) li.classList.add("enter");
      else {
        li.classList.add("hit");
        qtyOut.classList.add("bump");
        requestAnimationFrame(() => requestAnimationFrame(() => li.classList.remove("hit"))); // se desvanece con la transición
      }
    }
    list.append(li);
  }
  $("cart-empty").hidden = cart.size > 0;
  $("count").textContent = `${units} ${units === 1 ? "artículo" : "artículos"}`;
  $("units").textContent = units;
  $("lines").textContent = cart.size;
  countTo($("total"), shownTotal, total);
  shownTotal = total;
  $("pay").disabled = $("clear").disabled = cart.size === 0;
  updateChange();
  renderQuick();
}

function changeQty(code, delta) {
  const line = cart.get(code);
  if (!line) return;
  const next = line.qty + delta;
  if (next <= 0) return removeLine(code);
  if (next > line.product.stock) {
    scanFeedback(false);
    return toast(`Solo hay ${line.product.stock} de ${line.product.name}`, "bad");
  }
  line.qty = next;
  renderCart(code, false);
  focusScan();
}

function removeLine(code) {
  cart.delete(code);
  renderCart();
  focusScan();
}

function addToCart(product) {
  const line = cart.get(product.barcode);
  const qty = (line ? line.qty : 0) + 1;
  if (qty > product.stock) {
    scanFeedback(false);
    toast(product.stock ? `Solo quedan ${product.stock} de ${product.name}` : `${product.name} está agotado`, "bad");
    return false;
  }
  cart.set(product.barcode, { product, qty });
  scanFeedback(true);
  renderCart(product.barcode, !line);
  if (!line) replay($("count"), "bump");
  return true;
}

async function scan(code) {
  if (!/^[0-9A-Za-z-]{3,32}$/.test(code)) {
    scanFeedback(false);
    return toast("Ese código no es válido. Usá solo números, letras y guiones.", "bad");
  }
  try {
    addToCart(await api("/api/products/" + encodeURIComponent(code)));
  } catch (e) {
    scanFeedback(false);
    if (e.status === 404) {
      toast(`El código ${code} no está registrado. Completá sus datos.`, "info");
      setTimeout(() => fillProductForm(code), 600);
    } else toast(e.message, "bad");
  }
}

// Acceso rápido: los productos registrados, para vender sin escanear (pan, productos sin código...).
let quickShown = false;
function renderQuick() {
  const grid = $("quick-grid");
  grid.replaceChildren();
  const first = !quickShown && products.length > 0;
  products.slice(0, 8).forEach((p, i) => {
    const inCart = cart.get(p.barcode)?.qty || 0;
    const left = p.stock - inCart;
    const av = avatar(p);
    const tile = el("button", {
      className: "tile" + (first ? " rise" : ""), type: "button", disabled: left <= 0, title: `Agregar ${p.name}`,
      onclick: () => { const fromRect = av; if (addToCart(p)) flyToCart(fromRect); focusScan(); },
    },
    el("span", { className: "tile-top" }, av, el("span", { className: "tile-price", textContent: money(p.price_cents) })),
    el("span", { className: "tile-name", textContent: p.name }),
    el("span", { className: "tile-stock", textContent: left <= 0 ? "Agotado" : `${left} disponibles` }));
    if (first) tile.style.animationDelay = `${180 + i * 45}ms`;
    grid.append(tile);
  });
  if (first) quickShown = true;
  $("quick-grid").parentElement.hidden = products.length === 0;
}

function cashCents() {
  const v = parseFloat($("cash").value);
  return Number.isFinite(v) ? Math.round(v * 100) : null;
}

function updateChange() {
  const box = $("change");
  const cash = cashCents();
  if (cash === null || !shownTotal) { box.hidden = true; return; }
  const diff = cash - shownTotal;
  box.hidden = false;
  box.classList.toggle("short", diff < 0);
  $("change-label").textContent = diff >= 0 ? "Vuelto" : "Faltan";
  $("change-value").textContent = money(Math.abs(diff));
}

for (const b of document.querySelectorAll("#chips button")) {
  b.onclick = () => {
    $("cash").value = b.dataset.cash === "exact" ? (shownTotal / 100).toFixed(2) : b.dataset.cash;
    updateChange();
  };
}

async function pay() {
  if (!cart.size || $("view-caja").hidden) return;
  const cash = cashCents();
  if (cash !== null && cash < shownTotal) {
    beep(false);
    return toast(`El cliente paga ${money(cash)} y la venta es ${money(shownTotal)}`, "bad");
  }
  $("pay").disabled = true;
  const items = [...cart].map(([barcode, { qty }]) => ({ barcode, qty }));
  try {
    const sale = await api("/api/sales", { items });
    cart.clear();
    $("cash").value = "";
    $("cash").blur();
    showDone(sale, cash);
    beep(true);
    setTimeout(() => beep(true), 130);
    await loadProducts(); // el stock cambió
    renderCart();
    refreshSummary();
  } catch (e) {
    scanFeedback(false);
    toast(e.message, "bad");
    $("pay").disabled = false;
  }
}

function showDone(sale, cash) {
  const hour = new Date().toLocaleTimeString("es-NI", { hour: "2-digit", minute: "2-digit" });
  $("done-sub").textContent = `Venta #${sale.id} a las ${hour}`;
  const rows = [el("div", { className: "r-row total" }, el("span", { textContent: "Total" }), el("span", { textContent: money(sale.total_cents) }))];
  if (cash !== null) {
    rows.push(el("div", { className: "r-row" }, el("span", { textContent: "Pagó con" }), el("span", { textContent: money(cash) })));
    rows.push(el("div", { className: "r-row change" }, el("span", { textContent: "Vuelto" }), el("span", { textContent: money(cash - sale.total_cents) })));
  }
  $("receipt").replaceChildren(
    el("ul", {}, ...sale.items.map((i) => el("li", {},
      el("span", { textContent: `${i.qty} × ${i.name}` }), el("span", { textContent: money(i.qty * i.price_cents) })))),
    ...rows);
  $("done").hidden = false;
  confetti();
  // reinicia las animaciones del check y la tarjeta
  const card = $("done").querySelector(".done-card");
  card.replaceWith(card.cloneNode(true));
  $("next").onclick = closeDone;
  $("print").onclick = () => window.print();
  $("next").focus();
}

function closeDone() {
  $("done").hidden = true;
  focusScan();
}

function focusScan() {
  if (!$("view-caja").hidden && $("done").hidden && document.activeElement?.id !== "cash") $("scan").focus();
}

$("scan-form").addEventListener("submit", (ev) => {
  ev.preventDefault();
  const code = $("scan").value.trim();
  $("scan").value = "";
  if (code) scan(code);
});
$("cash").addEventListener("input", updateChange);
$("pay").onclick = pay;
$("clear").onclick = () => {
  cart.clear();
  $("cash").value = "";
  renderCart();
  toast("Venta cancelada. No se cobró nada.", "info");
  focusScan();
};
$("done").addEventListener("click", (ev) => { if (ev.target === $("done")) closeDone(); });
// el lector escribe donde esté el foco: si el cajero hace clic en un espacio vacío, vuelve al campo
document.addEventListener("click", (ev) => {
  if (!ev.target.closest("input, button, label, a, .overlay")) focusScan();
});

// ---------- Productos ----------
async function loadProducts() {
  products = await api("/api/products");
  renderProducts();
  renderQuick();
}

function renderProducts(animate) {
  const q = $("search").value.trim().toLowerCase();
  const list = $("products-list");
  list.replaceChildren();
  let i = 0;
  for (const p of products) {
    if (q && !p.name.toLowerCase().includes(q) && !p.barcode.includes(q)) continue;
    const badge = p.stock === 0 ? ["out", "Agotado"] : p.stock <= 5 ? ["low", `Quedan ${p.stock}`] : ["ok", `${p.stock} en stock`];
    const row = el("button", { className: "row" + (animate ? " enter" : ""), type: "button", onclick: () => fillProductForm(p.barcode) },
      el("span", { className: "row-main" }, avatar(p),
        el("span", { style: "min-width:0" },
          el("span", { className: "row-name", textContent: p.name, style: "display:block" }),
          el("span", { className: "row-code", textContent: p.barcode }))),
      el("span", { className: "row-price", textContent: money(p.price_cents) }),
      el("span", { className: "badge " + badge[0], textContent: badge[1] }));
    if (animate) row.style.animationDelay = `${Math.min(i++, 10) * 30}ms`;
    list.append(el("li", {}, row));
  }
  if (!list.children.length) {
    list.append(el("li", { className: "hint", textContent: q ? "Ningún producto coincide con la búsqueda." : "Todavía no hay productos. Escaneá uno para registrarlo." }));
  }
}

async function fillProductForm(code) {
  const f = $("product-form");
  if ($("view-productos").hidden) showView("productos");
  let p = null;
  try { p = await api("/api/products/" + encodeURIComponent(code)); } catch { /* producto nuevo */ }
  f.barcode.value = code;
  f.name.value = p ? p.name : "";
  f.price.value = p ? (p.price_cents / 100).toFixed(2) : "";
  f.stock.value = p ? p.stock : "";
  $("form-title").textContent = p ? "Editar producto" : "Nuevo producto";
  replay(f, "filled");
  beep(true);
  (p ? f.stock : f.name).focus();
}

// Enter en el código (lo manda el lector) busca el producto en vez de guardar el formulario.
$("product-form").barcode.addEventListener("keydown", (ev) => {
  if (ev.key !== "Enter") return;
  ev.preventDefault();
  const code = ev.target.value.trim();
  if (code) fillProductForm(code);
});

$("product-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const f = ev.target;
  const body = {
    barcode: f.barcode.value.trim(),
    name: f.name.value.trim(),
    price_cents: Math.round(parseFloat(f.price.value) * 100),
    stock: parseInt(f.stock.value, 10),
  };
  try {
    const p = await api("/api/products", body);
    toast(`${p.name} guardado`, "ok");
    f.reset();
    $("form-title").textContent = "Nuevo producto";
    await loadProducts();
    refreshSummary();
    f.barcode.focus();
  } catch (e) {
    toast(e.message, "bad");
  }
});
$("product-form").addEventListener("reset", () => { $("form-title").textContent = "Nuevo producto"; });
$("search").addEventListener("input", () => renderProducts(false));

// ---------- General ----------
function showView(name) {
  for (const b of document.querySelectorAll(".nav-btn")) b.classList.toggle("is-active", b.dataset.view === name);
  $("view-caja").hidden = name !== "caja";
  $("view-productos").hidden = name !== "productos";
  $("view-title").textContent = name === "caja" ? "Caja" : "Productos";
  if (name === "productos") renderProducts(true);
  focusScan();
}
for (const b of document.querySelectorAll(".nav-btn")) b.onclick = () => showView(b.dataset.view);

const summary = { sales: 0, total: 0 };
async function refreshSummary() {
  try {
    const s = await api("/api/summary");
    if (summary.loaded && s.sales !== summary.sales) replay($("today-count"), "tick");
    countTo($("today-count"), summary.sales, s.sales, String);
    countTo($("today-total"), summary.total, s.total_cents);
    Object.assign(summary, { sales: s.sales, total: s.total_cents, loaded: true });
    $("today-low").textContent = s.low_stock.length;
    $("today-low").className = s.low_stock.length ? "warn" : "";
    // si hay productos por acabarse, el icono de alerta se sacude para llamar la atención
    const alertIcon = document.querySelector(".kpi-icon.amber");
    alertIcon.classList.toggle("alert", s.low_stock.length > 0);
    if (s.low_stock.length) replay(alertIcon, "alert");
  } catch { /* el resumen es opcional */ }
}

function tickClock() {
  const d = new Date();
  const txt = d.toLocaleDateString("es-NI", { weekday: "long", day: "numeric", month: "long" }) + ", " +
    d.toLocaleTimeString("es-NI", { hour: "2-digit", minute: "2-digit" });
  $("clock").textContent = txt.charAt(0).toUpperCase() + txt.slice(1);
}

// entrada escalonada al abrir: tarjetas de arriba, campo de escaneo, venta y resumen
[...document.querySelectorAll(".kpi"), $("scan-form"), document.querySelector(".cart"), document.querySelector(".order")]
  .forEach((node, i) => { node.classList.add("rise"); node.style.animationDelay = `${i * 70}ms`; });

tickClock();
setInterval(tickClock, 30_000);
renderCart();
loadProducts().catch(() => toast("No se pudo cargar el inventario", "bad"));
refreshSummary();
focusScan();
