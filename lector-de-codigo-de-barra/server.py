"""Lector de Código de Barra: punto de venta con lector de código de barras y SQLite.

Uso:  python -I server.py   ->  http://127.0.0.1:6100
Un lector USB funciona como un teclado: escribe el código y presiona Enter.
La página tiene un campo siempre enfocado que recibe eso.
"""
import json
import re
import sqlite3
import sys
import urllib.parse
from datetime import datetime
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DB_PATH = ROOT / "caja.db"
BARCODE_RE = re.compile(r"^[0-9A-Za-z\-]{3,32}$")  # EAN-13, UPC, Code128 simples

SCHEMA = """
CREATE TABLE IF NOT EXISTS products (
    barcode     TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    price_cents INTEGER NOT NULL CHECK (price_cents >= 0),
    stock       INTEGER NOT NULL CHECK (stock >= 0)
);
CREATE TABLE IF NOT EXISTS sales (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at  TEXT NOT NULL,
    total_cents INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sale_items (
    sale_id     INTEGER NOT NULL REFERENCES sales(id),
    barcode     TEXT NOT NULL,
    name        TEXT NOT NULL,
    qty         INTEGER NOT NULL,
    price_cents INTEGER NOT NULL
);
"""

DEMO = [
    ("7441001600011", "Agua Fuente Pura 600 ml", 2000, 48),
    ("7441002300015", "Coca-Cola 600 ml", 3000, 36),
    ("7441003500018", "Galletas Pozuelo", 1500, 24),
    ("7441004700012", "Arroz Faisán 1 lb", 2800, 30),
    ("7441005900016", "Frijoles rojos 1 lb", 3500, 30),
    ("7501055300075", "Jabón Protex", 4500, 12),
]


class SaleError(Exception):
    """Venta rechazada (producto inexistente, sin stock o cantidad inválida)."""


def connect(path=DB_PATH):
    con = sqlite3.connect(path, check_same_thread=False)
    con.row_factory = sqlite3.Row
    con.executescript(SCHEMA)
    if con.execute("SELECT COUNT(*) FROM products").fetchone()[0] == 0:
        con.executemany("INSERT INTO products VALUES (?,?,?,?)", DEMO)
        con.commit()
    return con


def get_product(con, barcode):
    row = con.execute("SELECT * FROM products WHERE barcode = ?", (barcode,)).fetchone()
    return dict(row) if row else None


def list_products(con):
    return [dict(r) for r in con.execute("SELECT * FROM products ORDER BY name")]


def save_product(con, barcode, name, price_cents, stock):
    if not BARCODE_RE.match(barcode or ""):
        raise SaleError("Código de barras inválido")
    name = (name or "").strip()
    if not name or len(name) > 80:
        raise SaleError("Nombre vacío o muy largo")
    if not isinstance(price_cents, int) or price_cents < 0:
        raise SaleError("Precio inválido")
    if not isinstance(stock, int) or stock < 0:
        raise SaleError("Stock inválido")
    with con:
        con.execute(
            "INSERT INTO products VALUES (?,?,?,?) ON CONFLICT(barcode) DO UPDATE SET "
            "name=excluded.name, price_cents=excluded.price_cents, stock=excluded.stock",
            (barcode, name, price_cents, stock),
        )
    return get_product(con, barcode)


def make_sale(con, items):
    """items = [{"barcode": str, "qty": int}]. Todo o nada: si falla una línea no se descuenta nada."""
    if not items:
        raise SaleError("La venta está vacía")
    qty_by_code = {}
    for it in items:
        code, qty = it.get("barcode"), it.get("qty")
        if not isinstance(qty, int) or qty <= 0:
            raise SaleError("Cantidad inválida")
        qty_by_code[code] = qty_by_code.get(code, 0) + qty
    with con:  # transacción: commit al final o rollback si hay excepción
        lines, total = [], 0
        for code, qty in qty_by_code.items():
            p = get_product(con, code)
            if not p:
                raise SaleError(f"Producto {code} no existe")
            if p["stock"] < qty:
                raise SaleError(f"Solo quedan {p['stock']} de {p['name']}")
            lines.append((code, p["name"], qty, p["price_cents"]))
            total += qty * p["price_cents"]
        cur = con.execute(
            "INSERT INTO sales (created_at, total_cents) VALUES (?, ?)",
            (datetime.now().isoformat(timespec="seconds"), total),
        )
        sale_id = cur.lastrowid
        for code, name, qty, price in lines:
            con.execute("INSERT INTO sale_items VALUES (?,?,?,?,?)", (sale_id, code, name, qty, price))
            con.execute("UPDATE products SET stock = stock - ? WHERE barcode = ?", (qty, code))
    return {"id": sale_id, "total_cents": total, "items": [
        {"barcode": c, "name": n, "qty": q, "price_cents": p} for c, n, q, p in lines
    ]}


def today_summary(con):
    day = datetime.now().date().isoformat()
    row = con.execute(
        "SELECT COUNT(*) AS sales, COALESCE(SUM(total_cents), 0) AS total_cents "
        "FROM sales WHERE created_at LIKE ?", (day + "%",)
    ).fetchone()
    low = [dict(r) for r in con.execute("SELECT barcode, name, stock FROM products WHERE stock <= 5 ORDER BY stock")]
    return {"date": day, **dict(row), "low_stock": low}


class Handler(SimpleHTTPRequestHandler):
    con = None  # se asigna al arrancar

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT / "public"), **kw)

    def log_message(self, fmt, *args):
        if "/api/" not in (args[0] if args else ""):
            super().log_message(fmt, *args)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/api/products":
            return self.send_json(200, list_products(self.con))
        if path.startswith("/api/products/"):
            code = urllib.parse.unquote(path.rsplit("/", 1)[1])
            if not BARCODE_RE.match(code):
                return self.send_json(400, {"error": "Código de barras inválido"})
            p = get_product(self.con, code)
            return self.send_json(200, p) if p else self.send_json(404, {"error": "No registrado"})
        if path == "/api/summary":
            return self.send_json(200, today_summary(self.con))
        return super().do_GET()

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        try:
            length = int(self.headers.get("Content-Length", 0))
            if length > 100_000:
                return self.send_json(413, {"error": "Petición muy grande"})
            data = json.loads(self.rfile.read(length) or b"{}")
            if path == "/api/products":
                p = save_product(self.con, data.get("barcode"), data.get("name"),
                                 data.get("price_cents"), data.get("stock"))
                return self.send_json(200, p)
            if path == "/api/sales":
                return self.send_json(200, make_sale(self.con, data.get("items") or []))
            return self.send_json(404, {"error": "Ruta no existe"})
        except SaleError as e:
            return self.send_json(400, {"error": str(e)})
        except (ValueError, AttributeError, TypeError):
            return self.send_json(400, {"error": "Datos mal formados"})

    def send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 6100
    Handler.con = connect()
    print(f"Lector de Código de Barra en http://127.0.0.1:{PORT}")
    # un solo hilo: es una caja, y así la conexión SQLite nunca se comparte entre hilos
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
