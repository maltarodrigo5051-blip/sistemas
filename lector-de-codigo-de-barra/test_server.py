"""Pruebas de la lógica de ventas.  Uso:  python -m unittest test_server -v"""
import unittest

import server


class VentasTest(unittest.TestCase):
    def setUp(self):
        self.con = server.connect(":memory:")  # base nueva con los productos demo

    def stock(self, code):
        return server.get_product(self.con, code)["stock"]

    def test_venta_suma_total_y_baja_stock(self):
        sale = server.make_sale(self.con, [
            {"barcode": "7441001600011", "qty": 2},  # agua 20.00
            {"barcode": "7441002300015", "qty": 1},  # coca 30.00
        ])
        self.assertEqual(sale["total_cents"], 2 * 2000 + 3000)
        self.assertEqual(self.stock("7441001600011"), 46)
        self.assertEqual(self.stock("7441002300015"), 35)

    def test_mismo_codigo_dos_veces_se_junta(self):
        sale = server.make_sale(self.con, [
            {"barcode": "7441003500018", "qty": 1},
            {"barcode": "7441003500018", "qty": 2},
        ])
        self.assertEqual(len(sale["items"]), 1)
        self.assertEqual(sale["items"][0]["qty"], 3)

    def test_sin_stock_no_descuenta_nada(self):
        with self.assertRaises(server.SaleError):
            server.make_sale(self.con, [
                {"barcode": "7441001600011", "qty": 1},
                {"barcode": "7501055300075", "qty": 999},  # solo hay 12
            ])
        self.assertEqual(self.stock("7441001600011"), 48)  # rollback

    def test_producto_inexistente_y_cantidad_mala(self):
        for items in ([{"barcode": "000", "qty": 1}], [{"barcode": "7441001600011", "qty": 0}], []):
            with self.assertRaises(server.SaleError):
                server.make_sale(self.con, items)

    def test_guardar_producto_valida(self):
        p = server.save_product(self.con, "1234567890128", "Leche", 4000, 10)
        self.assertEqual(p["price_cents"], 4000)
        with self.assertRaises(server.SaleError):
            server.save_product(self.con, "../x", "Malo", 100, 1)
        with self.assertRaises(server.SaleError):
            server.save_product(self.con, "1234567890128", "Leche", -1, 1)

    def test_resumen_del_dia(self):
        server.make_sale(self.con, [{"barcode": "7441004700012", "qty": 1}])
        s = server.today_summary(self.con)
        self.assertEqual(s["sales"], 1)
        self.assertEqual(s["total_cents"], 2800)


if __name__ == "__main__":
    unittest.main()
