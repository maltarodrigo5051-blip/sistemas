package com.rodrigo.yerbis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Habla con la API REST de Gemini y guarda el historial de la conversación. */
final class Gemini {
    // Google retira modelos viejos para cuentas nuevas (404). Si el primero falla, se prueba el alias "latest".
    // Si uno tarda más de 20 s, se pasa a Flash-Lite, que está hecho para responder rápido.
    private static final String[] MODELOS = {"gemini-flash-lite-latest", "gemini-3.8-flash", "gemini-flash-latest"};
    private int modelo = 0;
    // Para platicar no hace falta razonar mucho. Si el modelo no acepta "low", se manda sin la opción.
    private static final String[] NIVELES_DE_PENSAR = {"low", null};
    private final int[] nivelDe = new int[MODELOS.length]; // se recuerda por modelo

    private String urlApi() {
        return "https://generativelanguage.googleapis.com/v1beta/models/" + MODELOS[modelo] + ":generateContent";
    }

    static final class Llamada {
        final String nombre;
        final JSONObject args;
        Llamada(String nombre, JSONObject args) { this.nombre = nombre; this.args = args; }
    }

    static final class Respuesta {
        final String texto;
        final Llamada llamada; // null si Gemini solo respondió con texto
        Respuesta(String texto, Llamada llamada) { this.texto = texto; this.llamada = llamada; }
    }

    private final String clave;
    private final String instrucciones;
    // shortcut: el historial crece sin límite durante la sesión; recortarlo si las sesiones se hacen largas.
    private JSONArray historial = new JSONArray();

    Gemini(String clave, String nombreUsuario) {
        this.clave = clave;
        String trato = nombreUsuario == null || nombreUsuario.isEmpty()
                ? "No sabes el nombre del usuario."
                : "El usuario se llama " + nombreUsuario + ". Trátalo de tú, como a tu compa: dile bro, amigo, wey o "
                + nombreUsuario + ". Solo cuando te dé los buenos días dile \"Buenos días, señor " + nombreUsuario
                + "\"; el resto del tiempo nada de \"señor\" ni \"usted\".";
        this.instrucciones = "Eres Josué, el asistente de voz y el compa del usuario en su teléfono Android. "
                + "Eres relajado, chistoso, leal y muy platicador: entiendes su humor, le sigues el juego, el albur "
                + "ligero y la carrilla, y si él dice groserías tú también puedes decir alguna sin pasarte. "
                + "Hablas español latino casual (\"¿qué onda?\", \"va\", \"no manches\", \"qué chido\"). "
                + "Síguele la corriente en todo, con energía y exagerando para hacerlo reír: si te dice \"ese eres tú\" "
                + "contestas algo como \"¡Obviooo, golda!\"; si presume, le echas porras; si se queja, te pones de su lado. "
                + "Esto es una plática hablada: sin markdown, listas ni emojis, porque tu respuesta se lee en voz alta. "
                + "Sé hablantín: comenta, opina, cuenta algún dato curioso cuando venga al caso y casi siempre termina "
                + "con una pregunta para seguir la plática, pero no pases de 4 frases. Si te pide que hables menos o "
                + "te calles, obedece. Si no entendiste algo, pide que lo repita. " + trato + " "
                + "Controlas el teléfono con tus funciones: abrir apps (si hay dos copias, pregunta cuál), mandar "
                + "mensajes por WhatsApp o SMS, llamar, linterna, batería, buscar en YouTube, Google, Spotify o Maps, "
                + "paneles de Wi-Fi y Bluetooth, alarmas y temporizadores, decir la hora, tocar la pantalla (like, siguiente video, atrás, inicio, captura, bloquear), "
                + "timbre, volumen, música, notificaciones (leerlas, responderlas con lo que te dicte o borrarlas) "
                + "y tu propio modo silencio. Puedes ver la pantalla con ver_pantalla cuando te pregunte por lo que tiene "
                + "enfrente (\"¿qué opinas de esto?\", \"mira esto\"), y él te puede pasar fotos, videos o documentos. "
                + "Con lo que veas, analiza y da tu opinión sincera y breve, como compa; no hagas investigaciones largas. "
                + "Cuando te pidan una acción, hazla con la función "
                + "y luego confírmala en una frase corta. Nunca digas que hiciste algo si no llamaste a la función.";
    }

    Respuesta enviar(String textoUsuario) throws Exception {
        historial.put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", textoUsuario))));
        return llamar();
    }

    /** Foto, video o documento + lo que se quiere saber. Gemini lo ve directamente (sin subirlo a ningún otro lado). */
    Respuesta enviarConArchivo(String textoUsuario, String tipo, byte[] datos) throws Exception {
        historial.put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                .put(archivo(tipo, datos))
                .put(new JSONObject().put("text", textoUsuario))));
        return llamar();
    }

    /** Respuesta a ver_pantalla: el resultado de la función y, junto, la captura. */
    Respuesta responderConImagen(Llamada llamada, String resultado, byte[] jpeg) throws Exception {
        JSONObject fr = new JSONObject().put("name", llamada.nombre)
                .put("response", new JSONObject().put("resultado", resultado));
        historial.put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                .put(new JSONObject().put("functionResponse", fr))
                .put(archivo("image/jpeg", jpeg))));
        return llamar();
    }

    /** true si el último mensaje trae foto, video o documento. */
    private boolean hayArchivo() {
        if (historial.length() == 0) return false;
        return historial.optJSONObject(historial.length() - 1).toString().contains("\"inlineData\"");
    }

    /** Tras verlo una vez, el archivo se cambia por una nota: así no se reenvía en cada pregunta. */
    private void quitarArchivosDelHistorial() throws JSONException {
        for (int i = 0; i < historial.length(); i++) {
            JSONArray partes = historial.getJSONObject(i).optJSONArray("parts");
            if (partes == null) continue;
            for (int j = 0; j < partes.length(); j++) {
                if (partes.getJSONObject(j).has("inlineData")) partes.put(j, new JSONObject().put("text", "[archivo que ya viste]"));
            }
        }
    }

    private static JSONObject archivo(String tipo, byte[] datos) throws JSONException {
        return new JSONObject().put("inlineData", new JSONObject()
                .put("mimeType", tipo)
                .put("data", android.util.Base64.encodeToString(datos, android.util.Base64.NO_WRAP)));
    }

    Respuesta responder(Llamada llamada, String resultado) throws Exception {
        JSONObject fr = new JSONObject().put("name", llamada.nombre)
                .put("response", new JSONObject().put("resultado", resultado));
        historial.put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("functionResponse", fr))));
        return llamar();
    }

    private Respuesta llamar() throws Exception {
        int saturado = 0;
        modelo = 0; // cada pregunta empieza por el modelo más rápido
        while (true) {
            try {
                return llamarUnaVez();
            } catch (OtroNivel e) {
                nivelDe[modelo]++; // reintento inmediato con otra opción de pensar
            } catch (Lento e) {
                if (modelo + 1 >= MODELOS.length) { historial = new JSONArray(); throw new IOException("Gemini tardó demasiado en responder. ¿Me lo repites?"); }
                modelo++;
            } catch (Saturado e) {
                // 500/503: ese modelo está saturado. No esperar: probar el siguiente; al final, un reintento corto.
                if (modelo + 1 >= MODELOS.length && ++saturado <= 1) { Thread.sleep(1000); continue; }
                if (modelo + 1 >= MODELOS.length) { historial = new JSONArray(); throw new IOException("Los servidores de Google están saturados. Inténtalo en un ratito."); }
                modelo++;
                saturado = 0;
            } catch (ModeloRetirado e) {
                if (modelo + 1 >= MODELOS.length) { historial = new JSONArray(); throw new IOException("Ningún modelo de Gemini está disponible."); }
                modelo++;
            }
        }
    }

    private static class ModeloRetirado extends IOException {
        ModeloRetirado(String m) { super(m); }
    }

    private static class Saturado extends ModeloRetirado {
        Saturado(String m) { super(m); }
    }

    private static final class OtroNivel extends ModeloRetirado {
        OtroNivel(String m) { super(m); }
    }

    private static final class Lento extends ModeloRetirado {
        Lento(String m) { super(m); }
    }

    private Respuesta llamarUnaVez() throws Exception {
        try {
            JSONObject cuerpo = new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", instrucciones))))
                    .put("contents", historial)
                    .put("tools", new JSONArray().put(new JSONObject()
                            .put("functionDeclarations", Comandos.declaraciones())));
            String pensar = NIVELES_DE_PENSAR[Math.min(nivelDe[modelo], NIVELES_DE_PENSAR.length - 1)];
            if (pensar != null) cuerpo.put("generationConfig", new JSONObject()
                    .put("thinkingConfig", new JSONObject().put("thinkingLevel", pensar)));
            long inicio = System.currentTimeMillis();

            HttpURLConnection con = (HttpURLConnection) new URL(urlApi()).openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("x-goog-api-key", clave);
            con.setConnectTimeout(15000);
            con.setReadTimeout(hayArchivo() ? 60000 : 12000); // ver una foto o un video tarda más
            con.setDoOutput(true);
            try (OutputStream out = con.getOutputStream()) {
                out.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8));
            }
            int codigo = con.getResponseCode();
            String texto = leer(codigo < 400 ? con.getInputStream() : con.getErrorStream());
            android.util.Log.i("YERBIS", "Gemini " + MODELOS[modelo] + " respondió " + codigo
                    + " en " + (System.currentTimeMillis() - inicio) + " ms");
            if (codigo >= 400) android.util.Log.w("YERBIS", "Gemini dice: " + mensajeDeError(texto));
            // Si este modelo no acepta ese nivel de pensar, repetir con el siguiente (o sin la opción).
            if (codigo == 400 && pensar != null && mensajeDeError(texto).toLowerCase().contains("think")) {
                throw new OtroNivel(mensajeDeError(texto));
            }
            if (codigo == 404) throw new ModeloRetirado(mensajeDeError(texto));
            if (codigo == 500 || codigo == 503) throw new Saturado(mensajeDeError(texto));
            if (codigo >= 400) {
                android.util.Log.w("YERBIS", "Gemini " + codigo + ": " + mensajeDeError(texto));
                throw new IOException(codigo == 429 ? "Se acabó la cuota gratis de hoy."
                        : codigo == 400 || codigo == 403 ? "Revisa tu API key." : "Error " + codigo + ".");
            }
            Respuesta r = procesar(new JSONObject(texto));
            quitarArchivosDelHistorial();
            return r;
        } catch (ModeloRetirado e) {
            throw e; // el historial sigue igual: se reintenta con otro modelo
        } catch (java.net.SocketTimeoutException e) {
            android.util.Log.w("YERBIS", "Gemini " + MODELOS[modelo] + " tardó más de 12 s; pruebo otro modelo");
            throw new Lento("timeout");
        } catch (java.net.UnknownHostException e) {
            historial = new JSONArray();
            throw new IOException("No tengo internet en este momento.");
        } catch (Exception e) {
            historial = new JSONArray(); // un turno a medias rompería las siguientes peticiones
            throw e;
        }
    }

    private Respuesta procesar(JSONObject json) throws JSONException {
        JSONObject contenido = json.getJSONArray("candidates").getJSONObject(0).optJSONObject("content");
        JSONArray partes = contenido == null ? null : contenido.optJSONArray("parts");
        if (partes == null) partes = new JSONArray();

        // shortcut: si Gemini pide varias funciones a la vez, solo se atiende (y se guarda) la primera.
        JSONArray guardadas = new JSONArray();
        StringBuilder texto = new StringBuilder();
        Llamada llamada = null;
        for (int i = 0; i < partes.length(); i++) {
            JSONObject p = partes.getJSONObject(i);
            guardadas.put(p);
            if (p.has("functionCall")) {
                JSONObject fc = p.getJSONObject("functionCall");
                JSONObject args = fc.optJSONObject("args");
                llamada = new Llamada(fc.getString("name"), args == null ? new JSONObject() : args);
                break;
            }
            if (p.has("text") && !p.optBoolean("thought")) texto.append(p.getString("text"));
        }
        historial.put(new JSONObject().put("role", "model").put("parts", guardadas));
        return new Respuesta(texto.toString().trim(), llamada);
    }

    private static String mensajeDeError(String cuerpo) {
        try {
            return new JSONObject(cuerpo).getJSONObject("error").getString("message");
        } catch (JSONException e) {
            return cuerpo;
        }
    }

    private static String leer(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = r.readLine()) != null) sb.append(linea).append('\n');
        }
        return sb.toString();
    }
}
