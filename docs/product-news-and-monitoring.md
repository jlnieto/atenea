# Novedades y avisos de operaciones

Novedades muestra por separado integración y disponibilidad de backend, Android
y worker. Su copia persistida es sólo presentación: nunca autoriza validación,
integración o publicación. Las acciones de entrega conservan sus comprobaciones
frescas y recibos originales.

El GET autenticado lee un snapshot PostgreSQL y registra interés en memoria; no
consulta GitHub, el publicador ni AX42. Un scheduler independiente refresca una
página acotada cada vez (máximo 50), normalmente cada 120 segundos, y reintenta
fallos tras 30 segundos. Conserva el contenido y su fecha real si falla una
fuente. Android guarda la primera página, aislada por operador, la muestra al
abrir y actualiza en segundo plano. Cerrar sesión borra esa copia.

Las notas revisadas del recurso `product-news-es-v1.json` se vinculan a repo,
número de PR y merge SHA. No se atribuyen a otro merge. Para nuevas PR se usa:

```text
## Novedades para el usuario
<!-- atenea-news:es -->
Título: Qué mejora para el usuario
- Qué cambia y qué debe hacer, en español y sin instrucciones de servidor.
```

Sin texto de producto declarado en español se muestra una descripción genérica
honesta; no se copia el título técnico como si fuera una novedad explicada.
La nota histórica sobre avisos SSH se destaca y comprueba contra el despliegue.

## Monitor cada cinco minutos

Se reutilizan las comprobaciones Apache/web y el transporte FCM existentes.
La periodicidad sigue siendo 300000 ms por defecto; los trabajos lentos de
entrega o Novedades no comparten el hilo del monitor.

- Sano: cero avisos.
- Apache inactivo o web caída confirmada: un evento nuevo en **cada ciclo**.
- Lentitud web: tres observaciones consecutivas para confirmar, después un
  evento nuevo en cada ciclo mientras persista.
- SSH fallido: estado Apache **desconocido**, no caída demostrada. Tras tres
  fallos consecutivos se avisa de imposibilidad de verificación una vez hasta
  una recuperación observada. Los fallos aislados no avisan.
- Una comprobación que no termina no es una recuperación: rompe la secuencia
  de confirmación, pero no rearma un aviso SSH ya emitido.

V104 persiste observación, contadores, evento por ciclo, bandeja por operador y
entrega por dispositivo en una transacción. Repetir el mismo check no duplica el
evento. Reiniciar App conserva la política. La entrega FCM tiene lease de 60 s,
fencing por intento, hasta cinco intentos y caducidad de diez minutos; retiene
errores terminales y no marca aceptado un proveedor deshabilitado. La caducidad
restante también se envía como TTL a FCM: un reintento no prolonga el plazo ni
deja un aviso antiguo en su cola durante semanas. Reintentar una
entrega conserva su event ID; el siguiente ciclo degradado usa otro ID y por
tanto no se deduplica como incidente. La bandeja conserva el aviso aunque FCM
falle; los mensajes incluyen la hora de comprobación UTC. Aceptado por FCM no
demuestra recepción física en el móvil.

El filtrado no corrige por sí mismo una conexión SSH: un exit 255 sin detalle no
permite atribuir una causa de red, autenticación o servicio. No se declara una
caída de Apache ni una reparación de SSH a partir de ese dato.

## Validación

Tests de política, scheduler, proyección y dispatcher; integración PostgreSQL 16
V103 → V104 con datos conservados, reinicio simulado del servicio, doble ciclo,
idempotencia, reintento por dispositivo, leases, fencing y snapshot persistido.
Tests Android de protocolo/cache y Compose de Novedades y campana vectorial.
No requiere fallos artificiales ni fixtures en PROD.

La campana usa el vector Google Material Symbols `notifications`, outlined 24 px
(Apache 2.0), con badge separado y descripción accesible; no un emoji ni una
fuente descargable.
