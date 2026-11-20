# Sistema de contabilidad empresarial

Aplicación web de contabilidad por partida doble: registro de ingresos y gastos, asientos con debe y haber, libro diario, plan de cuentas y reportes financieros con exportación a PDF y Excel.

**Tecnologías:** Java 22+ (servidor HTTP y API), SQL con base de datos H2 embebida, JavaScript, HTML y CSS (interfaz).

## Funciones

- **Panel de inicio:** utilidad del mes, ingresos frente a gastos, disponible en caja y bancos, cuentas por cobrar y por pagar, gráfico de los últimos 6 meses y últimos asientos.
- **Ingresos y gastos:** formulario rápido que genera el asiento contable automáticamente.
- **Asientos contables:** varias líneas de debe y haber; el servidor rechaza asientos descuadrados.
- **Libro diario:** filtro por fechas y búsqueda.
- **Plan de cuentas:** activo, pasivo, patrimonio, ingreso y gasto.
- **Reportes:** balance de comprobación, estado de resultados y balance general (con verificación de cuadre).
- **Exportación** de cada tabla a PDF y Excel.
- Modo claro y oscuro, diseño adaptable a celular, montos en córdobas (C$).

## Cómo ejecutarlo

Requiere Java 22 o superior.

```powershell
.\run.ps1
```

La primera vez descarga el driver de H2 desde Maven Central. Luego abre <http://localhost:8080>.

Autoprueba de las reglas de partida doble:

```powershell
.\run.ps1 test
```

## Estructura

| Archivo | Contenido |
|---|---|
| `App.java` | Servidor HTTP, API JSON y validación de asientos |
| `schema.sql` | Tablas (cuentas, asientos, movimientos) y plan de cuentas base |
| `web/index.html` | Interfaz y estilos |
| `web/app.js` | Lógica de la interfaz, reportes y exportación |
| `run.ps1` | Descarga H2 (si falta) y arranca el servidor |
