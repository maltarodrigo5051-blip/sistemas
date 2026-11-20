# ⚙️ Sistemas de software

Programas que resuelven un trabajo concreto: llevar la contabilidad, cobrar en una caja, avisar de transmisiones en vivo y tener un asistente de voz. Tienen servidor, base de datos, validaciones y pruebas.

Cada carpeta de este repositorio tiene el código completo del proyecto.

| Proyecto | Para qué sirve | Tecnologías | Código |
|---|---|---|---|
| 📊 **Contabilidad** | Llevar la contabilidad de una empresa por partida doble | Java, SQL (H2), JavaScript | [contabilidad](contabilidad/) |
| 🧾 **Punto de venta** | Cobrar en una pulpería o minisúper con lector de código de barras | Python, SQLite, JavaScript | [lector-de-codigo-de-barra](lector-de-codigo-de-barra/) |
| 🔔 **Notis** | Avisar cuando tus streamers entran en vivo | Python, JavaScript | [notis](notis/) |
| 🎙️ **Josué** | Asistente de voz para Android con inteligencia artificial | Java, Android, Gemini API | [josue-asistente](josue-asistente/) |

---

## 📊 Contabilidad: sistema contable empresarial

**Para qué sirve:** registrar los ingresos y gastos de una empresa y sacar sus reportes financieros sin hacer cuentas a mano.

**Qué hace:**
- Cada ingreso o gasto genera su asiento contable automáticamente.
- El servidor rechaza asientos descuadrados, donde el debe no es igual al haber.
- Incluye libro diario con búsqueda, plan de cuentas, balance de comprobación, estado de resultados y balance general.
- Exporta cualquier tabla a **PDF y Excel**.
- El panel de inicio muestra la utilidad del mes, la caja y bancos, y las cuentas por cobrar y por pagar.

## 🧾 Punto de venta con lector de código de barras

**Para qué sirve:** la caja de una pulpería o un minisúper. Escaneas el producto, cobras y el inventario baja solo.

**Qué hace:**
- Funciona con cualquier lector USB. Distingue el lector del teclado por la velocidad con que escribe.
- Calcula el vuelto con botones de billetes e imprime el ticket.
- Avisa de los productos con poco stock.
- Cada venta es una sola transacción: si falta stock de un producto, no se descuenta nada.
- El dinero se guarda en centavos, sin errores de decimales, y tiene pruebas automáticas.

## 🔔 Notis: avisos de streams en vivo

**Para qué sirve:** te notifica cuando tus streamers favoritos de **Kick, Twitch y YouTube** empiezan a transmitir.

**Lo técnico:** un servidor en Python consulta el estado en vivo, lo que evita el bloqueo de CORS del navegador. No usa claves privadas y valida cada nombre de usuario.

## 🎙️ Josué: asistente de voz para Android

**Para qué sirve:** hablarle al teléfono o a la TV y que te responda en voz alta, manos libres.

**Qué hace:**
- Conversa con la IA de **Google Gemini** y te recuerda por tu nombre.
- Dice la hora, abre apps ("abre YouTube") y prepara mensajes de texto. Nunca envía nada sin que confirmes con un "sí".
- Se activa cuando escucha su nombre, y lo puedes interrumpir mientras habla.
- El mismo APK sirve para el teléfono y para la TV con Android.

---

### Ver el código en tu computadora

```bash
git clone https://github.com/maltarodrigo5051-blip/sistemas.git
```

📂 Otros grupos: [Páginas web](https://github.com/maltarodrigo5051-blip/paginas-web) · [Tiendas en línea](https://github.com/maltarodrigo5051-blip/tiendas-online)
