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
