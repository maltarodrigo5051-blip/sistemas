package com.rodrigo.yerbis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TextoTest {
    @Test public void extraeNombre() {
        assertEquals("Rodrigo", Texto.extraerNombre("me llamo rodrigo"));
        assertEquals("Rodrigo", Texto.extraerNombre("Hola, soy Rodrigo."));
        assertEquals("Rodrigo Malta", Texto.extraerNombre("mi nombre es rodrigo malta"));
        assertEquals("Ana", Texto.extraerNombre("Ana"));
        assertEquals("Rodrigo", Texto.extraerNombre("Hola Jarvis, me llamo Rodrigo"));
        assertEquals("Rodrigo", Texto.extraerNombre("hola rodrigo"));
        assertEquals("", Texto.extraerNombre("¿?"));
    }

    @Test public void entiendeSiYNo() {
        assertTrue(Texto.esSi("Sí"));
        assertTrue(Texto.esSi("claro que sí"));
        assertTrue(Texto.esSi("dale"));
        assertFalse(Texto.esSi("no"));
        assertFalse(Texto.esSi("no, mejor no"));
        assertFalse(Texto.esSi("qué"));
    }

    @Test public void despedidaYSaludo() {
        assertTrue(Texto.preguntaSiEstaDespierto("¿Estás despierto, Yerbis?"));
        assertTrue(Texto.preguntaSiEstaDespierto("estas despierto jarvis"));
        assertFalse(Texto.preguntaSiEstaDespierto("abre YouTube"));
        assertTrue(Texto.llamaAlAsistente("Yerbis, ¿qué hora es?"));
        assertTrue(Texto.llamaAlAsistente("oye jarvis abre youtube"));
        assertTrue(Texto.llamaAlAsistente("¿estás despierto?"));
        assertFalse(Texto.llamaAlAsistente("pásame la sal"));
        assertFalse(Texto.llamaAlAsistente("hierbas buenas"));
        assertTrue(Texto.esDespedida("Adiós YERBIS"));
        assertTrue(Texto.esDespedida("Dios"));
        assertTrue(Texto.esDespedida("ya apágate"));
        assertFalse(Texto.esDespedida("gracias a dioses griegos"));
        assertTrue(Texto.llamaAlAsistente("yarbis"));
        assertTrue(Texto.llamaAlAsistente("Josué, abre WhatsApp"));
        assertTrue(Texto.llamaAlAsistente("oye josue"));
        assertEquals("", Texto.quitarNombreDelAsistente("Oye Josué"));
        assertEquals("abre whatsapp", Texto.quitarNombreDelAsistente("Josué, abre WhatsApp"));
        assertFalse(Texto.esDespedida("abre YouTube"));
        assertEquals("Buenos días", Texto.saludo(8));
        assertEquals("Buenas tardes", Texto.saludo(15));
        assertEquals("Buenas noches", Texto.saludo(22));
        assertEquals("Buenas noches", Texto.saludo(2));
    }

    @Test public void buscaApps() {
        assertEquals(2, Texto.parecido("YouTube", "youtube"));
        assertEquals(1, Texto.parecido("YouTube Music", "youtube"));
        assertEquals(0, Texto.parecido("Cámara", "youtube"));
        assertEquals(2, Texto.parecido("Cámara", "camara"));
        assertEquals("abc123", Texto.idDeVozFish("https://fish.audio/m/abc123?x=1"));
        assertEquals("abc123", Texto.idDeVozFish(" abc123 "));
        assertEquals("", Texto.idDeVozFish("hola mundo"));
        assertEquals(35858L, Texto.segundosDeEspera("TTS 429: {\"retryDelay\": \"35858s\"}", 3600));
        assertEquals(3600L, Texto.segundosDeEspera("TTS 429: otro error", 3600));
        assertEquals("50588881234", Texto.numeroInternacional("8888-1234"));
        assertEquals("50588881234", Texto.numeroInternacional("+505 8888 1234"));
        assertEquals("15551234567", Texto.numeroInternacional("+1 (555) 123-4567"));
        assertTrue(Texto.pareceNumero("+505 8888 1234"));
        assertFalse(Texto.pareceNumero("mamá"));
    }
}
