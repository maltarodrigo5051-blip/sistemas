document.getElementById('sin-servidor').remove();
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const fmt = n => Number(n).toLocaleString('es-NI', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const cs = n => 'C$ ' + fmt(n);
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
// data-t/data-v hacen que Excel reciba números reales y no texto formateado
const money = n => `<td class="num" data-t="n" data-v="${n}">${fmt(n)}</td>`;
const money0 = n => (n ? money(n) : '<td></td>');
const hoy = new Date().toLocaleDateString('sv'); // aaaa-mm-dd en hora local (toISOString usa UTC)
const DEUDORAS = ['ACTIVO', 'GASTO'];
const TIPO = { ACTIVO: 'Activo', PASIVO: 'Pasivo', PATRIMONIO: 'Patrimonio', INGRESO: 'Ingreso', GASTO: 'Gasto' };
const saldo = c => (DEUDORAS.includes(c.tipo) ? c.debe - c.haber : c.haber - c.debe);
const suma = (lista, f) => lista.reduce((s, x) => s + f(x), 0);
let cuentas = [];

// barra de carga superior + indicador de conexión
let pendientes = 0;
function carga(delta) {
  pendientes += delta;
  const b = $('#carga');
  if (pendientes > 0) { b.className = 'activo'; return; }
  b.className = 'fin';
  setTimeout(() => { if (!pendientes) b.className = ''; }, 500);
}
function estado(enLinea) {
  $('#estado').className = 'estado ' + (enLinea ? 'en-linea' : 'fuera');
  $('#estado').textContent = enLinea ? 'Conectado' : 'Sin conexión';
}

async function api(ruta, datos) {
  carga(1);
  try {
    let r;
    try { r = await fetch('/api/' + ruta, datos ? { method: 'POST', body: new URLSearchParams(datos) } : undefined); }
    catch { estado(false); throw new Error('No hay conexión con el servidor. Ejecuta run.ps1.'); }
    estado(true);
    const j = await r.json();
    if (!r.ok) throw new Error(j.error);
    return j;
  } finally { carga(-1); }
}

// ---------- tema claro / oscuro ----------
const raiz = document.documentElement;
raiz.dataset.theme ||= matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
$('#tema').onclick = () => {
  raiz.dataset.theme = raiz.dataset.theme === 'dark' ? 'light' : 'dark';
  try { localStorage.setItem('tema', raiz.dataset.theme); } catch { /* sin almacenamiento: el tema dura hasta recargar */ }
};
$('#fecha-hoy').textContent = new Date().toLocaleDateString('es', { weekday: 'long', day: 'numeric', month: 'long' });

// botones de exportación (mismo par en cada tabla)
const ICONO_DESCARGA = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>';
$$('.exportar[data-tabla]').forEach(d => (d.innerHTML =
  `<button type="button" data-exp="${d.dataset.tabla}" data-fmt="pdf">${ICONO_DESCARGA}PDF</button>` +
  `<button type="button" data-exp="${d.dataset.tabla}" data-fmt="xlsx">${ICONO_DESCARGA}Excel</button>`));

function aviso(texto, ok = true) {
  const m = $('#msg');
  m.textContent = texto;
  m.style.background = ok ? 'var(--ingreso)' : 'var(--gasto)';
  m.style.display = 'block';
  clearTimeout(aviso.t);
  aviso.t = setTimeout(() => (m.style.display = 'none'), 4000);
}
const fallo = e => aviso(e.message, false);

const opciones = tipos => cuentas.filter(c => !tipos || tipos.includes(c.tipo))
  .map(c => `<option value="${esc(c.codigo)}">${esc(c.codigo)}  ${esc(c.nombre)}</option>`).join('');
const tabla = (cab, filas, vacio) =>
  `<thead><tr>${cab}</tr></thead><tbody>${filas || `<tr><td class="vacio" colspan="9">${vacio}</td></tr>`}</tbody>`;

// ---------- navegación ----------
const cargar = { inicio: cargarInicio, diario: cargarDiario, cuentas: cargarCuentas, reportes: generarReportes };
function ir(tab) {
  $$('nav button').forEach(x => x.classList.toggle('activo', x.dataset.tab === tab));
  $$('main section').forEach(s => (s.hidden = s.id !== tab));
  $('#miga').textContent = $(`nav [data-tab=${tab}]`).textContent;
  scrollTo(0, 0);
  cargar[tab]?.().catch(fallo);
}
$$('[data-tab]').forEach(b => (b.onclick = () => ir(b.dataset.tab)));
$$('[data-ir]').forEach(b => (b.onclick = () => ir(b.dataset.ir)));

// ---------- inicio (dashboard) ----------
async function cargarInicio() {
  const ahora = new Date();
  const iniMes = hoy.slice(0, 8) + '01';
  const hace6 = new Date(ahora.getFullYear(), ahora.getMonth() - 5, 1).toLocaleDateString('sv');
  const [mes, acum, mensual, diario] = await Promise.all([
    api(`saldos?desde=${iniMes}&hasta=${hoy}`), api('saldos'), api(`mensual?desde=${hace6}`), api(`diario?desde=${hace6}`),
  ]);

  const nombreMes = ahora.toLocaleDateString('es', { month: 'long' });
  $('#mes-actual').textContent = ahora.toLocaleDateString('es', { month: 'long', year: 'numeric' });
  const ing = suma(mes.filter(c => c.tipo === 'INGRESO'), saldo), gas = suma(mes.filter(c => c.tipo === 'GASTO'), saldo);
  $('#etq-resultado').textContent = `${ing - gas >= 0 ? 'Utilidad' : 'Pérdida'} de ${nombreMes}`;
  $('#k-resultado').textContent = cs(ing - gas);
  $('#k-resultado').className = 'cifra ' + (ing - gas < 0 ? 'bad' : '');
  $('#k-ingresos').textContent = cs(ing);
  $('#k-gastos').textContent = cs(gas);
  const [bi, bg] = $$('#balanza span');
  bi.style.flexGrow = ing; bg.style.flexGrow = gas;
  $('#balanza').setAttribute('aria-label', `Ingresos ${cs(ing)} frente a gastos ${cs(gas)}`);

  // shortcut: agrupa por prefijo del plan de cuentas base (11 caja/bancos, 12 por cobrar); ajustar si cambias la codificación
  $('#k-disponible').textContent = cs(suma(acum.filter(c => c.codigo.startsWith('11')), saldo));
  $('#k-cobrar').textContent = cs(suma(acum.filter(c => c.codigo.startsWith('12')), saldo));
  $('#k-pagar').textContent = cs(suma(acum.filter(c => c.tipo === 'PASIVO'), saldo));

  // gráfico espejo: ingresos hacia arriba, gastos hacia abajo
  const meses = Array.from({ length: 6 }, (_, i) => new Date(ahora.getFullYear(), ahora.getMonth() - 5 + i, 1));
  const datos = meses.map(d => mensual.find(m => m.mes === d.toLocaleDateString('sv').slice(0, 7)) || { ingresos: 0, gastos: 0 });
  const max = Math.max(1, ...datos.flatMap(m => [m.ingresos, m.gastos]));
  $('#grafico').innerHTML = datos.map((m, i) => {
    const etq = meses[i].toLocaleDateString('es', { month: 'short' }).replace('.', '');
    return `<div class="mes" role="img" aria-label="${etq}: ingresos ${cs(m.ingresos)}, gastos ${cs(m.gastos)}" title="Ingresos ${cs(m.ingresos)}\nGastos ${cs(m.gastos)}">
      <div class="arriba"><i style="height:${(m.ingresos / max) * 100}%"></i></div>
      <div class="abajo"><i style="height:${(m.gastos / max) * 100}%"></i></div>
      <span>${etq}</span></div>`;
  }).join('');

  const asientos = [];
  for (const f of diario) {
    const a = asientos.at(-1);
    if (a?.id === f.asiento) a.total += f.debe;
    else asientos.push({ id: f.asiento, fecha: f.fecha, descripcion: f.descripcion, total: f.debe });
  }
  $('#recientes').innerHTML = asientos.slice(-6).reverse().map(a =>
    `<li><div>${esc(a.descripcion)}<small>N° ${a.id}, ${a.fecha}</small></div><b class="num">${cs(a.total)}</b></li>`).join('')
    || '<li class="vacio">Aún no hay asientos. Registra tu primer movimiento.</li>';
}

// ---------- cuentas ----------
async function cargarCuentas() {
  cuentas = await api('cuentas');
  $('#t-cuentas').innerHTML = tabla('<th>Código</th><th>Nombre</th><th>Tipo</th>',
    cuentas.map(c => `<tr data-q="${esc((c.codigo + ' ' + c.nombre + ' ' + TIPO[c.tipo]).toLowerCase())}">
      <td>${esc(c.codigo)}</td><td>${esc(c.nombre)}</td><td><span class="chip t-${c.tipo}">${TIPO[c.tipo]}</span></td></tr>`).join(''),
    'No hay cuentas.');
  actualizarRapido();
  $$('#lineas select').forEach(s => { const v = s.value; s.innerHTML = opciones(); s.value = v; });
}

$('#f-cuenta').onsubmit = async e => {
  e.preventDefault();
  try {
    await api('cuentas', new FormData(e.target));
    e.target.reset();
    await cargarCuentas();
    aviso('Cuenta agregada');
  } catch (err) { fallo(err); }
};

// ---------- búsqueda en tablas ----------
$$('[data-buscar]').forEach(inp => (inp.oninput = () => {
  const q = inp.value.trim().toLowerCase();
  $$(`#${inp.dataset.buscar} tbody tr[data-q]`).forEach(tr => (tr.hidden = !tr.dataset.q.includes(q)));
}));

// ---------- registro rápido de ingresos / gastos ----------
const fr = $('#f-rapido');
const nombre = cod => cuentas.find(c => c.codigo === cod)?.nombre ?? '';
function actualizarRapido() {
  const ingreso = fr.tipo.value === 'ingreso';
  const [vc, vp] = [fr.concepto.value, fr.pago.value];
  fr.concepto.innerHTML = opciones([ingreso ? 'INGRESO' : 'GASTO']);
  fr.pago.innerHTML = opciones(['ACTIVO', 'PASIVO']);
  // conserva la selección si sigue existiendo; si no (cambió de ingreso a gasto), toma la primera
  for (const [sel, v] of [[fr.concepto, vc], [fr.pago, vp]]) { sel.value = v; if (!sel.value) sel.selectedIndex = 0; }
  $('#btn-rapido').textContent = ingreso ? 'Registrar ingreso' : 'Registrar gasto';
  $('#btn-rapido').style.background = $('#btn-rapido').style.borderColor = ingreso ? 'var(--ingreso)' : 'var(--gasto)';
  notaRapido();
}
function notaRapido() {
  const ingreso = fr.tipo.value === 'ingreso';
  const [d, h] = ingreso ? [fr.pago.value, fr.concepto.value] : [fr.concepto.value, fr.pago.value];
  $('#nota-rapido').textContent = `Asiento que se creará: Debe ${nombre(d)}, Haber ${nombre(h)}.`;
}
$$('#f-rapido [name=tipo]').forEach(r => (r.onchange = actualizarRapido));
fr.concepto.onchange = fr.pago.onchange = notaRapido;
fr.fecha.value = hoy;
fr.onsubmit = async e => {
  e.preventDefault();
  const m = fr.monto.value;
  // ingreso: Debe caja/banco, Haber ingreso; gasto: Debe gasto, Haber caja/banco
  const [debe, haber] = fr.tipo.value === 'ingreso' ? [fr.pago.value, fr.concepto.value] : [fr.concepto.value, fr.pago.value];
  try {
    const r = await api('asientos', [
      ['fecha', fr.fecha.value], ['descripcion', fr.descripcion.value],
      ['cuenta', debe], ['debe', m], ['haber', '0'],
      ['cuenta', haber], ['debe', '0'], ['haber', m],
    ]);
    fr.monto.value = fr.descripcion.value = '';
    aviso(`${fr.tipo.value === 'ingreso' ? 'Ingreso' : 'Gasto'} registrado en el asiento N° ${r.id}`);
  } catch (err) { fallo(err); }
};

// ---------- asiento manual (debe / haber) ----------
const fa = $('#f-asiento');
fa.fecha.value = hoy;
const lineas = () => $$('#lineas .linea:not(.encabezado-lineas)');
function nuevaLinea() {
  const d = document.createElement('div');
  d.className = 'linea';
  d.innerHTML = `<select aria-label="Cuenta">${opciones()}</select>
    <input type="number" step="0.01" min="0" placeholder="Debe" aria-label="Debe">
    <input type="number" step="0.01" min="0" placeholder="Haber" aria-label="Haber">
    <button type="button" aria-label="Quitar línea">✕</button>`;
  d.querySelector('button').onclick = () => { d.remove(); totales(); };
  d.oninput = totales;
  $('#lineas').append(d);
}
function totales() {
  let td = 0, th = 0;
  lineas().forEach(l => { const [d, h] = l.querySelectorAll('input'); td += +d.value; th += +h.value; });
  const ok = Math.abs(td - th) < 0.005 && td > 0;
  $('#totales').innerHTML = `Debe ${cs(td)}, Haber ${cs(th)}: <span class="${ok ? 'ok' : 'bad'}">${ok ? 'cuadrado' : 'diferencia ' + cs(td - th)}</span>`;
}
function reiniciarAsiento() { lineas().forEach(l => l.remove()); nuevaLinea(); nuevaLinea(); totales(); }
$('#mas-linea').onclick = nuevaLinea;
$('#guardar-asiento').onclick = async () => {
  const datos = [['fecha', fa.fecha.value], ['descripcion', fa.descripcion.value]];
  lineas().forEach(l => {
    const [d, h] = l.querySelectorAll('input');
    datos.push(['cuenta', l.querySelector('select').value], ['debe', d.value], ['haber', h.value]);
  });
  try {
    const r = await api('asientos', datos);
    fa.descripcion.value = '';
    reiniciarAsiento();
    aviso(`Asiento N° ${r.id} guardado`);
  } catch (err) { fallo(err); }
};

// ---------- libro diario ----------
async function cargarDiario() {
  const filas = await api(`diario?desde=${$('#d-desde').value}&hasta=${$('#d-hasta').value}`);
  // texto de búsqueda por asiento completo, para que el filtro muestre o esconda todas sus líneas juntas
  const texto = {};
  for (const f of filas) texto[f.asiento] = (texto[f.asiento] ?? `${f.asiento} ${f.fecha} ${f.descripcion}`) + ` ${f.cuenta} ${f.nombre}`;
  let html = '', prev = null, td = 0, th = 0;
  for (const f of filas) {
    const nuevo = f.asiento !== prev;
    prev = f.asiento; td += f.debe; th += f.haber;
    html += `<tr class="${nuevo ? 'inicio' : 'cont'}" data-q="${esc(texto[f.asiento].toLowerCase())}">
      <td>${nuevo ? f.asiento : ''}</td><td>${nuevo ? f.fecha : ''}</td><td>${nuevo ? esc(f.descripcion) : ''}</td>
      <td class="${f.haber ? 'sangria' : ''}">${esc(f.cuenta)} ${esc(f.nombre)}</td>${money0(f.debe)}${money0(f.haber)}</tr>`;
  }
  if (filas.length) html += `<tr class="total"><td colspan="4">Totales</td>${money(td)}${money(th)}</tr>`;
  $('#t-diario').innerHTML = tabla('<th>N°</th><th>Fecha</th><th>Descripción</th><th>Cuenta</th><th class="num">Debe</th><th class="num">Haber</th>',
    html, 'No hay asientos en estas fechas.');
}
$('#d-filtrar').onclick = () => cargarDiario().catch(fallo);

// ---------- reportes ----------
function bloque(titulo, lista) {
  const total = suma(lista, saldo);
  const html = `<tr class="grupo"><td colspan="3">${titulo}</td></tr>` +
    lista.map(c => `<tr><td>${esc(c.codigo)}</td><td>${esc(c.nombre)}</td>${money(saldo(c))}</tr>`).join('') +
    `<tr class="total"><td colspan="2">Total ${titulo.toLowerCase()}</td>${money(total)}</tr>`;
  return { html, total };
}
const delTipo = (lista, tipo) => lista.filter(c => c.tipo === tipo && (c.debe || c.haber));
const cab3 = '<th>Código</th><th>Cuenta</th><th class="num">Saldo</th>';

async function generarReportes() {
  const d = $('#r-desde').value, h = $('#r-hasta').value;
  // El balance general es acumulado hasta la fecha; comprobación y resultados son del periodo.
  const [periodo, acumulado] = await Promise.all([api(`saldos?desde=${d}&hasta=${h}`), api(`saldos?hasta=${h}`)]);

  let sd = 0, sh = 0, sdeu = 0, sacr = 0, html = '';
  for (const c of periodo.filter(c => c.debe || c.haber)) {
    const s = c.debe - c.haber;
    sd += c.debe; sh += c.haber; sdeu += Math.max(s, 0); sacr += Math.max(-s, 0);
    html += `<tr><td>${esc(c.codigo)}</td><td>${esc(c.nombre)}</td>${money(c.debe)}${money(c.haber)}${money0(Math.max(s, 0))}${money0(Math.max(-s, 0))}</tr>`;
  }
  if (html) html += `<tr class="total"><td colspan="2">Totales</td>${money(sd)}${money(sh)}${money(sdeu)}${money(sacr)}</tr>`;
  $('#t-comprobacion').innerHTML = tabla('<th>Código</th><th>Cuenta</th><th class="num">Debe</th><th class="num">Haber</th><th class="num">Saldo deudor</th><th class="num">Saldo acreedor</th>',
    html, 'No hay movimientos en el periodo.');

  const ing = bloque('Ingresos', delTipo(periodo, 'INGRESO')), gas = bloque('Gastos', delTipo(periodo, 'GASTO'));
  const util = ing.total - gas.total;
  $('#t-resultados').innerHTML = tabla(cab3, ing.html + gas.html +
    `<tr class="total"><td colspan="2" class="${util < 0 ? 'bad' : 'ok'}">${util >= 0 ? 'Utilidad' : 'Pérdida'} neta</td>${money(util)}</tr>`);

  const act = bloque('Activo', delTipo(acumulado, 'ACTIVO')), pas = bloque('Pasivo', delTipo(acumulado, 'PASIVO'));
  const resultado = suma(delTipo(acumulado, 'INGRESO'), saldo) - suma(delTipo(acumulado, 'GASTO'), saldo);
  const pat = bloque('Patrimonio', [...delTipo(acumulado, 'PATRIMONIO'),
    { codigo: '', nombre: 'Resultado del ejercicio', tipo: 'PATRIMONIO', debe: 0, haber: resultado }]);
  const pyp = pas.total + pat.total;
  $('#t-balance').innerHTML = tabla(cab3, act.html + pas.html + pat.html +
    `<tr class="total"><td colspan="2">Total pasivo + patrimonio</td>${money(pyp)}</tr>`);
  const ok = Math.abs(act.total - pyp) < 0.005;
  $('#cuadre').innerHTML = `<span class="chip ${ok ? 't-INGRESO' : 't-GASTO'}">${ok ? 'Cuadrado' : 'Descuadrado'}</span>`;
}
$('#r-generar').onclick = () => generarReportes().catch(fallo);

// ---------- exportación PDF / Excel ----------
document.addEventListener('click', e => {
  const b = e.target.closest('[data-exp]');
  if (!b) return;
  const t = document.getElementById(b.dataset.exp), titulo = t.dataset.titulo;
  if (!t.querySelector('td.num, tbody tr:not(:has(.vacio))')) return aviso('No hay datos para exportar', false);
  if (b.dataset.fmt === 'xlsx') {
    XLSX.writeFile(XLSX.utils.table_to_book(t, { sheet: titulo.slice(0, 31) }), `${titulo}.xlsx`);
  } else {
    const doc = new jspdf.jsPDF();
    doc.setFillColor(42, 85, 229); doc.rect(0, 0, 210, 4, 'F');
    doc.setFontSize(15); doc.text(titulo, 14, 16);
    doc.setFontSize(9); doc.setTextColor(107, 111, 142);
    doc.text(`Montos en córdobas (C$). Generado el ${new Date().toLocaleString('es')}`, 14, 22);
    doc.autoTable({ html: t, startY: 28, styles: { fontSize: 8 }, headStyles: { fillColor: [23, 26, 61] },
      alternateRowStyles: { fillColor: [244, 245, 251] } });
    doc.save(`${titulo}.pdf`);
  }
});

// ---------- inicio ----------
$('#r-hasta').value = hoy;
cargarCuentas().then(reiniciarAsiento).catch(fallo);
cargarInicio().catch(fallo);
