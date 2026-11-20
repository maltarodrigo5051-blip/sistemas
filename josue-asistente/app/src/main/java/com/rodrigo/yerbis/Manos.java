package com.rodrigo.yerbis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

/**
 * Las "manos" de Josué: un servicio de accesibilidad que toca, desliza y pulsa botones del sistema.
 * El usuario lo activa a mano en Ajustes > Accesibilidad; Android no deja que una app se lo dé sola.
 */
public class Manos extends AccessibilityService {

    /** null si el usuario no lo ha activado. */
    static volatile Manos activa;

    @Override protected void onServiceConnected() { activa = this; }
    @Override public void onDestroy() { activa = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    /** Ejecuta una acción con nombre. Devuelve el resultado en palabras para Gemini. */
    String hacer(String accion) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float cx = m.widthPixels / 2f, cy = m.heightPixels / 2f;
        switch (accion) {
            case "like":
                // Doble toque en el centro: así se da "me gusta" en TikTok, Instagram, Reels y Shorts.
                gesto(toque(cx, cy, 0), toque(cx, cy, 130));
                return "Di like con doble toque en el centro de la pantalla.";
            case "siguiente":
                gesto(trazo(cx, m.heightPixels * 0.75f, cx, m.heightPixels * 0.25f, 0, 250));
                return "Deslicé hacia arriba (siguiente).";
            case "anterior":
                gesto(trazo(cx, m.heightPixels * 0.25f, cx, m.heightPixels * 0.75f, 0, 250));
                return "Deslicé hacia abajo (anterior).";
            case "tocar_centro":
                gesto(toque(cx, cy, 0));
                return "Toqué el centro de la pantalla.";
            case "atras": return global(GLOBAL_ACTION_BACK, "Fui atrás.");
            case "inicio": return global(GLOBAL_ACTION_HOME, "Fui a la pantalla de inicio.");
            case "recientes": return global(GLOBAL_ACTION_RECENTS, "Abrí las apps recientes.");
            case "notificaciones": return global(GLOBAL_ACTION_NOTIFICATIONS, "Abrí las notificaciones.");
            case "ajustes_rapidos": return global(GLOBAL_ACTION_QUICK_SETTINGS, "Abrí los ajustes rápidos.");
            case "captura": return global(GLOBAL_ACTION_TAKE_SCREENSHOT, "Tomé una captura de pantalla.");
            case "bloquear": return global(GLOBAL_ACTION_LOCK_SCREEN, "Bloqueé la pantalla.");
            default: return "No sé hacer la acción " + accion + ".";
        }
    }

    /** Busca el botón Enviar de WhatsApp en la pantalla actual y lo toca. */
    boolean tocarEnviar() {
        android.view.accessibility.AccessibilityNodeInfo raiz = getRootInActiveWindow();
        if (raiz == null) return false;
        for (String id : new String[]{"com.whatsapp:id/send", "com.whatsapp.w4b:id/send"}) {
            for (android.view.accessibility.AccessibilityNodeInfo n : raiz.findAccessibilityNodeInfosByViewId(id)) {
                if (n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
        }
        return tocarPorDescripcion(raiz);
    }

    private static boolean tocarPorDescripcion(android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return false;
        CharSequence d = n.getContentDescription();
        if (d != null && n.isClickable()) {
            String s = d.toString().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("enviar") || s.equals("send")) return n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < n.getChildCount(); i++) if (tocarPorDescripcion(n.getChild(i))) return true;
        return false;
    }

    /** Captura de pantalla en JPEG para que Gemini la vea. null si Android no la da (necesita Android 11+). */
    byte[] captura() {
        if (android.os.Build.VERSION.SDK_INT < 30) return null;
        java.util.concurrent.CountDownLatch listo = new java.util.concurrent.CountDownLatch(1);
        byte[][] jpeg = new byte[1][];
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                r.getHardwareBuffer().close();
                if (hw != null) {
                    android.graphics.Bitmap bmp = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                    // Más chica = más rápida de enviar; 1024 px de alto basta para leer la pantalla.
                    float escala = Math.min(1f, 1024f / bmp.getHeight());
                    android.graphics.Bitmap chica = android.graphics.Bitmap.createScaledBitmap(bmp,
                            Math.round(bmp.getWidth() * escala), Math.round(bmp.getHeight() * escala), true);
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    chica.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out);
                    jpeg[0] = out.toByteArray();
                }
                listo.countDown();
            }
            @Override public void onFailure(int codigo) { listo.countDown(); }
        });
        try { listo.await(4, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignorada) { }
        return jpeg[0];
    }

    private String global(int accion, String ok) {
        return performGlobalAction(accion) ? ok : "Android no me dejó hacer esa acción.";
    }

    private static GestureDescription.StrokeDescription toque(float x, float y, long inicio) {
        Path p = new Path();
        p.moveTo(x, y);
        return new GestureDescription.StrokeDescription(p, inicio, 40);
    }

    private static GestureDescription.StrokeDescription trazo(float x1, float y1, float x2, float y2, long inicio, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return new GestureDescription.StrokeDescription(p, inicio, ms);
    }

    private void gesto(GestureDescription.StrokeDescription... trazos) {
        GestureDescription.Builder b = new GestureDescription.Builder();
        for (GestureDescription.StrokeDescription t : trazos) b.addStroke(t);
        dispatchGesture(b.build(), null, null);
    }
}
