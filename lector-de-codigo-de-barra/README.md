# Lector de Código de Barra

Punto de venta para una pulpería o minisúper: escaneás el código de barras, el producto se suma a la venta, cobrás y el stock baja solo.

**Qué demuestra:**
- Un servidor en Python (solo biblioteca estándar) con una API JSON y base de datos SQLite.
- Ventas en una transacción: si un producto no tiene stock suficiente, no se descuenta nada.
- El dinero se guarda en centavos (enteros), nunca con decimales flotantes.
- Validación de códigos de barras, precios y cantidades en el servidor, no solo en la página.
- Pruebas automáticas de la lógica de ventas con `unittest`.

**Cómo se conecta el lector USB:**
- **Modo teclado (el de casi todos los lectores):** solo lo enchufás. La caja mide el tiempo entre teclas: un lector escribe el código en pocos milisegundos y una persona no. Así distingue el lector, muestra "Lector USB listo" y lee el código aunque el cursor esté en otro lado.
- **Modo puerto serie (COM):** el botón **Conectar por USB** usa Web Serial (Chrome o Edge) para leer el lector directamente.
- Sin lector, se puede escribir el código y presionar Enter.

**En la caja:**
- Cada escaneo hace un pitido y el producto entra a la venta con una animación. Si hay error, suena grave, el campo tiembla y sale un aviso.
- **Acceso rápido:** botones para vender productos sin escanear (pan, productos sin código).
- **El cliente paga con:** botones de billetes (100, 200, 500, 1000 o Exacto) y el vuelto se calcula solo.
- **F2** cobra y abre una ventana con el ticket, que se puede imprimir. **Enter** empieza la siguiente venta.
- **Modo claro y oscuro:** el botón del sol o la luna cambia el tema y lo recuerda. La primera vez usa el modo de Windows.
- Arriba se ven las ventas del día, lo vendido y cuántos productos tienen poco stock (5 o menos).
- Código no registrado → abre Productos con el código ya puesto. En **Productos**, escanear un código carga sus datos para editarlo.

## Cómo correrlo

Requiere Python 3. No hay que instalar nada.

```powershell
python -I server.py
```

Después abrí http://127.0.0.1:6100. La primera vez crea `caja.db` con 6 productos de ejemplo.

## Pruebas

```powershell
python -m unittest test_server -v
```

## API

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/api/products` | Lista de productos |
| GET | `/api/products/{código}` | Un producto (404 si no existe) |
| POST | `/api/products` | Crea o actualiza `{barcode, name, price_cents, stock}` |
| POST | `/api/sales` | Cobra `{items: [{barcode, qty}]}` y descuenta stock |
| GET | `/api/summary` | Ventas de hoy y productos con poco stock |
