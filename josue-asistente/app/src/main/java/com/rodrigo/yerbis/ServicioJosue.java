package com.rodrigo.yerbis;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Mantiene a Josué vivo en segundo plano (Android lo exige con una notificación fija) y muestra
 * la burbuja flotante sobre las demás apps, como Siri.
 */
public class ServicioJosue extends Service implements Asistente.Vista {

    static volatile Asistente asistente;
    /** La app está en pantalla: entonces la burbuja se esconde para no verse doble. */
    static volatile boolean appVisible;
    private static volatile ServicioJosue instancia;

    private static final String CANAL = "josue";
    private static final String DETENER = "detener";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout burbuja;
    private Nucleo bolita;
    private TextView globo;
    private WindowManager.LayoutParams lp;
    private ConnectivityManager.NetworkCallback red;
    private final Runnable ocultarGlobo = () -> { if (globo != null) globo.setVisibility(View.GONE); };

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notificacion(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, notificacion());
        }
        if (asistente == null) asistente = new Asistente(this);
        asistente.agregarVista(this);
        vigilarConexion();
        ponerBurbuja();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && DETENER.equals(i.getAction())) {
            getSharedPreferences("yerbis", MODE_PRIVATE).edit().putBoolean("apagado", true).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        ponerBurbuja(); // por si el permiso de burbuja se dio después
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instancia = null;
        if (red != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(red);
        if (burbuja != null) wm.removeView(burbuja);
        if (asistente != null) { asistente.quitarVista(this); asistente.destruir(); asistente = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /** La app avisa cuando entra o sale de la pantalla. */
    static void appEnPantalla(boolean si) {
        appVisible = si;
        ServicioJosue s = instancia;
        if (s != null) s.ui.post(s::mostrarOcultarBurbuja);
    }

    // ---------- Notificación fija ----------

    private Notification notificacion() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Josué escuchando", NotificationManager.IMPORTANCE_LOW));
        PendingIntent abrir = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent detener = PendingIntent.getService(this, 1,
                new Intent(this, ServicioJosue.class).setAction(DETENER), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Josué está escuchando")
                .setContentText("Diga «Josué» en cualquier momento.")
                .setContentIntent(abrir)
                .addAction(new Notification.Action.Builder(null, "Detener", detener).build())
                .setOngoing(true)
                .build();
    }

    // ---------- Burbuja flotante ----------

    @SuppressLint("ClickableViewAccessibility")
    private void ponerBurbuja() {
        if (burbuja != null || !Settings.canDrawOverlays(this)) return;
        wm = getSystemService(WindowManager.class);
        burbuja = new LinearLayout(this);
        burbuja.setOrientation(LinearLayout.HORIZONTAL);
        burbuja.setGravity(Gravity.CENTER_VERTICAL);

        bolita = new Nucleo(this, null);
        bolita.setContentDescription("Josué. Toca para hablar.");
        burbuja.addView(bolita, new LinearLayout.LayoutParams(dp(76), dp(76)));

        globo = new TextView(this);
        globo.setBackgroundResource(R.drawable.tarjeta);
        globo.setTextColor(Color.parseColor("#E0F7FA"));
        globo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        globo.setPadding(dp(12), dp(8), dp(12), dp(8));
        globo.setMaxWidth(dp(230));
        globo.setMaxLines(4);
        globo.setVisibility(View.GONE);
        burbuja.addView(globo);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = asistente.prefs.getInt("burbujaX", dp(8));
        lp.y = asistente.prefs.getInt("burbujaY", dp(220));

        // Arrastrar para moverla; un toque corto la activa.
        // Mantener presionada: dormir o despertar a Josué.
        bolita.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0; int lx, ly; boolean movio, largo;
            final Runnable presionLarga = () -> {
                largo = true;
                if (asistente != null) asistente.alternarDormir();
            };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); lx = lp.x; ly = lp.y; movio = false; largo = false;
                        ui.postDelayed(presionLarga, 650);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - x0) + Math.abs(e.getRawY() - y0) > dp(8)) { movio = true; ui.removeCallbacks(presionLarga); }
                        lp.x = lx + (int) (e.getRawX() - x0);
                        lp.y = ly + (int) (e.getRawY() - y0);
                        wm.updateViewLayout(burbuja, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        ui.removeCallbacks(presionLarga);
                        if (movio) asistente.prefs.edit().putInt("burbujaX", lp.x).putInt("burbujaY", lp.y).apply();
                        else if (!largo && asistente != null) asistente.hablar();
                        return true;
                }
                return false;
            }
        });
        wm.addView(burbuja, lp);
        bolita.setEstado(Nucleo.Estado.ESPERA);
        mostrarOcultarBurbuja();
    }

    private void mostrarOcultarBurbuja() {
        if (burbuja != null) burbuja.setVisibility(appVisible ? View.GONE : View.VISIBLE);
    }

    // ---------- Asistente.Vista: la burbuja refleja lo que hace Josué ----------

    @Override public void modo(Asistente.Modo m) {
        if (bolita != null) bolita.setEstado(Nucleo.Estado.valueOf(m.name()));
    }

    @Override public void frase(String texto, boolean delUsuario) {
        if (globo == null || delUsuario) return;
        globo.setText(texto);
        globo.setVisibility(View.VISIBLE);
        ui.removeCallbacks(ocultarGlobo);
        ui.postDelayed(ocultarGlobo, 4000 + texto.length() * 45L);
    }

    @Override public void parcial(String texto) {}

    @Override public void nivel(float n) { if (bolita != null) bolita.setNivel(n); }

    private void vigilarConexion() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        asistente.conexion(cm.getActiveNetwork() != null);
        red = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { ui.post(() -> { if (asistente != null) asistente.conexion(true); }); }
            @Override public void onLost(Network n) { ui.post(() -> { if (asistente != null) asistente.conexion(false); }); }
        };
        cm.registerDefaultNetworkCallback(red);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
