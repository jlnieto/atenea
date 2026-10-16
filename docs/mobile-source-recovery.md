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
