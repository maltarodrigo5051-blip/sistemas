# Notis

Te avisa cuando tus streamers o cuentas favoritas de Kick, Twitch y YouTube entran en vivo.

**Qué demuestra:**
- Un servidor en Python (solo biblioteca estándar) que sirve la app y consulta el estado en vivo. Así se evita el bloqueo de CORS del navegador.
- No usa claves privadas y valida los nombres de usuario antes de armar cada petición.
- Una interfaz en JavaScript puro con notificaciones.

## Cómo correrlo

Requiere Python 3.

```powershell
python -I server.py 5600
```

Después abrí http://127.0.0.1:5600. En Windows también podés usar `iniciar.bat`.
