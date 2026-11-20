# Josué (antes YERBIS): asistente personal de voz

YERBIS es una app de Android, escrita en **Java**. Te escucha, le pregunta a **Gemini** y te contesta en voz alta.
El mismo APK sirve para el **teléfono** y para la **TV con Android**.

## Qué hace esta versión

| Función | Cómo funciona |
|---|---|
| Primer inicio por voz | Te pregunta tu nombre, te pide permiso para guardarlo y te da la bienvenida. |
| Memoria del nombre | Guarda tu nombre en el teléfono. Las siguientes veces te saluda: "Buenos días, señor Rodrigo". |
| Chat de voz con Gemini | Tocas **Hablar**, dices algo, YERBIS responde en voz alta y vuelve a escuchar solo (manos libres). |
| "¿Estás despierto, YERBIS?" | Responde "Sí, señor Rodrigo." al instante, sin preguntarle a Gemini. |
| Voz con IA | Usa la voz "Charon" de Gemini (grave y serena) con tu misma API key. **Mantén presionado el reactor** para probar otras voces (Orus, Algieba, Alnilam…) o la del teléfono. Si no hay internet, usa la voz del teléfono. |
| Pantalla YERBIS 2.0 | Encabezado con indicador EN LÍNEA, núcleo de voz con animación por estado (espera, escuchando, procesando, hablando, sin conexión) y barra con Inicio, Chats, Voz, Memoria y Ajustes. |
| Escucha sin tocar | Mientras la app está abierta, escucha siempre y reacciona al oír "YERBIS". Después de responder, deja 30 segundos para seguir la plática. |
| Interrumpir | Si tocas **Hablar** mientras YERBIS habla, se calla y te escucha. |
| Comando: decir la hora | "¿Qué hora es?" |
| Comando: abrir una app | "Abre YouTube", "abre WhatsApp". |
| Comando: preparar un mensaje | "Mándale a Mamá que llego tarde". YERBIS te lee el mensaje y espera tu **"sí"** por voz. Después abre la app de SMS con el texto escrito, y tú tocas Enviar. Nunca envía nada sin que lo confirmes. |
| Terminar | "Adiós", "hasta luego" o "eso es todo". Si te quedas callado, YERBIS se pausa. |

Los comandos usan el *function calling* de Gemini: Gemini decide qué función llamar y la app la ejecuta con *intents* normales de Android. Esta versión no usa el permiso de accesibilidad.

## Archivos importantes

```
app/src/main/java/com/rodrigo/yerbis/
  MainActivity.java  -> pantalla, micrófono, voz y el flujo de la conversación
  Gemini.java        -> llama a la API REST de Gemini y guarda el historial
  Comandos.java      -> las funciones (abrir app, mensaje, hora)
  Texto.java         -> reglas de texto (sacar el nombre, entender "sí"/"no")
app/src/test/.../TextoTest.java -> pruebas de Texto.java
```

La app no usa librerías externas: el micrófono (`SpeechRecognizer`), la voz (`TextToSpeech`), la red y el JSON vienen con Android.

## Lo que necesitas (una sola vez)

1. **Android Studio**: ya está instalado en esta PC, con el SDK en `%LOCALAPPDATA%\Android\Sdk`.
   El proyecto usa Gradle 9.9.2 y el plugin de Android 9.5.1. Ya compila y pasa sus pruebas:
   ```powershell
   .\gradlew.bat assembleDebug testDebugUnitTest
   ```
   El APK queda en `app\build\outputs\apk\debug\app-debug.apk`.
2. **Una API key de Gemini** (gratis): entra a https://aistudio.google.com/apikey con tu cuenta de Google y toca "Create API key".
   **No la pegues en el código ni la subas a GitHub.** Se escribe dentro de la app y queda guardada solo en tu teléfono.

## Correrlo en tu teléfono

1. En el teléfono, activa las **Opciones de desarrollador**: Ajustes > Acerca del teléfono > toca 7 veces "Número de compilación".
2. En Ajustes > Opciones de desarrollador, activa **Depuración por USB**.
3. Conecta el teléfono a la PC con un cable USB y acepta el mensaje "¿Permitir depuración USB?".
4. Abre Android Studio > **Open** > elige la carpeta `yerbis`. Espera a que termine "Gradle sync" (la primera vez descarga cosas y tarda).
   Si Android Studio te sugiere actualizar la versión del plugin de Android (AGP), puedes aceptar.
5. Arriba, elige tu teléfono en la lista de dispositivos y toca el botón verde ▶ **Run**.
   Sin abrir Android Studio también funciona: `adb install app\build\outputs\apk\debug\app-debug.apk`
   (`adb` está en `%LOCALAPPDATA%\Android\Sdk\platform-tools`).
6. En el teléfono, acepta los permisos de **micrófono** y **contactos** (contactos sirve para buscar a quién mandarle el mensaje).
7. Escribe tu API key en el campo de arriba y toca **Guardar**.
8. YERBIS te va a preguntar tu nombre. Contesta, por ejemplo, "me llamo Rodrigo".

Si no se escucha la voz, revisa en Ajustes > Sistema > Idioma > **Texto a voz** que haya una voz en español.
Para reconocer tu voz se necesita la app de Google, que casi todos los teléfonos Android ya traen.

## Correrlo en tu TV con Android

Es el mismo APK. Aparece en el menú de apps de la TV y se usa con el control remoto: el botón **Hablar** ya tiene el foco, así que solo presionas OK.
En algunos controles, el botón de búsqueda o de micrófono también activa a YERBIS.

1. En la TV: Ajustes > Dispositivo > Acerca de > presiona 7 veces "Compilación". Luego, en Opciones de desarrollador, activa **Depuración por USB** o **Depuración inalámbrica**.
2. Busca la IP de la TV (Ajustes > Red).
3. En la terminal de Android Studio (así se conecta por Wi-Fi):
   ```powershell
   adb connect 192.168.1.50:5555
   ```
   Cambia `192.168.1.50` por la IP de tu TV y acepta el mensaje que aparece en la pantalla de la TV.
4. Elige la TV en la lista de dispositivos de Android Studio y toca ▶ **Run**.

Para que YERBIS te escuche, la TV necesita micrófono, ya sea en el control remoto o en la misma TV. Escribir la API key con el control es incómodo, pero solo se hace una vez.

## ¿Y en la PC?

Todavía no hay versión para PC. Primero tiene que funcionar bien en el teléfono. El plan más pequeño es este:

- Hacer una app de consola en Java que **reutilice `Gemini.java` y `Texto.java` tal cual** (solo se agrega el `.jar` de `org.json`).
- La memoria (tu nombre y la clave) se guarda en un archivo de tu carpeta de usuario, fuera del proyecto.
- Para la voz se usa la que ya trae Windows (`System.Speech`) y se llama desde Java, así no hay que instalar nada.
- Los comandos de PC (abrir programas, etc.) van en una clase aparte, como `Comandos.java`.

## Cómo borrar el nombre o la clave

Ajustes > Apps > YERBIS > Almacenamiento > **Borrar datos**. La próxima vez que la abras, YERBIS empieza de cero.

## Siguientes pasos (todavía no están hechos)

- **Palabra de activación siempre activa** ("Oye YERBIS") con la pantalla apagada. Necesita un *servicio en primer plano* con una notificación fija, porque Android no deja usar el micrófono en segundo plano sin avisarle al usuario.
- **Notificaciones** (recordatorios, avisos).
- **Conexión con tu cuenta de Google** (Calendar, Gmail) usando inicio de sesión de Google.
- **Enviar por WhatsApp**: abrir WhatsApp directo con el contacto.
- **Control desde otro dispositivo** (por ejemplo, hablarle al teléfono para que haga algo en la TV).
- Más comandos que usen el permiso de **accesibilidad**. Se le pedirá al usuario de forma explícita.

## Limitaciones que conviene saber

- Mientras otra app está abierta, YERBIS no escucha. Por eso, después de abrir una app, se calla hasta que vuelvas.
- Si Gemini pide dos funciones a la vez, solo se ejecuta la primera.
- La conversación se olvida al cerrar la app. Solo se guardan tu nombre y la clave.
- El modelo es `gemini-3.8-flash` (si Google lo retira, YERBIS prueba `gemini-flash-latest`). Están en `MODELOS`, dentro de `Gemini.java`.
- La voz IA de Gemini gratis solo da unas 10 frases al día. Después YERBIS usa la voz del teléfono hasta que se renueve la cuota.

## Josué en segundo plano (versión 0.3)

- **Siempre escuchando:** un servicio con notificación fija («Josué está escuchando») lo mantiene vivo con la app cerrada. El botón **Detener** de la notificación lo apaga.
- **Burbuja flotante:** un núcleo pequeño sobre cualquier app. Tócalo para hablar y arrástralo para moverlo. Muestra en un globito lo que responde.
- **Nombre:** se activa con **«Josué»**. Por costumbre, también responde a YERBIS y Jarvis. «Adiós» lo duerme.
- **Apps duplicadas:** si hay dos copias (por ejemplo, el WhatsApp del Segundo espacio de Xiaomi), te pregunta cuál quieres.
- **Control del teléfono:** like (doble toque), siguiente o anterior video, atrás, inicio, recientes, captura, bloquear, timbre (silencio, vibrar o normal), volumen y música.
- **Notificaciones:** te las lee, responde lo que le dictes (siempre pregunta «¿la envío?» antes) y las borra.
- **Modo silencio:** «Josué, cállate» o «ponte en mute» → responde solo por texto. «Vuelve a hablar» → vuelve a hablar en voz alta.

### Permisos que activas tú (Ajustes › pestaña Ajustes de la app)

| Botón | Para qué |
|---|---|
| Burbuja sobre otras apps | Mostrar la bolita flotante |
| Controlar la pantalla (Accesibilidad) | Like, deslizar, atrás, capturas… |
| Leer y responder notificaciones | La barra de notificaciones |
| No molestar | Poner el teléfono en silencio total |
| Batería | Que Xiaomi no cierre a Josué |

En Xiaomi, si Accesibilidad o Notificaciones salen bloqueadas: Ajustes › Apps › Josué › ⋮ › **Permitir ajustes restringidos**. Además conviene activar **Inicio automático** para Josué.
