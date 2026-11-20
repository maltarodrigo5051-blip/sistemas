package com.rodrigo.yerbis;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reglas de texto sin Android, para poder probarlas en la PC. */
final class Texto {
    private Texto() {}

    // Busca la frase en cualquier parte: "Hola Jarvis, me llamo Rodrigo" -> "Rodrigo".
    private static final Pattern ANTES_DEL_NOMBRE = Pattern.compile(
            "^.*?\\b(me llamo|mi nombre es|soy|ll[aá]mame|puedes llamarme)\\s+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SALUDO_INICIAL = Pattern.compile(
            "^(hola|buenas|buenos d[ií]as|buenas tardes|buenas noches)\\s+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final List<String> SI = Arrays.asList(
            "si", "claro", "dale", "ok", "okay", "vale", "adelante", "confirmo", "hazlo", "correcto", "acuerdo", "supuesto");

    /** Minúsculas, sin tildes ni signos: "¡Sí, claro!" -> "si claro". */
    static String normalizar(String s) {
        String sinTildes = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** "Me llamo rodrigo" -> "Rodrigo". Se queda con máximo 3 palabras. */
    static String extraerNombre(String dicho) {
        String limpio = dicho.replaceAll("[¡!¿?.,]", " ").trim();
        java.util.regex.Matcher m = ANTES_DEL_NOMBRE.matcher(limpio);
        limpio = m.find() ? limpio.substring(m.end()).trim() : SALUDO_INICIAL.matcher(limpio).replaceFirst("").trim();
        StringBuilder sb = new StringBuilder();
        String[] palabras = limpio.split("\\s+");
        for (int i = 0; i < Math.min(3, palabras.length); i++) {
            String p = palabras[i];
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.substring(0, 1).toUpperCase(Locale.ROOT)).append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** true solo si hay un "sí" y no hay un "no". Ante la duda, es no. */
    static boolean esSi(String dicho) {
        List<String> palabras = Arrays.asList(normalizar(dicho).split(" "));
        if (palabras.contains("no")) return false;
        for (String p : palabras) if (SI.contains(p)) return true;
        return false;
    }

    // "Dios" sale cuando el micrófono corta el "a" de "adiós".
    private static final List<String> DESPEDIDAS = Arrays.asList(
            "adios", "dios", "chao", "chau", "bye", "apagate", "descansa", "duermete");

    static boolean esDespedida(String dicho) {
        String n = normalizar(dicho);
        if (n.contains("hasta luego") || n.contains("eso es todo") || n.contains("nos vemos")) return true;
        for (String p : n.split(" ")) if (DESPEDIDAS.contains(p)) return true;
        return false;
    }

    /** "¿Estás despierto, YERBIS?" -> true. No exige oír "YERBIS": el micrófono lo entiende de muchas formas. */
    static boolean preguntaSiEstaDespierto(String dicho) {
        String n = normalizar(dicho);
        return n.contains("despierto") || n.contains("despierta");
    }

    // Cómo suele escribir el reconocedor de voz el nombre del asistente: "Josué" y, por costumbre, "YERBIS".
    private static final List<String> NOMBRES_DEL_ASISTENTE = Arrays.asList(
            "josue", "josu", "hosue", "yosue", "jusue", "josuee",
            "yerbis", "yervis", "jerbis", "jervis", "jarvis", "yarvis", "herbis", "hervis",
            "gerbis", "gervis", "llerbis", "llervis", "lervis", "yerbi", "yerbes", "jerves",
            "yarbis", "jarbis", "harvis", "garvis", "yarbi", "yervi");

    /** true si en la frase lo llaman: "Josué, ¿qué hora es?" o "¿estás despierto?". */
    static boolean llamaAlAsistente(String dicho) {
        for (String p : normalizar(dicho).split(" ")) if (NOMBRES_DEL_ASISTENTE.contains(p)) return true;
        return preguntaSiEstaDespierto(dicho);
    }

    /** "Oye Josué" -> "" ; "Josué abre YouTube" -> "abre youtube". */
    static String quitarNombreDelAsistente(String dicho) {
        StringBuilder sb = new StringBuilder();
        for (String p : normalizar(dicho).split(" ")) {
            if (NOMBRES_DEL_ASISTENTE.contains(p) || p.equals("oye") || p.equals("hey") || p.equals("ey")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }

    private static final String[] RESPUESTAS_AL_LLAMADO = {
            "¿Qué onda, bro?", "Dime, wey.", "¿Qué pasó, amigo?", "A la orden, bro.", "Aquí estoy, ¿qué se ofrece?"};

    /** Cuando solo dicen "Josué": varía la respuesta para que no suene a robot. */
    static String contestarAlLlamado() {
        return RESPUESTAS_AL_LLAMADO[(int) (Math.random() * RESPUESTAS_AL_LLAMADO.length)];
    }

    static String saludo(int hora) {
        if (hora >= 5 && hora < 12) return "Buenos días";
        if (hora >= 12 && hora < 19) return "Buenas tardes";
        return "Buenas noches";
    }

    /** 2 = nombre exacto, 1 = lo contiene, 0 = no se parece. */
    static int parecido(String etiquetaApp, String pedido) {
        String a = normalizar(etiquetaApp), b = normalizar(pedido);
        if (b.isEmpty()) return 0;
        if (a.equals(b)) return 2;
        return a.contains(b) ? 1 : 0;
    }

    /** Acepta el ID solo o la dirección completa: "https://fish.audio/m/abc123" -> "abc123". */
    static String idDeVozFish(String pegado) {
        String s = pegado.trim();
        int m = s.indexOf("/m/");
        if (m >= 0) s = s.substring(m + 3);
        s = s.replaceAll("[/?#].*$", "");
        return s.matches("[A-Za-z0-9]+") ? s : "";
    }

    /** Lee "retryDelay": "35858s" de un error 429 de Gemini; si no está, devuelve porDefecto. */
    static long segundosDeEspera(String error, long porDefecto) {
        java.util.regex.Matcher m = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"").matcher(error);
        return m.find() ? Long.parseLong(m.group(1)) : porDefecto;
    }

    /** "8888-1234" -> "50588881234" para wa.me. shortcut: asume Nicaragua (+505) si el número trae 8 dígitos. */
    static String numeroInternacional(String numero) {
        String d = numero.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) d = d.substring(2);
        return d.length() == 8 ? "505" + d : d;
    }

    static boolean pareceNumero(String s) {
        return s.replaceAll("[\\s()+-]", "").matches("\\d{6,15}");
    }
}
