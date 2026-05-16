# Conversation Model

Este documento define el modelo interno necesario para que la voz sea natural, segura y recuperable.

## Foco operativo

Atenea debe mantener un foco operativo unico.

El foco responde:

- dominio actual;
- objetivo actual;
- entidad activa;
- estado de ejecucion;
- cursor de lectura;
- notas pendientes;
- acciones pendientes de confirmacion.

Ejemplo:

```json
{
  "domain": "development",
  "project": "fomasys",
  "workSessionId": 123,
  "surface": "worksession_conversation",
  "activity": "reading_codex_response",
  "readCursor": {
    "responseId": "turn-456",
    "segmentIndex": 2,
    "segmentCount": 6
  },
  "pendingVoiceNotes": 3,
  "pendingConfirmation": null
}
```

Dominios iniciales:

- `none`: sin foco operativo.
- `development`: proyectos, worksessions, Codex, PRs, deliverables.
- `operations`: servidores, Apache, incidencias, checks.
- `communications`: email y futuras comunicaciones.
- `personal`: notas, recordatorios y contexto personal futuro.

## Cambio de foco

El foco cambia solo cuando el usuario lo pide o cuando una accion confirmada lo implica.

Ejemplos:

```text
Atenea, vuelve a fomasys.
Atenea, cambia a comunicaciones.
Atenea, vamos a operaciones.
```

Si el usuario esta en `development/fomasys` y dice:

```text
Atenea, revisa el email.
```

Atenea cambia el foco a `communications`.

Respuesta esperada:

```text
Cambio a comunicaciones. Voy a revisar el email.
```

## Estados

Estados minimos del motor:

- `IDLE`: no hay sesion de voz activa.
- `AWAITING_WAKE_WORD`: esperando `Atenea`.
- `LISTENING`: escuchando instruccion.
- `THINKING`: procesando.
- `SPEAKING`: Atenea esta hablando.
- `INTERRUPTED`: el usuario ha hablado mientras Atenea hablaba.
- `ANSWERING_CLARIFICATION`: Atenea responde aclaracion.
- `CAPTURING_NOTE`: Atenea guarda una nota.
- `RESUME_PENDING`: hay lectura pausada que puede continuar.
- `EXECUTING_ACTION`: ejecutando accion.
- `WAITING_CONFIRMATION`: accion sensible pendiente de confirmacion.
- `ERROR_RECOVERY`: fallo de red/proveedor/permiso y recuperacion.

## Segmentos de respuesta

Toda respuesta larga debe transformarse en segmentos.

Ejemplo:

```json
[
  {"type": "summary", "title": "Resumen"},
  {"type": "step", "title": "Tarea 1"},
  {"type": "step", "title": "Tarea 2"},
  {"type": "step", "title": "Tarea 3"},
  {"type": "next_action", "title": "Siguiente accion"}
]
```

El cursor de lectura apunta siempre a un segmento.

Esto permite:

- repetir lo ultimo;
- volver al punto anterior;
- saltar al siguiente;
- pedir detalle de un punto;
- interrumpir y continuar donde estaba.

## Interrupciones

Cuando el usuario habla mientras Atenea esta hablando, Atenea debe cortar o atenuar el audio y clasificar la interrupcion.

Tipos:

- `PLAYBACK_CONTROL`: para, sigue, repite, vuelve atras, salta.
- `CLARIFICATION`: no entendi, explicame mejor, que significa.
- `NOTE_CAPTURE`: apunta, guarda nota, recuérdame.
- `NEW_COMMAND`: revisa email, abre fomasys, recupera Apache.
- `FOCUS_CHANGE`: cambia a comunicaciones, vuelve a operaciones.
- `CONFIRMATION`: confirmo, cancela.

Regla:

Si la interrupcion es aclaracion o nota, Atenea conserva el cursor y puede continuar.

Ejemplo:

```text
Estado: SPEAKING segmento 3
Usuario: Atenea, apunta que hay que revisar permisos.
Estado: CAPTURING_NOTE
Atenea: Nota guardada.
Estado: SPEAKING segmento 3
```

## Notas de voz

Las notas son globales.

Se guarda:

- transcripcion;
- fecha;
- foco operativo en el momento de captura;
- origen: voz;
- estado: activa, enviada, archivada;
- prompt en el que se consumio, si aplica.

No se guarda audio original por defecto.

Flujo:

```text
Usuario: Atenea, apunta revisar permisos en deploy.
Atenea: Nota guardada.

Usuario: Atenea, leeme las notas.
Atenea: Tienes una nota: revisar permisos en deploy.

Usuario: Atenea, manda las notas como prompt y pide que lo priorice.
Atenea: Voy a enviar una nota como prompt en el foco actual. Confirmas?
Usuario: Confirmo.
Atenea: Enviado. He limpiado la bandeja activa.
```

Despues de enviar notas como prompt:

- pasan a estado `sent`;
- se limpia la bandeja activa;
- queda historico.

## Confirmaciones

Acciones sensibles requieren confirmacion explicita.

Ejemplos:

- recuperar Apache;
- enviar email;
- cerrar incidencia;
- deploy;
- publish PR;
- cerrar worksession;
- ejecutar prompt que pueda modificar codigo o infraestructura.

Formato:

```text
Atenea: Voy a recuperar Apache en dedicado. Es una accion operativa. Confirmas?
Usuario: Confirmo.
Atenea: Ejecutando.
```

La confirmacion debe estar vinculada a un token interno de accion, no solo al texto.
