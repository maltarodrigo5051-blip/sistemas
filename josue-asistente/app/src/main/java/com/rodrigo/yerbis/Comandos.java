package com.rodrigo.yerbis;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Las funciones que Gemini puede pedir. Usan intents normales de Android y, para tocar la pantalla, Manos. */
final class Comandos {
    private final Context ctx;
    /** Se pone en true si un comando abrió otra app. */
    boolean abrioOtraApp;

    Comandos(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    static JSONArray declaraciones() throws JSONException {
        // org.json de Android acepta comillas simples, así el texto se lee mejor.
        return new JSONArray("["
                + "{name:'abrir_app', description:'Abre una aplicación instalada. Si hay dos copias (por ejemplo WhatsApp normal y el del segundo espacio), la primera vez llámala sin cual: te dirá cuántas hay y debes preguntarle al usuario cuál quiere; luego llámala otra vez con cual.',"
                + " parameters:{type:'OBJECT', properties:{nombre:{type:'STRING', description:'Nombre de la app, por ejemplo WhatsApp o YouTube'}, cual:{type:'INTEGER', description:'1 para la primera copia, 2 para la segunda'}}, required:['nombre']}},"
                + "{name:'enviar_mensaje', description:'Manda un mensaje a un contacto por WhatsApp (por defecto) o SMS. Josué lo envía solo después de que el usuario diga que sí.',"
                + " parameters:{type:'OBJECT', properties:{destinatario:{type:'STRING', description:'Nombre del contacto o número'}, texto:{type:'STRING', description:'Texto del mensaje'}, via:{type:'STRING', enum:['whatsapp','sms']}}, required:['destinatario','texto']}},"
                + "{name:'poner_alarma', description:'Pone una alarma en el reloj del teléfono.',"
                + " parameters:{type:'OBJECT', properties:{hora:{type:'INTEGER', description:'0 a 23'}, minuto:{type:'INTEGER'}, nombre:{type:'STRING', description:'Para qué es la alarma'}}, required:['hora','minuto']}},"
                + "{name:'temporizador', description:'Pone un temporizador (cuenta regresiva).',"
                + " parameters:{type:'OBJECT', properties:{segundos:{type:'INTEGER'}, nombre:{type:'STRING'}}, required:['segundos']}},"
                + "{name:'decir_hora', description:'Devuelve la fecha y la hora actual del teléfono.'},"
                + "{name:'accion_pantalla', description:'Controla la pantalla del teléfono como si el usuario la tocara: like = doble toque (me gusta en TikTok, Instagram, Reels), siguiente = deslizar hacia arriba (siguiente video), anterior, tocar_centro, atras, inicio, recientes, notificaciones, ajustes_rapidos, captura, bloquear.',"
                + " parameters:{type:'OBJECT', properties:{accion:{type:'STRING', enum:['like','siguiente','anterior','tocar_centro','atras','inicio','recientes','notificaciones','ajustes_rapidos','captura','bloquear']}}, required:['accion']}},"
                + "{name:'modo_telefono', description:'Cambia el timbre del teléfono: silencio, vibrar o normal.',"
                + " parameters:{type:'OBJECT', properties:{modo:{type:'STRING', enum:['silencio','vibrar','normal']}}, required:['modo']}},"
                + "{name:'volumen', description:'Sube, baja, pone al máximo o quita el volumen de la música y los videos.',"
                + " parameters:{type:'OBJECT', properties:{accion:{type:'STRING', enum:['subir','bajar','maximo','silenciar']}}, required:['accion']}},"
                + "{name:'musica', description:'Controla la música o el video que suena: pausar, reproducir, siguiente o anterior.',"
                + " parameters:{type:'OBJECT', properties:{accion:{type:'STRING', enum:['pausar','reproducir','siguiente','anterior']}}, required:['accion']}},"
                + "{name:'ver_pantalla', description:'Toma una captura de lo que el usuario tiene en pantalla para que la veas y le des tu análisis u opinión.'},"
                + "{name:'linterna', description:'Prende o apaga la linterna del teléfono.',"
                + " parameters:{type:'OBJECT', properties:{prender:{type:'BOOLEAN'}}, required:['prender']}},"
                + "{name:'bateria', description:'Dice cuánta batería tiene el teléfono y si está cargando.'},"
                + "{name:'buscar', description:'Busca o reproduce algo en YouTube, Google, Spotify, o abre Maps para ir a un lugar.',"
                + " parameters:{type:'OBJECT', properties:{donde:{type:'STRING', enum:['youtube','google','spotify','maps']}, que:{type:'STRING', description:'Qué buscar o a dónde ir'}}, required:['donde','que']}},"
                + "{name:'llamar', description:'Llama por teléfono a un contacto o número. Josué marca solo después de que el usuario diga que sí.',"
                + " parameters:{type:'OBJECT', properties:{destinatario:{type:'STRING'}}, required:['destinatario']}},"
                + "{name:'panel', description:'Abre el panelito de Wi-Fi, internet o Bluetooth para que el usuario lo prenda o apague (Android no deja hacerlo solo).',"
                + " parameters:{type:'OBJECT', properties:{cual:{type:'STRING', enum:['wifi','internet','bluetooth']}}, required:['cual']}},"
                + "{name:'leer_notificaciones', description:'Lee las notificaciones que hay en la barra (app, quién y qué dice), numeradas. Úsala antes de responder o borrar una.'},"
                + "{name:'responder_notificacion', description:'Responde una notificación (WhatsApp, Telegram, Mensajes…) con el texto que dicte el usuario. El número es el de la última lista leída.',"
                + " parameters:{type:'OBJECT', properties:{numero:{type:'INTEGER'}, texto:{type:'STRING', description:'Lo que el usuario quiere responder, tal cual'}}, required:['numero','texto']}},"
                + "{name:'borrar_notificacion', description:'Borra una notificación por su número de la última lista, o todas con numero 0.',"
                + " parameters:{type:'OBJECT', properties:{numero:{type:'INTEGER', description:'0 para borrar todas'}}, required:['numero']}},"
                + "{name:'silenciar_asistente', description:'Pone a Josué en modo silencio (deja de hablar en voz alta y solo responde por texto) o lo quita. Úsala cuando el usuario te pida que te calles, que te pongas en mute o que vuelvas a hablar.',"
                + " parameters:{type:'OBJECT', properties:{activar:{type:'BOOLEAN', description:'true para silenciar, false para volver a hablar'}}, required:['activar']}}"
                + "]");
    }

    /** Acciones que hablan en nombre del usuario: Josué pide un "sí" por voz antes. */
    static boolean necesitaConfirmacion(String funcion) {
        return "enviar_mensaje".equals(funcion) || "llamar".equals(funcion) || "responder_notificacion".equals(funcion);
    }

    String describir(Gemini.Llamada c) {
        if ("llamar".equals(c.nombre)) return "¿Le marco a " + c.args.optString("destinatario") + "?";
        if ("responder_notificacion".equals(c.nombre)) {
            return "Voy a responder la notificación " + c.args.optInt("numero") + " con: " + c.args.optString("texto") + ". ¿La envío?";
        }
        String via = "sms".equals(c.args.optString("via")) ? "por SMS" : "por WhatsApp";
        return "Le mando a " + c.args.optString("destinatario") + " " + via + ": " + c.args.optString("texto") + ". ¿Lo mando?";
    }

    String ejecutar(Gemini.Llamada c) {
        switch (c.nombre) {
            case "decir_hora":
                return new SimpleDateFormat("EEEE d 'de' MMMM, h:mm a", new Locale("es")).format(new Date());
            case "abrir_app":
                return abrirApp(c.args.optString("nombre"), c.args.optInt("cual", 0));
            case "enviar_mensaje":
                return enviarMensaje(c.args.optString("destinatario"), c.args.optString("texto"), c.args.optString("via", "whatsapp"));
            case "poner_alarma":
                return alarma(c.args.optInt("hora"), c.args.optInt("minuto"), c.args.optString("nombre", "Josué"));
            case "temporizador":
                return temporizador(c.args.optInt("segundos"), c.args.optString("nombre", "Josué"));
            case "accion_pantalla":
                Manos m = Manos.activa;
                if (m == null) {
                    abrirAjustes(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    return "No tengo permiso para controlar la pantalla. Abrí Ajustes > Accesibilidad: dile al usuario que active «Josué» ahí y lo intente de nuevo.";
                }
                return m.hacer(c.args.optString("accion"));
            case "leer_notificaciones":
            case "responder_notificacion":
            case "borrar_notificacion":
                Avisos av = Avisos.activa;
                if (av == null) {
                    abrirAjustes(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    return "No tengo permiso para ver las notificaciones. Abrí Ajustes: dile al usuario que active a Josué en Acceso a notificaciones.";
                }
                if (c.nombre.startsWith("leer")) return av.leer();
                if (c.nombre.startsWith("borrar")) return av.borrar(c.args.optInt("numero", 0));
                return av.responder(c.args.optInt("numero"), c.args.optString("texto"));
            case "linterna": return linterna(c.args.optBoolean("prender", true));
            case "bateria": return bateria();
            case "buscar": return buscar(c.args.optString("donde"), c.args.optString("que"));
            case "llamar": return llamar(c.args.optString("destinatario"));
            case "panel": return panel(c.args.optString("cual"));
            case "modo_telefono":
                return modoTelefono(c.args.optString("modo"));
            case "volumen":
                return volumen(c.args.optString("accion"));
            case "musica":
                return musica(c.args.optString("accion"));
            default:
                return "Función desconocida: " + c.nombre;
        }
    }

    /** Busca en el usuario normal y en los otros perfiles (segundo espacio, apps duplicadas). */
    private String abrirApp(String pedido, int cual) {
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        List<LauncherActivityInfo> iguales = new ArrayList<>();
        int mejor = 0;
        for (UserHandle u : la.getProfiles()) {
            for (LauncherActivityInfo a : la.getActivityList(null, u)) {
                int p = Texto.parecido(a.getLabel().toString(), pedido);
                if (p > mejor) { mejor = p; iguales.clear(); }
                if (p == mejor && p > 0) iguales.add(a);
            }
        }
        if (iguales.isEmpty()) return abrirAppDeTV(pedido);
        if (iguales.size() > 1 && (cual < 1 || cual > iguales.size())) {
            StringBuilder sb = new StringBuilder("Hay " + iguales.size() + " apps que coinciden: ");
            for (int i = 0; i < iguales.size(); i++) sb.append(i + 1).append(") ").append(etiqueta(iguales.get(i))).append(". ");
            return sb.append("Pregúntale al usuario cuál quiere y vuelve a llamar abrir_app con cual.").toString();
        }
        LauncherActivityInfo elegida = iguales.get(iguales.size() == 1 ? 0 : cual - 1);
        la.startMainActivity(elegida.getComponentName(), elegida.getUser(), null, null);
        abrioOtraApp = true;
        return "Abrí " + etiqueta(elegida) + ".";
    }

    private static String etiqueta(LauncherActivityInfo a) {
        boolean otroPerfil = !a.getUser().equals(Process.myUserHandle());
        return a.getLabel() + (otroPerfil ? " (la segunda copia)" : "");
    }

    /** En Android TV las apps se registran en LEANBACK_LAUNCHER, que LauncherApps no lista. */
    private String abrirAppDeTV(String pedido) {
        PackageManager pm = ctx.getPackageManager();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            if (Texto.parecido(r.loadLabel(pm).toString(), pedido) == 0) continue;
            Intent abrir = pm.getLeanbackLaunchIntentForPackage(r.activityInfo.packageName);
            if (abrir == null) continue;
            ctx.startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            abrioOtraApp = true;
            return "Abrí " + r.loadLabel(pm) + ".";
        }
        return "No encontré ninguna app llamada " + pedido + ".";
    }

    private String modoTelefono(String modo) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        try {
            if ("silencio".equals(modo)) {
                // Silencio total exige el permiso "No molestar"; sin él, se pone en vibrar.
                if (!nm.isNotificationPolicyAccessGranted()) {
                    am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                    abrirAjustes(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                    return "Lo puse en vibrar. Para silencio total necesito el permiso de No molestar: abrí Ajustes, dile al usuario que active a Josué ahí.";
                }
                am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                return "Teléfono en silencio.";
            }
            am.setRingerMode("vibrar".equals(modo) ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL);
            return "vibrar".equals(modo) ? "Teléfono en vibrar." : "Teléfono con sonido normal.";
        } catch (SecurityException e) {
            abrirAjustes(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            return "Android no me dejó cambiar el timbre; abrí el permiso de No molestar para que el usuario lo active.";
        }
    }

    private String volumen(String accion) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        int s = AudioManager.STREAM_MUSIC;
        switch (accion) {
            case "subir": am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
            case "bajar": am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
            case "maximo": am.setStreamVolume(s, am.getStreamMaxVolume(s), AudioManager.FLAG_SHOW_UI); break;
            case "silenciar": am.setStreamVolume(s, 0, AudioManager.FLAG_SHOW_UI); break;
            default: return "No entendí qué hacer con el volumen.";
        }
        return "Volumen: " + am.getStreamVolume(s) + " de " + am.getStreamMaxVolume(s) + ".";
    }

    private String musica(String accion) {
        int tecla;
        switch (accion) {
            case "pausar": tecla = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "reproducir": tecla = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "siguiente": tecla = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "anterior": tecla = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            default: return "No entendí qué hacer con la música.";
        }
        AudioManager am = ctx.getSystemService(AudioManager.class);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, tecla));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, tecla));
        return "Listo: " + accion + ".";
    }

    private void abrirAjustes(String accion) {
        try {
            ctx.startActivity(new Intent(accion).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignorada) {
            // Algunos teléfonos no tienen esa pantalla de ajustes; Gemini igual explica qué falta.
        }
    }

    /** Solo se llama después del "sí" del usuario (necesitaConfirmacion). Corre en el hilo de red. */
    private String enviarMensaje(String destinatario, String texto, String via) {
        String numero = Texto.pareceNumero(destinatario) ? destinatario : buscarNumero(destinatario);
        if (numero == null) return "No encontré a " + destinatario + " en los contactos con un número. Pregúntale al usuario el número.";
        if ("sms".equals(via)) {
            if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                return "No tengo permiso para mandar SMS. Dile al usuario que abra Josué y acepte el permiso de SMS.";
            }
            android.telephony.SmsManager sms = ctx.getSystemService(android.telephony.SmsManager.class);
            sms.sendMultipartTextMessage(numero, null, sms.divideMessage(texto), null, null);
            return "SMS enviado a " + destinatario + ".";
        }
        // WhatsApp: se abre el chat con el texto ya escrito y Manos toca "Enviar".
        Intent wa = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/" + Texto.numeroInternacional(numero) + "?text=" + Uri.encode(texto)));
        wa.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(wa);
        } catch (ActivityNotFoundException e) {
            return "No tengo WhatsApp instalado.";
        }
        abrioOtraApp = true;
        Manos m = Manos.activa;
        if (m == null) return "Abrí el chat de " + destinatario + " con el mensaje escrito, pero sin el permiso de Accesibilidad no puedo tocar Enviar: que lo toque el usuario.";
        // Si hay dos WhatsApp, Android pregunta cuál: el usuario lo elige y Manos sigue esperando el botón.
        for (int i = 0; i < 16; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { return "Se interrumpió el envío."; }
            if (m.tocarEnviar()) return "Mensaje enviado por WhatsApp a " + destinatario + ".";
        }
        return "Abrí el chat de " + destinatario + " pero no encontré el botón Enviar; que lo toque el usuario.";
    }

    private String linterna(boolean prender) {
        android.hardware.camera2.CameraManager cm = ctx.getSystemService(android.hardware.camera2.CameraManager.class);
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    cm.setTorchMode(id, prender);
                    return prender ? "Linterna prendida." : "Linterna apagada.";
                }
            }
            return "Este teléfono no tiene linterna.";
        } catch (Exception e) {
            return "No pude usar la linterna (quizá la cámara está ocupada).";
        }
    }

    /** Porcentaje de batería, para Gemini y para el saludo de buenos días. */
    String bateria() {
        android.os.BatteryManager bm = ctx.getSystemService(android.os.BatteryManager.class);
        int nivel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return nivel + "% de batería" + (bm.isCharging() ? ", cargando." : ".");
    }

    private String buscar(String donde, String que) {
        Uri u;
        switch (donde) {
            case "youtube": u = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(que)); break;
            case "spotify": u = Uri.parse("spotify:search:" + Uri.encode(que)); break;
            case "maps": u = Uri.parse("google.navigation:q=" + Uri.encode(que)); break;
            default: u = Uri.parse("https://www.google.com/search?q=" + Uri.encode(que));
        }
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            if (!"google".equals(donde)) return buscar("google", que); // sin la app, que lo busque en el navegador
            return "No tengo navegador para buscar.";
        }
        abrioOtraApp = true;
        return "Abrí " + donde + " con: " + que + ".";
    }

    /** Solo después del "sí". Sin permiso de llamadas, abre el marcador con el número listo. */
    private String llamar(String destinatario) {
        String numero = Texto.pareceNumero(destinatario) ? destinatario : buscarNumero(destinatario);
        if (numero == null) return "No encontré el número de " + destinatario + ". Pregúntale al usuario el número.";
        boolean puede = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        Intent i = new Intent(puede ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numero)));
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        abrioOtraApp = true;
        return puede ? "Llamando a " + destinatario + "." : "Abrí el marcador con el número de " + destinatario + "; que el usuario toque llamar.";
    }

    private String panel(String cual) {
        String accion = "bluetooth".equals(cual) ? Settings.ACTION_BLUETOOTH_SETTINGS
                : android.os.Build.VERSION.SDK_INT >= 29
                        ? ("wifi".equals(cual) ? Settings.Panel.ACTION_WIFI : Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        : Settings.ACTION_WIFI_SETTINGS;
        abrirAjustes(accion);
        return "Abrí el panel de " + cual + " para que el usuario lo prenda o apague.";
    }

    private String alarma(int hora, int minuto, String nombre) {
        Intent i = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hora)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minuto)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, nombre)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return String.format(Locale.ROOT, "Alarma puesta a las %d:%02d (%s).", hora, minuto, nombre);
        } catch (ActivityNotFoundException e) {
            return "Este teléfono no tiene app de reloj para alarmas.";
        }
    }

    private String temporizador(int segundos, String nombre) {
        Intent i = new Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, segundos)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, nombre)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return "Temporizador de " + segundos + " segundos puesto.";
        } catch (ActivityNotFoundException e) {
            return "Este teléfono no tiene app de reloj para temporizadores.";
        }
    }

    private String buscarNumero(String nombre) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        try (Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + nombre + "%"}, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        }
    }
}
