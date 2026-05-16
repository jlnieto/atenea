# Voice Commands

Este documento define el vocabulario natural inicial. No son frases rigidas; son ejemplos que el motor debe mapear a intenciones.

## Activacion

La palabra obligatoria es `Atenea`.

Ejemplos:

```text
Atenea, donde estas?
Atenea, dime como vas.
Atenea, revisa el email.
```

Durante una sesion activa, tambien se mantiene `Atenea` como prefijo para evitar acciones accidentales.

## Corte Android actual

La app Android implementa ya un primer interprete local:

- si el dictado no contiene `Atenea`, se guarda como nota activa;
- si contiene `Atenea`, se interpreta como orden;
- `Atenea, para` detiene escucha o lectura;
- `Atenea, repite` vuelve a leer el segmento actual;
- `Atenea, sigue` o `Atenea, continua` reanuda la lectura actual o vuelve al punto de retorno;
- `Atenea, siguiente` avanza al siguiente segmento;
- `Atenea, anterior` vuelve al segmento anterior;
- `Atenea, segmento uno`, `Atenea, segmento tres` y variantes saltan a un segmento concreto;
- `Atenea, empieza` o `Atenea, desde el principio` reinicia la lectura desde el primer segmento;
- `Atenea, lee la respuesta` lee el segmento actual;
- `Atenea, nota` abre una nota en modo bloque;
- `Atenea, guarda nota ...` abre una nota en modo bloque con texto inicial;
- `Atenea, fin` cierra la nota o bloque activo;
- `Atenea, manda las notas` envia las notas activas como prompt;
- `Atenea, manda las notas` prepara un envio backend con destino explicito, no lo ejecuta directamente;
- `Atenea, confirmo` confirma primero el envio de notas pendiente si existe;
- `Atenea, confirmo` despues confirma acciones locales pendientes o confirmaciones Core;
- `Atenea, cancela` cancela el envio de notas pendiente y conserva las notas activas;
- el cursor de lectura queda persistido en `voice_focus.playback`;
- `Atenea, no he entendido`, `Atenea, explicame esto`, `Atenea, dame un ejemplo` y variantes lanzan una aclaracion sobre el segmento actual;
- despues de una aclaracion, `Atenea, sigue` recupera la lectura anterior desde el punto de retorno;
- cualquier otra frase con `Atenea` se envia a `Core` como comando de voz.

## Estado y foco

```text
Atenea, donde estas?
Atenea, en que estas?
Atenea, dime el foco actual.
Atenea, que estas haciendo?
```

Respuesta esperada:

```text
Estoy en el proyecto fomasys, en la conversacion activa. Codex ya respondio y tengo dos notas pendientes.
```

## Navegacion de foco

```text
Atenea, abre fomasys.
Atenea, vuelve a la conversacion activa.
Atenea, cambia a comunicaciones.
Atenea, vamos a operaciones.
Atenea, vuelve donde estabas antes.
```

## Lectura y reproduccion

```text
Atenea, resume.
Atenea, dame detalle.
Atenea, sigue.
Atenea, continua.
Atenea, pausa.
Atenea, repite esto.
Atenea, repite el ultimo punto.
Atenea, vuelve al punto anterior.
Atenea, salta al siguiente.
Atenea, siguiente.
Atenea, segmento tres.
Atenea, empieza.
Atenea, vuelve dos parrafos atras.
```

## Aclaraciones

```text
Atenea, no he entendido la tarea dos.
Atenea, explicame mejor esto.
Atenea, que significa eso?
Atenea, dame un ejemplo.
```

Regla:

Despues de la aclaracion, Atenea debe poder continuar por donde iba. En el corte Android actual se recupera el segmento activo, no el offset exacto de audio dentro del segmento. Por eso los segmentos deben mantenerse en un tamano escuchable.

## Notas

```text
Atenea, apunta revisar permisos.
Atenea, guarda nota: preguntar por el deploy.
Atenea, anade a notas que revise el cliente aleman.
Atenea, leeme las notas.
Atenea, cuantas notas tengo?
Atenea, manda las notas como prompt.
Atenea, manda las notas como prompt y pide que priorice lo urgente.
Atenea, limpia las notas.
```

Reglas:

- las notas son siempre modo bloque;
- abrir una nota no cambia el foco;
- abrir una nota durante una lectura guarda un punto de retorno;
- cerrar una nota con `Atenea, fin` permite continuar la lectura anterior;
- enviar notas como prompt requiere confirmacion si puede ejecutar acciones;
- la confirmacion debe decir a que proyecto y WorkSession se van a enviar;
- al confirmar, backend crea un `SessionTurn` real en la WorkSession activa;
- al confirmar correctamente, la bandeja activa se limpia y queda historico;
- si el foco ya no apunta a la WorkSession preparada, el envio falla y las notas no se consumen.

## Development

```text
Atenea, abre el proyecto fomasys.
Atenea, dime el siguiente paso.
Atenea, que respondio Codex?
Atenea, resume la respuesta de Codex.
Atenea, continua la worksession.
Atenea, manda este prompt.
Atenea, crea una nueva conversacion para fomasys.
```

## Communications

```text
Atenea, revisa el email.
Atenea, dime si hay algo importante.
Atenea, resume los dos emails importantes.
Atenea, que dice el primero?
Atenea, responde al primero con esto.
Atenea, del segundo crea una tarea.
```

Regla:

Si se pide revisar email desde otro foco, Atenea cambia el foco a `communications`.

## Operations

```text
Atenea, como esta el dedicado?
Atenea, comprueba Apache.
Atenea, recupera Apache en el dedicado.
Atenea, revisa incidencias.
Atenea, dime si las webs responden.
```

Regla:

Recuperar Apache requiere confirmacion.

## Confirmaciones

```text
Atenea, confirmo.
Atenea, cancela.
Atenea, espera, antes dime que vas a hacer.
```

Confirmacion esperada:

```text
Atenea: Voy a recuperar Apache en dedicado. Confirmas?
Usuario: Atenea, confirmo.
```
