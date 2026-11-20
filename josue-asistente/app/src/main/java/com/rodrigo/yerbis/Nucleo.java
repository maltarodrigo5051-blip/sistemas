package com.rodrigo.yerbis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * El "cuerpo" del asistente: una esfera de luz que se dibuja en vivo.
 * Tres capas con forma orgánica giran y ondulan; la voz (tuya o suya) las deforma.
 */
public class Nucleo extends View {

    public enum Estado { ESPERA, ESCUCHANDO, PENSANDO, HABLANDO, SIN_CONEXION, ERROR }

    private final Paint pintura = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path forma = new Path();
    private Estado estado = Estado.ESPERA;
    private float nivel, nivelSuave;           // volumen 0..1
    private float energia = 0.3f;              // cuánto se mueve (sube y baja suave)
    private int[] colores = paleta(Estado.ESPERA), coloresDestino = colores;
    private float mezcla = 1f;
    private final long nacimiento = System.nanoTime();

    public Nucleo(Context c, AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setEstado(Estado e) {
        if (e == estado) return;
        estado = e;
        colores = coloresActuales();
        coloresDestino = paleta(e);
        mezcla = 0f;
    }

    /** Volumen de la voz que se oye ahora, de 0 a 1. */
    public void setNivel(float n) { nivel = Math.max(0f, Math.min(1f, n)); }

    private static int[] paleta(Estado e) {
        switch (e) {
            case ESCUCHANDO:   return new int[]{0xFF00E5FF, 0xFF2979FF, 0xFF7C4DFF};
            case PENSANDO:     return new int[]{0xFFB388FF, 0xFF7C4DFF, 0xFF00B0FF};
            case HABLANDO:     return new int[]{0xFFFFFFFF, 0xFF80F0FF, 0xFF2979FF};
            case SIN_CONEXION: return new int[]{0xFF546E7A, 0xFF37474F, 0xFF263238};
            case ERROR:        return new int[]{0xFFFF5252, 0xFFFF1744, 0xFF880E4F};
            default:           return new int[]{0xFF00B8D4, 0xFF1E3A8A, 0xFF6D28D9};
        }
    }

    private int[] coloresActuales() {
        int[] r = new int[3];
        for (int i = 0; i < 3; i++) r[i] = mezclar(colores[i], coloresDestino[i], mezcla);
        return r;
    }

    private static int mezclar(int a, int b, float t) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private float energiaDe(Estado e) {
        switch (e) {
            case ESCUCHANDO: return 0.55f;
            case PENSANDO: return 0.8f;
            case HABLANDO: return 0.6f;
            case SIN_CONEXION: return 0.08f;
            case ERROR: return 0.9f;
            default: return 0.25f;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float t = (System.nanoTime() - nacimiento) / 1e9f;
        nivelSuave += (nivel - nivelSuave) * 0.25f;
        energia += (energiaDe(estado) - energia) * 0.05f;
        mezcla = Math.min(1f, mezcla + 0.04f);
        int[] col = coloresActuales();

        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(getWidth(), getHeight()) * 0.26f;
        float respiro = 1f + 0.03f * (float) Math.sin(t * 1.6f);        // pulso lento en reposo
        float voz = 1f + nivelSuave * 0.35f;                              // crece con la voz
        float r = base * respiro * voz;
        float velocidad = estado == Estado.PENSANDO ? 2.6f : 1f;

        // 1. Halo exterior grande y suave.
        float halo = Math.min(r * 2.4f, Math.min(cx, cy)); // que no se corte en los bordes de la vista
        pintura.setShader(new RadialGradient(cx, cy, halo,
                new int[]{conAlfa(col[0], 0.55f), conAlfa(col[1], 0.18f), 0x00000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, halo, pintura);

        // 2. Tres capas orgánicas que giran a distinto ritmo.
        for (int capa = 2; capa >= 0; capa--) {
            float escala = 1f + capa * 0.12f;
            float fase = capa * 2.1f;
            armarForma(cx, cy, r * escala, t * velocidad, fase, energia * (1f + nivelSuave * 1.5f));
            int a = col[capa], b = col[Math.min(capa + 1, 2)];
            pintura.setShader(new RadialGradient(cx - r * 0.25f, cy - r * 0.3f, r * escala * 1.3f,
                    new int[]{conAlfa(a, 0.95f - capa * 0.2f), conAlfa(b, 0.55f - capa * 0.12f)},
                    null, Shader.TileMode.CLAMP));
            c.drawPath(forma, pintura);
        }

        // 3. Núcleo brillante: más intenso cuando habla.
        float brillo = estado == Estado.HABLANDO ? 0.9f + nivelSuave * 0.1f : estado == Estado.SIN_CONEXION ? 0.15f : 0.55f;
        pintura.setShader(new RadialGradient(cx - r * 0.15f, cy - r * 0.2f, r * 0.75f,
                new int[]{conAlfa(0xFFFFFFFF, brillo), conAlfa(col[0], brillo * 0.3f), 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 0.75f, pintura);

        postInvalidateOnAnimation(); // siguiente cuadro
    }

    /** Borde ondulado: suma de senos a distintas frecuencias, como una gota viva. */
    private void armarForma(float cx, float cy, float r, float t, float fase, float e) {
        forma.reset();
        int puntos = 72;
        for (int i = 0; i <= puntos; i++) {
            double ang = 2 * Math.PI * i / puntos;
            double onda = Math.sin(ang * 3 + t * 1.3 + fase) * 0.05
                    + Math.sin(ang * 5 - t * 1.9 + fase * 1.7) * 0.035
                    + Math.sin(ang * 2 + t * 0.7 - fase) * 0.04;
            float rr = r * (float) (1 + onda * e * 2.2);
            float x = cx + rr * (float) Math.cos(ang), y = cy + rr * (float) Math.sin(ang);
            if (i == 0) forma.moveTo(x, y); else forma.lineTo(x, y);
        }
        forma.close();
    }

    private static int conAlfa(int color, float alfa) {
        return Color.argb((int) (255 * Math.max(0f, Math.min(1f, alfa))), Color.red(color), Color.green(color), Color.blue(color));
    }
}
