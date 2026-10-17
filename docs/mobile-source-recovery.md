# Recuperar la actualización de la misma PR

Resolver conflictos no repite el prompt funcional ni crea otra conversación.
La operación guarda el commit exacto de main aprobado, el HEAD publicado y la
evidencia de ownership antes de preparar nada. Si AX42 no tiene ese commit,
lo obtiene del repositorio fijo y conserva una referencia privada de la operación.
No mueve el main canónico ni cambia la configuración o la versión de Codex.

Si main avanza, Recuperar conserva el objetivo original y comprueba que sigue
siendo antecesor del main observado. Nunca sustituye silenciosamente el commit
aprobado por el último disponible. Una respuesta perdida se inspecciona antes
de continuar la misma intención durable.

En Cambio se distingue:

- **Atenea está trabajando**: obteniendo código, preparando conflictos o
  comprobando el resultado. No hay que repetir la acción; se puede salir.
- **Actualización detenida**: no hay trabajo de preparación en curso. Se conserva
  el error y, si está autorizado, Recuperar operación continúa la misma operación.
- **Evidencia no válida**: no permite repetir la recuperación para forzarla.
  Ayuda explica el estado sin presentarlo como un fallo de las pruebas funcionales.
- **Preparación terminada / Codex terminó**: aún falta validar la nueva revisión
  y actualizar la misma PR. No se integra ni se publica automáticamente.

La ayuda consulta la preparación pendiente aunque la creación de PR anterior
esté completada. Abrir la ayuda, consultar o refrescar no sincroniza, no admite
un resolver y no reintenta nada.

La recuperación conocida de validación Android y la actualización de la base
son operaciones distintas. Sus pruebas, evidencias y resultados anteriores
se conservan; iniciar la preparación de conflictos invalida las proyecciones
de validación, no reescribe sus resultados históricos.

## Base preparada anterior a main

Recuperar una operación y actualizar su base son acciones distintas. Recuperar
conserva el commit autorizado originalmente; no autoriza publicar sobre otro main.
Si la preparación terminó pero main ya avanzó, Cambio muestra **Actualizar base
con main**, incluso antes de validar. No vuelve a ofrecer Actualizar la misma PR
ni pide probar primero una base que ya se sabe antigua.

La observación `sourceUpdate.baseStatus` es de sólo lectura:

- `CURRENT`: coincide con main observado; aún hacen falta las cuatro pruebas de
  la revisión resultante antes de actualizar la PR.
- `UPDATE_REQUIRED`: el operador puede pedir explícitamente una continuación v2
  de la preparación. Conserva cambio, conversación, rama, PR y recibos previos.
- `UNAVAILABLE`: no se pudo comprobar GitHub; sólo se ofrece Consultar estado.
- `NOT_REQUIRED`: no corresponde comprobar una preparación terminada o ya existe
  una finalización durable; se conserva su inspección/recuperación, sin crear otra.

La continuación de una preparación exacta no exige validar la base antigua.
Exige ownership, revisión y fingerprint retenidos, observación autenticada del
árbol sin cambios posteriores, ausencia de trabajo activo y el recibo predecesor.
Invalida la autoridad de validación anterior. No modifica el control de publicación:
siguen siendo obligatorias cuatro comprobaciones vigentes y la coincidencia con
main; si cambia entre consulta y clic, se detiene antes de crear otra entrega y
Android refresca la orientación sin repetir ninguna solicitud.

Ayuda explica este paso antes de sugerir validar o actualizar la PR. Un rechazo
de finalización sin nuevo commit no queda oculto detrás del recibo satisfactorio
de una publicación anterior. Los resultados de pruebas guardados se mantienen:
una base desactualizada no se presenta como `TESTS_FAILED`.

## Una revisión, un estado de pantalla

`GET /api/mobile/sessions/{id}/delivery` incluye la evidencia `validation` de
esa misma WorkSession. La lectura DB es `REPEATABLE_READ` y de sólo lectura:
preparación, revisión, huella y pruebas no se componen con lecturas de otra
revisión. Una identidad no confirmable conserva el historial de operaciones,
pero devuelve evidencia nula, que nunca habilita publicación.

Android usa esa observación para la tarjeta principal, las pruebas y todos los
botones de entrega. El `CURRENT` guardado en la conversación no autoriza una
revisión recién preparada. Preparar main invalida el estado local anterior;
cuando termina sin conflictos, se muestra **Validar cambio**. Sólo las cuatro
pruebas de esa revisión habilitan **Actualizar la misma PR**.

Los operadores sin rol administrador conservan la consulta y validación ya
permitidas mediante su endpoint de evidencia existente; no reciben recibos ni
capacidades de publicación administrativas.

La pulsación se bloquea antes de enviar HTTP y conserva el recibo aceptado hasta
observar esa misma operación. Una respuesta perdida sólo habilita consultar;
el polling y volver del segundo plano nunca repiten el POST. Las lecturas
antiguas, las revisiones inferiores y las huellas contradictorias no sustituyen
el estado actual. Validar e inspeccionar siguen siendo acciones diferentes.

Mientras se trabaja, la pantalla mantiene una tarjeta y un mensaje de espera.
Las versiones/APK y el historial quedan plegados, separados del siguiente paso.

## Progreso real de la validación

La tarjeta de validación muestra las cuatro comprobaciones fijas en orden:
pruebas de backend, compilación web, compilación Android y prueba del flujo web.
Se distinguen correctas, pendientes, en cola, en ejecución y resultado por
confirmar. El contador es el número de pruebas correctas de la misma revisión y
fingerprint; no es un porcentaje de trabajo ni una estimación del tiempo restante.
Un resultado de una revisión anterior nunca se cuenta en ese progreso.

V100 añade metadata nullable a `validation_operation`: `worker_state`,
`worker_observed_at` y `worker_started_at`. Se guarda únicamente al recibir una
respuesta del broker cuyo ownership ya ha sido comprobado, en los caminos de
inicio/inspección existentes. No se cambian el protocolo Platform, la admisión,
las cuatro pruebas, los reintentos ni la autoridad de publicación. No se
rellenan artificialmente las filas antiguas: una fase desconocida se muestra
como tal. `ValidationOperationResponse` expone esos tres campos de forma
aditiva, y Android también utiliza la duración terminal ya existente.

`worker_observed_at` significa **confirmación recibida por App**, no heartbeat,
salida de logs ni progreso del proceso. La lectura de evidencia no renueva esa
hora y no llama al worker. Si faltan datos, falla la consulta o transcurren más
de 30 segundos sin otra confirmación, Android muestra el último estado como
histórico/por confirmar; no afirma que siga ejecutándose ni que haya fallado.
Una hora incompatible con la del dispositivo también se indica sin inventar
tiempos negativos. No se infieren fases a partir del texto del resumen.

Los tiempos distinguen duración terminal medida por el worker, tiempo real
desde su inicio de ejecución cuando existe, y tiempo desde la solicitud
(incluye cola). El total se etiqueta como tiempo desde la primera solicitud de
esa revisión; al terminar o detenerse queda fijado al último resultado guardado.
El reloj visual sólo funciona con la pantalla en primer plano y no hace HTTP.
Volver a la app recompone el progreso desde evidencia durable, no desde el clic
local. Ni ese reloj ni desplegar los detalles avanzan o reintentan pruebas.

La acción principal y su bloqueo se conservan. Al acabar, la lista de pruebas
y sus tiempos queda en **Ver pruebas y detalles**, sin desplazar el siguiente
paso de entrega. El despliegue futuro necesita backend con V100 antes de la APK;
crear/probar esta migración no la aplica a PROD.
El estado de conversación también se refresca cuando cambia la preparación,
aunque no se haya iniciado ningún AgentRun.

La entrega requiere backend antes que APK: una APK nueva sin la evidencia de
esta API conserva la consulta, pero no se apoya en un `CURRENT` antiguo. No se
necesitan migraciones, flags nuevos ni cambios Platform.

Regresión de pantalla: `ChangeWorkflowJourneyTest` recorre la conversación real
con HTTP sintético, resumen de conversación deliberadamente antiguo, petición
lenta, segundo plano, nueva revisión, cuatro pruebas y actualización de la misma
PR. `ChangeWorkflowCoherenceTest` cubre lecturas fuera de orden, respuestas
perdidas y ausencia de duplicados, sin fixtures ni operaciones en PROD.
