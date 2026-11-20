package com.rodrigo.yerbis;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * La pantalla de Josué. Solo muestra: el cerebro (Asistente) vive en ServicioJosue para que
 * siga escuchando con la app cerrada. Esta pantalla se registra como Vista mientras se ve.
 */
public class MainActivity extends Activity implements Asistente.Vista {

    private static final String CIAN = "#00E5FF";
    private static final int ADJUNTAR = 7;
    // Gemini acepta hasta ~20 MB por mensaje; se deja margen para el resto de la conversación.
    private static final int MAXIMO_BYTES = 18 * 1024 * 1024;

    private Nucleo nucleo;
    private View puntoConexion;
    private TextView fraseJosue, fraseUsuario, estadoTv, textoConexion, resumenMemoria, estadoClave, estadoPermisos;
    private LinearLayout conversacion, listaVoces;
    private ScrollView panelChats;
    private View[] paneles;
    private TextView[] pestanas;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        nucleo = findViewById(R.id.nucleo);
        puntoConexion = findViewById(R.id.puntoConexion);
        fraseJosue = findViewById(R.id.fraseYerbis);
        fraseUsuario = findViewById(R.id.fraseUsuario);
        estadoTv = findViewById(R.id.estado);
        textoConexion = findViewById(R.id.textoConexion);
        resumenMemoria = findViewById(R.id.resumenMemoria);
        estadoClave = findViewById(R.id.estadoClave);
        estadoPermisos = findViewById(R.id.estadoPermisos);
        conversacion = findViewById(R.id.conversacion);
        listaVoces = findViewById(R.id.listaVoces);
        panelChats = findViewById(R.id.panelChats);

        prepararEncabezado(findViewById(R.id.titulo));
        prepararPestanas();
        prepararAjustes();
        prepararMemoria();
        nucleo.setOnClickListener(v -> { Asistente a = ServicioJosue.asistente; if (a != null) a.hablar(); else prender(); });
        findViewById(R.id.encendido).setOnClickListener(v -> { if (ServicioJosue.asistente != null) apagar(); else prender(); });
        nucleo.requestFocus(); // para el control remoto de la TV
        findViewById(R.id.adjuntar).setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "application/pdf", "text/plain", "audio/*"}), ADJUNTAR));
        recibirCompartido(getIntent());

        List<String> faltan = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.READ_CONTACTS);
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.SEND_SMS);
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.POST_NOTIFICATIONS);
        if (getSharedPreferences("yerbis", MODE_PRIVATE).getBoolean("apagado", false)) mostrarApagado();
        else if (faltan.isEmpty()) arrancarServicio();
        else requestPermissions(faltan.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permisos, int[] resultados) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) arrancarServicio();
        else estadoTv.setText("Sin permiso de micrófono no puedo escucharle.");
    }

    /** Botón ⏻: apaga a Josué por completo (deja de escuchar y quita la burbuja y la notificación). */
    private void apagar() {
        Asistente a = ServicioJosue.asistente;
        if (a != null) a.quitarVista(this);
        getSharedPreferences("yerbis", MODE_PRIVATE).edit().putBoolean("apagado", true).apply();
        stopService(new Intent(this, ServicioJosue.class));
        mostrarApagado();
    }

    private void prender() {
        getSharedPreferences("yerbis", MODE_PRIVATE).edit().putBoolean("apagado", false).apply();
        ((android.widget.ImageView) findViewById(R.id.encendido)).setColorFilter(Color.parseColor(CIAN));
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) arrancarServicio();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    private void mostrarApagado() {
        nucleo.setEstado(Nucleo.Estado.SIN_CONEXION);
        nucleo.setNivel(0f);
        estadoTv.setText("Apagado · toca el botón de encendido");
        textoConexion.setText("APAGADO");
        textoConexion.setTextColor(Color.parseColor("#FF5252"));
        puntoConexion.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")));
        fraseJosue.setText("Zzz… Estoy apagado, bro. Toca el botón de encendido cuando me ocupes.");
        ((android.widget.ImageView) findViewById(R.id.encendido)).setColorFilter(Color.parseColor("#FF5252"));
    }

    /** El servicio solo se puede arrancar con la app en pantalla; después sigue solo. */
    private void arrancarServicio() {
        Intent i = new Intent(this, ServicioJosue.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        // El asistente se crea dentro del servicio: esperar un momento para engancharse.
        nucleo.postDelayed(this::engancharse, 300);
    }

    private void engancharse() {
        Asistente a = ServicioJosue.asistente;
        if (a == null) { nucleo.postDelayed(this::engancharse, 300); return; }
        a.quitarVista(this);
        a.agregarVista(this);
        mostrarConexion(a.enLinea);
        pintarEstadoClave();
        if (a.claveGuardada().isEmpty()) mostrarPanel(4);
    }

    @Override protected void onStart() {
        super.onStart();
        ServicioJosue.appEnPantalla(true);
        if (ServicioJosue.asistente != null) engancharse();
        pintarPermisos();
    }

    @Override protected void onStop() {
        super.onStop();
        ServicioJosue.appEnPantalla(false);
        Asistente a = ServicioJosue.asistente;
        if (a != null) a.quitarVista(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // El botón de búsqueda/micrófono de algunos controles de TV también activa a Josué.
        if (keyCode == KeyEvent.KEYCODE_SEARCH && ServicioJosue.asistente != null) { ServicioJosue.asistente.hablar(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- Fotos, videos y documentos ----------

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        recibirCompartido(i);
    }

    @Override protected void onActivityResult(int code, int resultado, Intent datos) {
        super.onActivityResult(code, resultado, datos);
        if (code == ADJUNTAR && resultado == RESULT_OK && datos != null && datos.getData() != null) leerYAnalizar(datos.getData());
    }

    /** Algo compartido desde otra app con el botón "Compartir". */
    private void recibirCompartido(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) { leerYAnalizar(u); return; }
        String texto = i.getStringExtra(Intent.EXTRA_TEXT);
        if (texto != null) esperarAsistente(a -> a.analizar(texto.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain", "texto compartido"));
    }

    private void leerYAnalizar(Uri u) {
        new Thread(() -> {
            String tipo = getContentResolver().getType(u);
            String nombre = "archivo";
            try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) nombre = c.getString(0);
            } catch (Exception ignorada) { }
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAXIMO_BYTES) {
                        runOnUiThread(() -> esperarAsistente(a -> a.decir("Ese archivo está muy pesado, bro. Pásame uno de menos de 18 megas.", false)));
                        return;
                    }
                }
                String t = tipo == null ? "application/octet-stream" : tipo, nom = nombre;
                byte[] datos = out.toByteArray();
                runOnUiThread(() -> esperarAsistente(a -> a.analizar(datos, t, nom)));
            } catch (Exception e) {
                runOnUiThread(() -> esperarAsistente(a -> a.decir("No pude abrir ese archivo, wey.", false)));
            }
        }).start();
    }

    /** El servicio tarda un instante en crear el asistente cuando la app recién abre. */
    private void esperarAsistente(java.util.function.Consumer<Asistente> hacer) {
        Asistente a = ServicioJosue.asistente;
        if (a != null) hacer.accept(a);
        else nucleo.postDelayed(() -> esperarAsistente(hacer), 300);
    }

    // ---------- Asistente.Vista ----------

    @Override public void modo(Asistente.Modo m) {
        nucleo.setEstado(Nucleo.Estado.valueOf(m.name()));
        if (m != Asistente.Modo.ESCUCHANDO && m != Asistente.Modo.HABLANDO) nucleo.setNivel(0f);
        mostrarConexion(m != Asistente.Modo.SIN_CONEXION);
        String texto;
        switch (m) {
            case ESPERA: texto = "Diga «Josué»"; break;
            case ESCUCHANDO: texto = "Escuchando"; break;
            case PENSANDO: texto = "Pensando"; break;
            case SIN_CONEXION: texto = "Sin conexión"; break;
            default: texto = "";
        }
        Asistente a = ServicioJosue.asistente;
        if (a != null && a.silenciado() && m == Asistente.Modo.ESPERA) texto = "Modo silencio · diga «Josué»";
        String t = texto;
        estadoTv.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            estadoTv.setText(t);
            estadoTv.animate().alpha(1f).setDuration(250).start();
        }).start();
    }

    @Override public void frase(String texto, boolean delUsuario) {
        if (delUsuario) fraseUsuario.setText("Tú: " + texto);
        else escribir(fraseJosue, texto);
        agregarAlChat(texto, delUsuario);
    }

    @Override public void parcial(String texto) { fraseUsuario.setText(texto); }

    @Override public void nivel(float n) { nucleo.setNivel(n); }

    @Override public void pedirAjustes() { mostrarPanel(4); }

    /** Escribe la frase letra por letra, como una máquina de escribir. */
    private void escribir(TextView tv, String texto) {
        Object anterior = tv.getTag();
        if (anterior instanceof Runnable) tv.removeCallbacks((Runnable) anterior);
        final int[] i = {0};
        Runnable paso = new Runnable() {
            @Override public void run() {
                if (i[0] > texto.length()) return;
                tv.setText(texto.substring(0, i[0]));
                i[0] += 2;
                tv.postDelayed(this, 18);
            }
        };
        tv.setTag(paso);
        paso.run();
    }

    // ---------- Encabezado ----------

    private void prepararEncabezado(TextView titulo) {
        float ancho = titulo.getPaint().measureText(titulo.getText().toString());
        titulo.getPaint().setShader(new LinearGradient(0, 0, ancho, 0,
                new int[]{Color.parseColor(CIAN), Color.parseColor("#2979FF"), Color.parseColor("#B388FF")},
                null, Shader.TileMode.CLAMP));
        titulo.setShadowLayer(14f, 0f, 0f, Color.parseColor("#8000E5FF"));
        ObjectAnimator p = ObjectAnimator.ofFloat(puntoConexion, View.ALPHA, 1f, 0.25f);
        p.setDuration(1100);
        p.setRepeatMode(ValueAnimator.REVERSE);
        p.setRepeatCount(ValueAnimator.INFINITE);
        p.start();
    }

    private void mostrarConexion(boolean si) {
        int color = Color.parseColor(si ? "#00E676" : "#607D8B");
        textoConexion.setText(si ? "EN LÍNEA" : "SIN CONEXIÓN");
        textoConexion.setTextColor(color);
        puntoConexion.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
    }

    // ---------- Navegación inferior ----------

    private void prepararPestanas() {
        paneles = new View[]{findViewById(R.id.panelInicio), panelChats, findViewById(R.id.panelVoz),
                findViewById(R.id.panelMemoria), findViewById(R.id.panelAjustes)};
        pestanas = new TextView[]{findViewById(R.id.tabInicio), findViewById(R.id.tabChats), findViewById(R.id.tabVoz),
                findViewById(R.id.tabMemoria), findViewById(R.id.tabAjustes)};
        for (int i = 0; i < pestanas.length; i++) {
            int n = i;
            pestanas[i].setOnClickListener(v -> mostrarPanel(n));
        }
        mostrarPanel(0);
    }

    private void mostrarPanel(int n) {
        for (int i = 0; i < paneles.length; i++) {
            paneles[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
            pestanas[i].setTextColor(Color.parseColor(i == n ? CIAN : "#4D8A94"));
        }
        if (n == 1) panelChats.post(() -> panelChats.fullScroll(View.FOCUS_DOWN));
        if (n == 2) pintarVoces();
        if (n == 3) pintarMemoria();
        if (n == 4) pintarPermisos();
    }

    // ---------- Sección Voz ----------

    private void pintarVoces() {
        listaVoces.removeAllViews();
        Asistente a = ServicioJosue.asistente;
        if (a == null) return;
        List<String> opciones = opcionesDeVoz(a);
        String actual = a.vozElegida();
        for (String v : opciones) {
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            fila.setGravity(Gravity.CENTER_VERTICAL);
            fila.setBackgroundResource(R.drawable.fila);
            fila.setSelected(v.equals(actual));
            fila.setPadding(dp(16), dp(12), dp(8), dp(12));

            TextView nombreVoz = new TextView(this);
            nombreVoz.setText((v.equals(actual) ? "✓  " : "    ") + nombreDeVoz(v, opciones.indexOf(v)));
            nombreVoz.setTextColor(Color.parseColor(v.equals(actual) ? CIAN : "#E0F7FA"));
            nombreVoz.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            fila.addView(nombreVoz, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            Button probar = new Button(this);
            probar.setText("▶");
            probar.setBackgroundResource(R.drawable.boton_guardar);
            probar.setTextColor(Color.WHITE);
            probar.setContentDescription("Escuchar " + nombreDeVoz(v, opciones.indexOf(v)));
            probar.setOnClickListener(x -> a.probarVoz(v));
            fila.addView(probar, new LinearLayout.LayoutParams(dp(48), dp(40)));

            fila.setOnClickListener(x -> { a.prefs.edit().putString("vozIA", v).apply(); pintarVoces(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            listaVoces.addView(fila, lp);
        }
    }

    private static List<String> opcionesDeVoz(Asistente a) {
        List<String> o = new ArrayList<>();
        o.add(VozIA.VOCES[0]); // Algieba: voz principal
        o.add(VozIA.VOCES[1]);
        o.add(VozIA.VOCES[2]);
        if (a.fishListo()) o.add(Asistente.VOZ_FISH);
        o.add(Asistente.VOZ_DEL_TELEFONO);
        return o;
    }

    private static String nombreDeVoz(String v, int i) {
        if (v.equals(Asistente.VOZ_DEL_TELEFONO)) return "Voz del teléfono (gratis, sin límite)";
        if (v.equals(Asistente.VOZ_FISH)) return "Fish Audio";
        return (i == 0 ? "Voz principal · " : "Voz alternativa " + i + " · ") + v;
    }

    // ---------- Sección Memoria ----------

    private void prepararMemoria() {
        EditText campo = findViewById(R.id.campoNombre);
        findViewById(R.id.guardarNombre).setOnClickListener(v -> {
            String n = Texto.extraerNombre(campo.getText().toString());
            Asistente a = ServicioJosue.asistente;
            if (n.isEmpty() || a == null) return;
            a.cambiarNombre(n);
            pintarMemoria();
        });
        findViewById(R.id.olvidarChat).setOnClickListener(v -> {
            Asistente a = ServicioJosue.asistente;
            if (a == null) return;
            conversacion.removeAllViews();
            a.olvidarConversacion();
            pintarMemoria();
        });
        findViewById(R.id.olvidarTodo).setOnClickListener(v -> {
            Asistente a = ServicioJosue.asistente;
            if (a == null) return;
            mostrarPanel(0);
            a.borrarTodo();
        });
    }

    private void pintarMemoria() {
        Asistente a = ServicioJosue.asistente;
        if (a == null) return;
        ((EditText) findViewById(R.id.campoNombre)).setText(a.nombre == null ? "" : a.nombre);
        resumenMemoria.setText(a.mensajes == 0 ? "Todavía no hemos conversado en esta sesión."
                : "Recuerdo " + a.mensajes + " mensajes de esta sesión. Al reiniciar solo guardo su nombre y su voz.");
    }

    // ---------- Sección Ajustes ----------

    private void prepararAjustes() {
        EditText clave = findViewById(R.id.clave);
        findViewById(R.id.guardarClave).setOnClickListener(v -> {
            String k = clave.getText().toString().trim();
            Asistente a = ServicioJosue.asistente;
            if (k.isEmpty() || a == null) return;
            a.claveNueva(k);
            clave.setText("");
            pintarEstadoClave();
            mostrarPanel(0);
        });
        findViewById(R.id.permisoBurbuja).setOnClickListener(v -> abrir(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.permisoManos).setOnClickListener(v -> abrir(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.permisoAvisos).setOnClickListener(v -> abrir(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.permisoSilencio).setOnClickListener(v -> abrir(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        findViewById(R.id.permisoBateria).setOnClickListener(v -> abrir(
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
    }

    private void abrir(Intent i) {
        try { startActivity(i); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void pintarEstadoClave() {
        Asistente a = ServicioJosue.asistente;
        boolean sin = a == null || a.claveGuardada().isEmpty();
        estadoClave.setText(sin ? "Sin clave: sácala gratis en aistudio.google.com/apikey"
                : "✓ Clave guardada en este teléfono. Pega otra para reemplazarla.");
    }

    private void pintarPermisos() {
        if (estadoPermisos == null) return;
        boolean burbuja = Settings.canDrawOverlays(this);
        boolean manos = Manos.activa != null;
        String avisosActivos = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean avisos = avisosActivos != null && avisosActivos.contains(new ComponentName(this, Avisos.class).flattenToString());
        boolean silencio = getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
        boolean bateria = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        estadoPermisos.setText(marca(burbuja) + "Burbuja flotante\n" + marca(manos) + "Controlar la pantalla\n"
                + marca(avisos) + "Notificaciones\n" + marca(silencio) + "Silencio del teléfono\n"
                + marca(bateria) + "Sin límite de batería");
        if (burbuja) startService(new Intent(this, ServicioJosue.class)); // pone la burbuja si se acaba de permitir
    }

    private static String marca(boolean si) { return si ? "✓  " : "✗  "; }

    // ---------- Chats ----------

    private void agregarAlChat(String texto, boolean delUsuario) {
        TextView tv = new TextView(this);
        tv.setText(delUsuario ? texto : "✦  " + texto);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        if (delUsuario) {
            tv.setBackgroundResource(R.drawable.burbuja_usuario);
            tv.setTextColor(Color.parseColor("#E0F7FA"));
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            lp.gravity = Gravity.END;
            lp.leftMargin = dp(48);
        } else {
            tv.setTextColor(Color.parseColor("#B2EBF2"));
            tv.setPadding(0, dp(4), dp(24), dp(4));
        }
        conversacion.addView(tv, lp);
    }

    private int dp(int valor) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valor, getResources().getDisplayMetrics()));
    }
}
