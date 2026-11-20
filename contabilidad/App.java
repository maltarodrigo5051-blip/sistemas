import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.sql.Date;
import java.util.*;

/** Servidor HTTP + API JSON del sistema contable. Ejecutar con run.ps1. */
public class App {
    static final String URL = "jdbc:h2:./data/contabilidad;DB_CLOSE_DELAY=-1";
    static final Set<String> TIPOS = Set.of("ACTIVO", "PASIVO", "PATRIMONIO", "INGRESO", "GASTO");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("test")) { selfTest(); return; }
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute("RUNSCRIPT FROM 'schema.sql' CHARSET 'UTF-8'");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/api/", App::api);
        server.createContext("/", App::estatico);
        server.start();
        System.out.println("Sistema contable en http://localhost:8080  (cierra esta ventana para apagarlo)");
        try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("http://localhost:8080")); }
        catch (Exception | Error e) { /* sin escritorio gráfico: el usuario abre la dirección a mano */ }
    }

    static Connection db() throws SQLException { return DriverManager.getConnection(URL, "sa", ""); }

    // ---------- API ----------
    static void api(HttpExchange ex) throws IOException {
        int code = 200;
        String body;
        try (Connection c = db()) {
            var q = params(ex.getRequestURI().getRawQuery());
            String ruta = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            body = switch (ruta) {
                case "GET /api/cuentas" -> json(c, """
                    SELECT JSON_ARRAYAGG(JSON_OBJECT('codigo': codigo, 'nombre': nombre, 'tipo': tipo) ORDER BY codigo)
                    FROM cuentas""");
                case "POST /api/cuentas" -> crearCuenta(c, params(leer(ex)));
                case "POST /api/asientos" -> crearAsiento(c, params(leer(ex)));
                case "GET /api/diario" -> json(c, """
                    SELECT JSON_ARRAYAGG(JSON_OBJECT('asiento': a.id, 'fecha': CAST(a.fecha AS VARCHAR),
                        'descripcion': a.descripcion, 'cuenta': m.cuenta_codigo, 'nombre': c.nombre,
                        'debe': m.debe, 'haber': m.haber) ORDER BY a.fecha, a.id, m.id)
                    FROM asientos a
                    JOIN movimientos m ON m.asiento_id = a.id
                    JOIN cuentas c ON c.codigo = m.cuenta_codigo
                    WHERE a.fecha BETWEEN ? AND ?""", fecha(q, "desde", "1900-01-01"), fecha(q, "hasta", "9999-12-31"));
                case "GET /api/saldos" -> json(c, """
                    SELECT JSON_ARRAYAGG(JSON_OBJECT('codigo': c.codigo, 'nombre': c.nombre, 'tipo': c.tipo,
                        'debe': COALESCE(s.debe, 0), 'haber': COALESCE(s.haber, 0)) ORDER BY c.codigo)
                    FROM cuentas c LEFT JOIN (
                        SELECT m.cuenta_codigo, SUM(m.debe) debe, SUM(m.haber) haber
                        FROM movimientos m JOIN asientos a ON a.id = m.asiento_id
                        WHERE a.fecha BETWEEN ? AND ?
                        GROUP BY m.cuenta_codigo) s ON s.cuenta_codigo = c.codigo""",
                    fecha(q, "desde", "1900-01-01"), fecha(q, "hasta", "9999-12-31"));
                case "GET /api/mensual" -> json(c, """
                    SELECT JSON_ARRAYAGG(JSON_OBJECT('mes': mes, 'ingresos': ing, 'gastos': gas) ORDER BY mes) FROM (
                        SELECT FORMATDATETIME(a.fecha, 'yyyy-MM') mes,
                            SUM(CASE WHEN c.tipo = 'INGRESO' THEN m.haber - m.debe ELSE 0 END) ing,
                            SUM(CASE WHEN c.tipo = 'GASTO' THEN m.debe - m.haber ELSE 0 END) gas
                        FROM asientos a
                        JOIN movimientos m ON m.asiento_id = a.id
                        JOIN cuentas c ON c.codigo = m.cuenta_codigo
                        WHERE a.fecha BETWEEN ? AND ?
                        GROUP BY mes)""", fecha(q, "desde", "1900-01-01"), fecha(q, "hasta", "9999-12-31"));
                default -> { code = 404; yield "{\"error\":\"Ruta no encontrada\"}"; }
            };
        } catch (IllegalArgumentException e) {
            code = 400;
            body = "{\"error\":" + quote(e.getMessage()) + "}";
        } catch (SQLException e) {
            code = 400;
            String msg = switch (e.getSQLState()) {
                case "23505" -> "Ya existe un registro con ese código";
                case "23506" -> "La cuenta indicada no existe";
                default -> e.getMessage();
            };
            body = "{\"error\":" + quote(msg) + "}";
        }
        send(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static String crearCuenta(Connection c, Map<String, List<String>> p) throws SQLException {
        String codigo = requerido(p, "codigo"), nombre = requerido(p, "nombre"), tipo = requerido(p, "tipo");
        if (!TIPOS.contains(tipo)) throw new IllegalArgumentException("Tipo de cuenta inválido");
        try (var st = c.prepareStatement("INSERT INTO cuentas VALUES (?, ?, ?)")) {
            st.setString(1, codigo); st.setString(2, nombre); st.setString(3, tipo);
            st.executeUpdate();
        }
        return "{\"ok\":true}";
    }

    static String crearAsiento(Connection c, Map<String, List<String>> p) throws SQLException {
        List<String> cuentas = p.getOrDefault("cuenta", List.of());
        List<BigDecimal[]> lineas = validar(p.getOrDefault("debe", List.of()), p.getOrDefault("haber", List.of()));
        if (cuentas.size() != lineas.size()) throw new IllegalArgumentException("Cada línea necesita una cuenta");
        Date fecha = fecha(p, "fecha", null);
        String descripcion = requerido(p, "descripcion");
        c.setAutoCommit(false);
        try {
            long id;
            try (var st = c.prepareStatement("INSERT INTO asientos(fecha, descripcion) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                st.setDate(1, fecha); st.setString(2, descripcion);
                st.executeUpdate();
                var rs = st.getGeneratedKeys(); rs.next(); id = rs.getLong(1);
            }
            try (var st = c.prepareStatement("INSERT INTO movimientos(asiento_id, cuenta_codigo, debe, haber) VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i < lineas.size(); i++) {
                    st.setLong(1, id); st.setString(2, cuentas.get(i));
                    st.setBigDecimal(3, lineas.get(i)[0]); st.setBigDecimal(4, lineas.get(i)[1]);
                    st.addBatch();
                }
                st.executeBatch();
            }
            c.commit();
            return "{\"id\":" + id + "}";
        } catch (SQLException e) {
            c.rollback();
            throw e;
        }
    }

    /** Partida doble: >= 2 líneas, cada una sólo en debe o sólo en haber, y sum(debe) == sum(haber). */
    static List<BigDecimal[]> validar(List<String> debe, List<String> haber) {
        if (debe.size() != haber.size() || debe.size() < 2) throw new IllegalArgumentException("El asiento necesita al menos 2 líneas");
        List<BigDecimal[]> out = new ArrayList<>();
        BigDecimal td = BigDecimal.ZERO, th = BigDecimal.ZERO;
        for (int i = 0; i < debe.size(); i++) {
            BigDecimal d = monto(debe.get(i)), h = monto(haber.get(i));
            if ((d.signum() > 0) == (h.signum() > 0)) throw new IllegalArgumentException("Línea " + (i + 1) + ": use debe o haber (sólo uno)");
            td = td.add(d); th = th.add(h);
            out.add(new BigDecimal[]{d, h});
        }
        if (td.compareTo(th) != 0) throw new IllegalArgumentException("Asiento descuadrado: debe " + td + " ≠ haber " + th);
        return out;
    }

    static BigDecimal monto(String s) {
        BigDecimal v = s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim());
        if (v.signum() < 0 || v.scale() > 2) throw new IllegalArgumentException("Monto inválido: " + s);
        return v;
    }

    // ---------- utilidades ----------
    static String json(Connection c, String sql, Object... ps) throws SQLException {
        try (var st = c.prepareStatement(sql)) {
            for (int i = 0; i < ps.length; i++) st.setObject(i + 1, ps[i]);
            try (var rs = st.executeQuery()) { rs.next(); String s = rs.getString(1); return s == null ? "[]" : s; }
        }
    }

    static String requerido(Map<String, List<String>> p, String k) {
        String v = p.getOrDefault(k, List.of("")).get(0).trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Falta el campo: " + k);
        return v;
    }

    static Date fecha(Map<String, List<String>> p, String k, String porDefecto) {
        String v = p.getOrDefault(k, List.of("")).get(0).trim();
        if (v.isEmpty() && porDefecto == null) throw new IllegalArgumentException("Falta el campo: " + k);
        return Date.valueOf(v.isEmpty() ? porDefecto : v); // IllegalArgumentException si el formato no es aaaa-mm-dd
    }

    static Map<String, List<String>> params(String raw) {
        Map<String, List<String>> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            m.computeIfAbsent(dec(p[0]), k -> new ArrayList<>()).add(p.length > 1 ? dec(p[1]) : "");
        }
        return m;
    }

    static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }

    static String leer(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static String quote(String s) {
        return "\"" + String.valueOf(s).replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\r\\n\\t]", " ") + "\"";
    }

    static void estatico(HttpExchange ex) throws IOException {
        Path base = Path.of("web").toAbsolutePath().normalize();
        String ruta = ex.getRequestURI().getPath();
        Path f = base.resolve(ruta.equals("/") ? "index.html" : ruta.substring(1)).normalize();
        if (!f.startsWith(base) || !Files.isRegularFile(f)) { send(ex, 404, "text/plain", "404".getBytes()); return; }
        String n = f.toString();
        String tipo = n.endsWith(".js") ? "text/javascript" : n.endsWith(".css") ? "text/css" : "text/html";
        send(ex, 200, tipo + "; charset=utf-8", Files.readAllBytes(f));
    }

    static void send(HttpExchange ex, int code, String tipo, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(code, body.length);
        try (var os = ex.getResponseBody()) { os.write(body); }
    }

    // ---------- autoprueba: java App.java test ----------
    static void selfTest() {
        validar(List.of("100.50", ""), List.of("", "100.50"));
        validar(List.of("60", "40", "0"), List.of("0", "0", "100"));
        String[][][] malos = {
            {{"100", ""}, {"", "90"}},        // descuadrado
            {{"100"}, {"100"}},               // una sola línea
            {{"100", ""}, {"100", "200"}},    // debe y haber en la misma línea
            {{"-5", ""}, {"", "-5"}},         // negativo
            {{"1.005", ""}, {"", "1.005"}},   // más de 2 decimales
            {{"", ""}, {"", ""}},             // vacío
        };
        for (var m : malos) {
            try { validar(List.of(m[0]), List.of(m[1])); throw new AssertionError("Debió fallar: " + Arrays.deepToString(m)); }
            catch (IllegalArgumentException ok) { }
        }
        System.out.println("Autoprueba OK");
    }
}
