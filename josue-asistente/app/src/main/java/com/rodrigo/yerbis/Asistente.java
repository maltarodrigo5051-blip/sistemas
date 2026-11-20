package com.rodrigo.yerbis;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * El cerebro de Josué: escucha, piensa con Gemini y habla. No depende de ninguna pantalla,
 * así puede vivir dentro del servicio de segundo plano. Las pantallas (la app y la burbuja)
 * se registran como "Vista" para enterarse de lo que pasa.
 */
final class Asistente {

    enum Modo { ESPERA, ESCUCHANDO, PENSANDO, HABLANDO, SIN_CONEXION, ERROR }

    interface Vista {
        void modo(Modo m);
        void frase(String texto, boolean delUsuario);
        void parcial(String texto);
        void nivel(float n);
        default void pedirAjustes() {}
    }

    private enum Estado { PIDE_NOMBRE, PIDE_PERMISO_NOMBRE, CHAT, CONFIRMAR }

    static final String VOZ_DEL_TELEFONO = "telefono";
    static final String VOZ_FISH = "fish";

    private final Context ctx;
    final SharedPreferences prefs;
    private final Comandos comandos;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService red = Executors.newSingleThreadExecutor();
    private final ExecutorService audio = Executors.newSingleThreadExecutor();
    private final List<Vista> vistas = new CopyOnWriteArrayList<>();
    private final Runnable volverAEscuchar = this::escuchar;

    private TextToSpeech voz;
    private SpeechRecognizer oido;
    private Gemini gemini;
    private Estado estado = Estado.CHAT;
    String nombre;
    private String nombreTentativo;
    private Gemini.Llamada pendiente;
    private boolean escucharAlTerminar, vozLista, iniciado, hablando, apagado;
    /** false = en espera: escucha siempre, pero solo reacciona si lo llaman por su nombre. */
    private boolean conversacionActiva;
    /** Solo "adiós" lo duerme; entonces espera a que digan "Josué" (o toquen la burbuja). */
    private boolean dormido;
    private String vozDemo;
    private volatile int turnoVoz;
    boolean enLinea = true;
    Modo modoActual = Modo.ESPERA;
    int mensajes;

    Asistente(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("yerbis", Context.MODE_PRIVATE);
        nombre = prefs.getString("nombre", null);
        comandos = new Comandos(ctx);
        voz = new TextToSpeech(ctx, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            voz.setLanguage(new Locale("es"));
            ponerVozDelTelefono();
            voz.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { ui.post(Asistente.this::alTerminarDeHablar); }
                @Override public void onError(String id) { ui.post(Asistente.this::alTerminarDeHablar); }
            });
            vozLista = true;
            iniciar();
        });
    }

    void agregarVista(Vista v) { vistas.add(v); v.modo(modoActual); }
    void quitarVista(Vista v) { vistas.remove(v); }

    /** Modo silencio: Josué sigue escuchando y respondiendo, pero solo por texto. */
    boolean silenciado() { return prefs.getBoolean("silencio", false); }

    void silenciar(boolean si) {
        prefs.edit().putBoolean("silencio", si).apply();
        if (si) { turnoVoz++; voz.stop(); }
    }

    private void iniciar() {
        if (!vozLista || iniciado) return;
        iniciado = true;
        conversacionActiva = true;
        if (nombre == null) {
            estado = Estado.PIDE_NOMBRE;
            decir("¡Hola! Soy Josué, tu nuevo asistente personal. Antes de comenzar, ¿cómo te llamas?", true);
        } else {
            estado = Estado.CHAT;
            int hora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            // "Señor Rodrigo" solo en los buenos días; el resto del día, de compa.
            decir(hora >= 5 && hora < 12 ? "Buenos días, señor " + nombre + ". Tienes " + comandos.bateria() + " ¿Qué hacemos hoy?"
                    : "¡Qué onda, " + nombre + "! Aquí Josué, ¿qué se te ofrece, bro?", true);
        }
    }

    /** Tocar el núcleo o la burbuja: interrumpe y escucha. */
    void hablar() {
        apagado = false;
        dormido = false;
        escucharAlTerminar = false;
        turnoVoz++;
        voz.stop();
        hablando = false;
        conversacionActiva = true;
        escuchar();
    }

    /** Mantener presionada la burbuja: si está despierto se duerme, si está dormido despierta. */
    void alternarDormir() {
        vibrar(60);
        if (dormido) { hablar(); return; }
        dormido = true;
        decir("Va, me duermo. Tócame o dime Josué cuando me ocupes.", false);
    }

    /** Vibración cortita: para que sepas que te oyó. */
    private void vibrar(long ms) {
        android.os.Vibrator v = ctx.getSystemService(android.os.Vibrator.class);
        if (v != null && v.hasVibrator()) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
    }

    /** Deja de escuchar por completo (botón Detener de la notificación). */
    void apagar() {
        apagado = true;
        ui.removeCallbacks(volverAEscuchar);
        turnoVoz++;
        voz.stop();
        if (oido != null) { oido.destroy(); oido = null; }
    }

    void destruir() {
        apagar();
        voz.shutdown();
        red.shutdownNow();
        audio.shutdownNow();
    }

    // ---------- Voz ----------

    String vozElegida() { return prefs.getString("vozIA", VozIA.VOCES[0]); }

    private void ponerVozDelTelefono() {
        // Tono y velocidad normales: bajarle el tono la hacía sonar robótica.
        voz.setPitch(1.0f);
        voz.setSpeechRate(1.05f);
        Set<Voice> todas = voz.getVoices();
        if (todas == null) return;
        // es-us-x-esd es la voz masculina latina de Google. La versión "network" es neuronal (mucho más
        // natural), gratis y sin límite diario; si no hay internet, Android usa sola la versión local.
        Voice local = null;
        for (Voice v : todas) {
            if ("es-us-x-esd-network".equals(v.getName())) { voz.setVoice(v); return; }
            if ("es-us-x-esd-local".equals(v.getName())) local = v;
        }
        if (local != null) voz.setVoice(local);
    }

    void probarVoz(String v) {
        vozDemo = v;
        decir("¿Qué onda, bro? Así sueno yo.", false);
    }

    void decir(String texto, boolean escucharDespues) {
        avisarFrase(texto, false);
        escucharAlTerminar = escucharDespues;
        hablando = true;
        ui.removeCallbacks(volverAEscuchar);
        if (oido != null) oido.cancel(); // que no se escuche a sí mismo
        int turno = ++turnoVoz;
        if (silenciado() && vozDemo == null) {
            // En silencio no suena: se deja un momento para leer y se sigue escuchando.
            ui.postDelayed(() -> { if (turno == turnoVoz) alTerminarDeHablar(); }, 400);
            return;
        }
        cambiarModo(Modo.HABLANDO);
        String k = claveGuardada();
        String elegida = vozDemo != null ? vozDemo : vozElegida();
        vozDemo = null;
        boolean usaFish = elegida.equals(VOZ_FISH) && fishListo();
        // El plan gratis de Gemini da pocas frases de voz IA al día; al acabarse, no perder tiempo intentándolo.
        boolean geminiSinCuota = System.currentTimeMillis() < prefs.getLong("vozIASinCuotaHasta", 0);
        if (!usaFish && (k.isEmpty() || geminiSinCuota || elegida.equals(VOZ_DEL_TELEFONO) || elegida.equals(VOZ_FISH))) {
            decirConTelefono(texto);
            return;
        }
        String claveFish = prefs.getString("fishClave", ""), idFish = prefs.getString("fishVoz", "");
        audio.execute(() -> {
            try {
                byte[] pcm = usaFish ? VozFish.generar(claveFish, idFish, texto) : VozIA.generar(k, elegida, texto);
                if (turno != turnoVoz) return;
                VozIA.reproducir(pcm, () -> turno == turnoVoz, n -> ui.post(() -> avisarNivel(n)));
                ui.post(() -> { if (turno == turnoVoz) alTerminarDeHablar(); });
            } catch (Exception e) {
                Log.w("YERBIS", "voz IA falló, uso la del teléfono: " + e.getMessage());
                if (!usaFish && String.valueOf(e.getMessage()).startsWith("TTS 429")) {
                    long espera = Texto.segundosDeEspera(e.getMessage(), 3600);
                    prefs.edit().putLong("vozIASinCuotaHasta", System.currentTimeMillis() + espera * 1000).apply();
                }
                ui.post(() -> { if (turno == turnoVoz) decirConTelefono(texto); });
            }
        });
    }

    private void decirConTelefono(String texto) {
        voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "josue" + System.nanoTime());
    }

    private void alTerminarDeHablar() {
        if (!hablando) return;
        hablando = false;
        conversacionActiva = !dormido; // escucha todo lo que digas mientras no le digas adiós
        escuchar();
    }

    // ---------- Oído ----------

    private void escuchar() {
        ui.removeCallbacks(volverAEscuchar);
        if (hablando || !iniciado || apagado) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Esperar más silencio antes de cortar, para no interrumpir al usuario a media frase.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L);
        // Un reconocedor nuevo cada vez: reusar uno recién cancelado da ERROR_CLIENT (5) en Xiaomi.
        if (oido != null) oido.destroy();
        oido = SpeechRecognizer.createSpeechRecognizer(ctx);
        oido.setRecognitionListener(escucha);
        oido.startListening(i);
        cambiarModo(conversacionActiva ? Modo.ESCUCHANDO : Modo.ESPERA);
    }

    private void reintentar(long ms) {
        ui.removeCallbacks(volverAEscuchar);
        ui.postDelayed(volverAEscuchar, ms);
    }

    private final RecognitionListener escucha = new RecognitionListener() {
        @Override public void onResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String dicho = r == null || r.isEmpty() ? "" : r.get(0);
            boolean meLlaman = Texto.llamaAlAsistente(dicho);
            // En espera ignora lo que no sea para Josué (conversaciones de la casa, la tele…).
            if (!conversacionActiva && estado == Estado.CHAT && !meLlaman) { reintentar(200); return; }
            if (dicho.isEmpty()) { reintentar(200); return; }
            if (meLlaman && !conversacionActiva) vibrar(40); // te oyó
            conversacionActiva = true;
            dormido = false;
            if (meLlaman && Texto.quitarNombreDelAsistente(dicho).isEmpty()) {
                avisarFrase(dicho, true);
                decir(Texto.contestarAlLlamado(), true);
                return;
            }
            alOir(dicho);
        }
        @Override public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (conversacionActiva && r != null && !r.isEmpty()) for (Vista v : vistas) v.parcial(r.get(0));
        }
        @Override public void onRmsChanged(float db) { avisarNivel(db / 10f); }
        @Override public void onError(int error) {
            if (hablando || apagado) return;
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                // Silencio: seguir escuchando; ya no se duerme solo, solo con "adiós".
                reintentar(200);
            } else if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Log.w("YERBIS", "error de reconocimiento: " + error);
                reintentar(1200);
            }
        }
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onBufferReceived(byte[] bytes) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int t, Bundle b) {}
    };

    private void alOir(String texto) {
        avisarFrase(texto, true);
        switch (estado) {
            case PIDE_NOMBRE:
                nombreTentativo = Texto.extraerNombre(texto);
                if (nombreTentativo.isEmpty()) { decir("No te entendí. ¿Cómo te llamas?", true); return; }
                estado = Estado.PIDE_PERMISO_NOMBRE;
                decir("¿Me das permiso para guardar tu nombre, " + nombreTentativo + "?", true);
                return;

            case PIDE_PERMISO_NOMBRE:
                nombre = nombreTentativo;
                String inicio = "";
                if (Texto.esSi(texto)) prefs.edit().putString("nombre", nombre).apply();
                else inicio = "Está bien, no lo guardaré. ";
                estado = Estado.CHAT;
                gemini = null;
                decir(inicio + "¡Mucho gusto, " + nombre + "! A partir de ahora estaré aquí para ayudarte. "
                        + "¿Qué te gustaría que hiciéramos primero?", true);
                return;

            case CONFIRMAR:
                Gemini.Llamada c = pendiente;
                pendiente = null;
                estado = Estado.CHAT;
                boolean si = Texto.esSi(texto);
                pensar(g -> procesar(g, g.responder(c, si ? comandos.ejecutar(c) : "El usuario canceló la acción.")));
                return;

            case CHAT:
                if (Texto.preguntaSiEstaDespierto(texto)) {
                    decir("Aquí ando bien despierto, bro. ¿Qué se ofrece?", true);
                    return;
                }
                if (Texto.esDespedida(texto)) {
                    dormido = true;
                    decir("Va, ahí nos vemos, bro. Me duermo; dime Josué cuando me ocupes.", false);
                    return;
                }
                if (claveGuardada().isEmpty()) {
                    for (Vista v : vistas) v.pedirAjustes();
                    decir("Primero pegue su API key de Gemini en Ajustes y toque Guardar.", false);
                    return;
                }
                pensar(g -> procesar(g, g.enviar(texto)));
        }
    }

    // ---------- Gemini ----------

    private interface TareaRed { void correr(Gemini g) throws Exception; }

    private void pensar(TareaRed tarea) {
        if (gemini == null) gemini = new Gemini(claveGuardada(), nombre);
        Gemini g = gemini;
        comandos.abrioOtraApp = false;
        cambiarModo(Modo.PENSANDO);
        red.execute(() -> {
            try {
                tarea.correr(g);
            } catch (Exception e) {
                Log.w("YERBIS", "Gemini falló: " + e.getMessage());
                ui.post(() -> {
                    cambiarModo(Modo.ERROR);
                    ui.postDelayed(() -> decir("Uy, bro. " + e.getMessage(), true), 900);
                });
            }
        });
    }

    /** Ejecuta las funciones que pida Gemini hasta que conteste con texto. Corre en el hilo de red. */
    private void procesar(Gemini g, Gemini.Respuesta r) throws Exception {
        while (r.llamada != null) {
            Gemini.Llamada c = r.llamada;
            if (Comandos.necesitaConfirmacion(c.nombre)) {
                pendiente = c;
                ui.post(() -> { estado = Estado.CONFIRMAR; decir(comandos.describir(c), true); });
                return;
            }
            if ("ver_pantalla".equals(c.nombre)) {
                Manos m = Manos.activa;
                byte[] jpg = m == null ? null : m.captura();
                if (jpg != null) { r = g.responderConImagen(c, "Esta es la captura de la pantalla del usuario.", jpg); continue; }
                r = g.responder(c, m == null
                        ? "No puedo ver la pantalla: falta activar a Josué en Ajustes > Accesibilidad."
                        : "Android no me dio la captura de pantalla.");
                continue;
            }
            String resultado;
            if ("silenciar_asistente".equals(c.nombre)) {
                boolean activar = c.args.optBoolean("activar", true);
                silenciar(activar);
                resultado = activar ? "Modo silencio activado: desde ahora solo respondes por texto."
                        : "Modo silencio desactivado: vuelves a hablar en voz alta.";
            } else {
                resultado = comandos.ejecutar(c);
            }
            r = g.responder(c, resultado);
        }
        String texto = r.texto.isEmpty() ? "Listo." : r.texto;
        ui.post(() -> decir(texto, true));
    }

    /** Foto, video o documento que el usuario adjuntó en la app: Josué lo mira y opina. */
    void analizar(byte[] datos, String tipo, String nombreArchivo) {
        if (claveGuardada().isEmpty()) { decir("Primero pega tu API key de Gemini en Ajustes, bro.", false); return; }
        avisarFrase("📎 " + nombreArchivo, true);
        conversacionActiva = true;
        pensar(g -> procesar(g, g.enviarConArchivo("Te paso este archivo (" + nombreArchivo + "). "
                + "Dime qué es y dame tu opinión sincera, breve y como compa.", tipo, datos)));
    }

    String claveGuardada() { return prefs.getString("clave", ""); }

    boolean fishListo() {
        return !prefs.getString("fishClave", "").isEmpty() && !prefs.getString("fishVoz", "").isEmpty();
    }

    // ---------- Memoria ----------

    void claveNueva(String k) {
        // Una clave nueva puede traer cuota nueva de voz IA: volver a intentarlo.
        prefs.edit().putString("clave", k).remove("vozIASinCuotaHasta").apply();
        gemini = null;
        decir("Clave guardada. Ya estamos en línea, bro.", true);
    }

    void cambiarNombre(String n) {
        nombre = n;
        prefs.edit().putString("nombre", n).apply();
        gemini = null;
        decir("Va, de ahora en adelante te digo " + n + ".", false);
    }

    void olvidarConversacion() {
        gemini = null;
        mensajes = 0;
        decir("Listo, ya se me olvidó todo, wey.", false);
    }

    void borrarTodo() {
        prefs.edit().remove("nombre").apply();
        nombre = null;
        gemini = null;
        estado = Estado.PIDE_NOMBRE;
        conversacionActiva = true;
        decir("Memoria borrada. Empecemos de nuevo. ¿Cómo te llamas?", true);
    }

    // ---------- Avisos a las pantallas ----------

    void cambiarModo(Modo m) {
        if (m == Modo.ESPERA && !enLinea) m = Modo.SIN_CONEXION;
        if (m == modoActual) return;
        modoActual = m;
        for (Vista v : vistas) v.modo(m);
    }

    void conexion(boolean si) {
        enLinea = si;
        if (!hablando && !conversacionActiva) cambiarModo(Modo.ESPERA);
    }

    private void avisarFrase(String texto, boolean delUsuario) {
        Log.i("YERBIS", (delUsuario ? "Usuario: " : "Josué: ") + texto);
        mensajes++;
        for (Vista v : vistas) v.frase(texto, delUsuario);
    }

    private void avisarNivel(float n) { for (Vista v : vistas) v.nivel(n); }
}
