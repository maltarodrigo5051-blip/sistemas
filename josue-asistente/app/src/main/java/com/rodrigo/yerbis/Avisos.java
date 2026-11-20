package com.rodrigo.yerbis;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Lee la barra de notificaciones para que Josué te diga qué llegó, responda lo que le dictes
 * (con tu "sí" antes de enviar) o las borre. El usuario lo activa en "Acceso a notificaciones".
 */
public class Avisos extends NotificationListenerService {

    static volatile Avisos activa;
    /** La última lista que se le leyó al usuario: "responde la 2" se refiere a esta. */
    private static final List<StatusBarNotification> ultimas = new ArrayList<>();

    @Override public void onListenerConnected() { activa = this; }
    @Override public void onListenerDisconnected() { activa = null; }

    String leer() {
        StatusBarNotification[] todas = getActiveNotifications();
        synchronized (ultimas) {
            ultimas.clear();
            if (todas != null) for (StatusBarNotification s : todas) {
                // Se saltan las fijas (música, la propia de Josué…) y las que no tienen texto.
                if (s.isOngoing() || getPackageName().equals(s.getPackageName())) continue;
                if (titulo(s).isEmpty() && texto(s).isEmpty()) continue;
                ultimas.add(s);
            }
            if (ultimas.isEmpty()) return "No hay notificaciones.";
            StringBuilder sb = new StringBuilder("Notificaciones (" + ultimas.size() + "): ");
            for (int i = 0; i < ultimas.size() && i < 10; i++) {
                StatusBarNotification s = ultimas.get(i);
                sb.append(i + 1).append(") ").append(app(s)).append(", ").append(titulo(s)).append(": ").append(texto(s))
                        .append(respuesta(s) != null ? " [se puede responder]" : "").append(". ");
            }
            return sb.toString();
        }
    }

    String responder(int numero, String mensaje) {
        StatusBarNotification s = elegir(numero);
        if (s == null) return "No encontré la notificación " + numero + ". Primero léelas con leer_notificaciones.";
        Notification.Action accion = respuesta(s);
        if (accion == null) return "La notificación de " + app(s) + " no se puede responder desde aquí.";
        Intent datos = new Intent();
        Bundle valores = new Bundle();
        for (RemoteInput ri : accion.getRemoteInputs()) valores.putCharSequence(ri.getResultKey(), mensaje);
        RemoteInput.addResultsToIntent(accion.getRemoteInputs(), datos, valores);
        try {
            accion.actionIntent.send(this, 0, datos);
            return "Respondí a " + titulo(s) + " en " + app(s) + ": " + mensaje;
        } catch (PendingIntent.CanceledException e) {
            return "La notificación ya no existe; no pude responder.";
        }
    }

    String borrar(int numero) {
        if (numero <= 0) {
            cancelAllNotifications();
            return "Borré todas las notificaciones que se pueden borrar.";
        }
        StatusBarNotification s = elegir(numero);
        if (s == null) return "No encontré la notificación " + numero + ".";
        cancelNotification(s.getKey());
        return "Borré la notificación de " + app(s) + ".";
    }

    private StatusBarNotification elegir(int numero) {
        synchronized (ultimas) {
            if (ultimas.isEmpty()) leer();
            return numero >= 1 && numero <= ultimas.size() ? ultimas.get(numero - 1) : null;
        }
    }

    /** El botón "Responder" de WhatsApp, Telegram, Mensajes…: es el que trae un campo de texto. */
    private static Notification.Action respuesta(StatusBarNotification s) {
        Notification.Action[] acciones = s.getNotification().actions;
        if (acciones == null) return null;
        for (Notification.Action a : acciones) {
            if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) return a;
        }
        return null;
    }

    private static String titulo(StatusBarNotification s) {
        CharSequence t = s.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
        return t == null ? "" : t.toString();
    }

    private static String texto(StatusBarNotification s) {
        CharSequence t = s.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
        return t == null ? "" : t.toString();
    }

    private String app(StatusBarNotification s) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(s.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return s.getPackageName();
        }
    }
}
