package com.rodrigo.yerbis;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Voz hecha con IA usando el modelo de texto a voz de Gemini (misma API key). */
final class VozIA {
    private VozIA() {}

    private static final String URL_TTS =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent";
    private static final int MUESTRAS_POR_SEGUNDO = 24000; // Gemini devuelve PCM 16 bits, mono, 24 kHz

    /** Voces masculinas de Gemini, de más grave/serena a más enérgica. */
    static final String[] VOCES = {"Algieba", "Charon", "Orus", "Alnilam", "Iapetus", "Schedar", "Fenrir"};

    /** Pide el audio a Gemini. Lanza excepción si no hay internet o la clave falla. */
    static byte[] generar(String clave, String nombreVoz, String texto) throws Exception {
        String conEstilo = "Lee en español latino, con voz grave, serena y elegante, "
                + "como un mayordomo de inteligencia artificial muy educado: " + texto;
        JSONObject cuerpo = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", conEstilo)))))
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", nombreVoz)))));

        HttpURLConnection con = (HttpURLConnection) new URL(URL_TTS).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setRequestProperty("x-goog-api-key", clave);
        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);
        con.setDoOutput(true);
        try (OutputStream out = con.getOutputStream()) {
            out.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8));
        }
        int codigo = con.getResponseCode();
        String json = leer(codigo < 400 ? con.getInputStream() : con.getErrorStream());
        if (codigo >= 400) throw new IOException("TTS " + codigo + ": " + json);
        String b64 = new JSONObject(json).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getJSONObject("inlineData").getString("data");
        return Base64.decode(b64, Base64.DEFAULT);
    }

    /** Reproduce el audio y bloquea hasta que termina o hasta que "seguir" diga false (interrupción). */
    static void reproducir(byte[] pcm, BooleanSupplier seguir, java.util.function.Consumer<Float> nivel) throws InterruptedException {
        int minimo = AudioTrack.getMinBufferSize(MUESTRAS_POR_SEGUNDO,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack pista = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MUESTRAS_POR_SEGUNDO)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minimo, 8192))
                .build();
        try {
            pista.play();
            for (int i = 0; i < pcm.length && seguir.getAsBoolean(); i += 2048) {
                int n = Math.min(2048, pcm.length - i);
                nivel.accept(volumen(pcm, i, n)); // ~40 ms de audio por trozo: el reactor parpadea con la voz
                pista.write(pcm, i, n);
            }
            int totalMuestras = pcm.length / 2;
            while (seguir.getAsBoolean() && pista.getPlaybackHeadPosition() < totalMuestras) Thread.sleep(40);
        } finally {
            pista.stop();
            pista.release();
        }
    }

    /** Volumen de un trozo de PCM 16 bits, de 0 (silencio) a 1 (muy fuerte). */
    static float volumen(byte[] pcm, int desde, int largo) {
        long suma = 0;
        int muestras = largo / 2;
        for (int j = 0; j < muestras; j++) {
            int i = desde + j * 2;
            short s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            suma += (long) s * s;
        }
        double rms = muestras == 0 ? 0 : Math.sqrt((double) suma / muestras);
        return (float) Math.min(1.0, rms / 8000.0);
    }

    private static String leer(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = r.readLine()) != null) sb.append(linea);
        }
        return sb.toString();
    }
}
