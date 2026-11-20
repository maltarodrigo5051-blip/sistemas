package com.rodrigo.yerbis;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Voz de Fish Audio (más natural). El usuario pone su propia API key y el ID de la voz en la app. */
final class VozFish {
    private VozFish() {}

    private static final String URL_TTS = "https://api.fish.audio/v1/tts";

    /** Devuelve PCM 16 bits mono a 24 kHz, el mismo formato que reproduce VozIA. */
    static byte[] generar(String clave, String idVoz, String texto) throws Exception {
        JSONObject cuerpo = new JSONObject()
                .put("text", texto)
                .put("reference_id", idVoz)
                .put("format", "pcm")
                .put("sample_rate", 24000)
                .put("latency", "balanced");

        HttpURLConnection con = (HttpURLConnection) new URL(URL_TTS).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Authorization", "Bearer " + clave);
        con.setRequestProperty("Content-Type", "application/json");
        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);
        con.setDoOutput(true);
        try (OutputStream out = con.getOutputStream()) {
            out.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8));
        }
        int codigo = con.getResponseCode();
        byte[] datos = leer(codigo < 400 ? con.getInputStream() : con.getErrorStream());
        if (codigo >= 400) throw new IOException("Fish Audio " + codigo + ": " + new String(datos, StandardCharsets.UTF_8));
        return datos;
    }

    private static byte[] leer(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in == null) return out.toByteArray();
        try (InputStream i = in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
