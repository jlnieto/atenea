# Migración Android Nativa

Este documento fija la decisión, el camino y los límites de la migración de la app móvil de Atenea hacia una aplicación Android nativa.

La intención es que esta guía sea el punto de referencia para no perder contexto cuando el trabajo avance por fases. Si hay conflicto entre este documento y el código runtime, prevalece el código. Si hay conflicto entre este documento y documentación móvil anterior basada en Expo, este documento define la dirección nueva.

## Decisión

Atenea debe evolucionar hacia una app Android nativa como superficie móvil principal.

La app actual en `mobile/` sigue siendo útil como baseline funcional y referencia de UX/API, pero deja de ser la arquitectura objetivo para la herramienta móvil personal de máxima fiabilidad.

La migración no debe hacerse como un rewrite destructivo. Debe hacerse en paralelo:

- mantener la app Expo/React Native actual mientras sea útil para operar
- crear una app Android nativa nueva
- reutilizar el backend y los contratos existentes
- migrar primero las superficies críticas
- retirar o congelar la app Expo sólo cuando la app nativa cubra el uso diario real

## Por qué ahora

El objetivo de producto ha cambiado de "cliente móvil operable" a "herramienta personal principal para trabajo, operaciones y vida diaria".

Las necesidades nuevas son:

- máxima fiabilidad del canal de voz
- baja latencia desde el primer segundo
- operación real de servidores desde móvil
- acciones críticas con confirmación y auditoría
- notificaciones accionables
- posible modo asistente, foreground service y wake word local en Android
- integración profunda con el dispositivo
- uso intensivo por una sola persona, sin necesidad de optimizar para distribución masiva multiplataforma

Expo/React Native permitió validar producto, backend y flujos. Para la siguiente etapa, la capa JavaScript limita el control sobre audio, background, permisos, servicios, batería, widgets, shortcuts y automatizaciones Android.

## Alcance de la migración

La migración Android nativa cubre la app cliente. No implica rehacer el backend.

Se mantienen como fuentes de verdad:

- `Atenea Core` como entrada conversacional top-level
- `Project`
- `WorkSession`
- `SessionTurn`
- `AgentRun`
- dominio `operations`
- contratos `/api/core/*`
- contratos `/api/mobile/*` para lecturas agregadas, auth, inbox y vistas compactas

La app nativa debe consumir el backend existente y sólo pedir cambios backend cuando un flujo móvil real lo exija.

## No objetivos

Esta migración no debe:

- reintroducir `Task` / `TaskExecution`
- duplicar lógica de negocio del backend en Android
- crear un segundo modelo de workflow separado de `WorkSession`
- saltarse confirmaciones de Core para acciones sensibles
- convertir la app en un cliente genérico para muchos usuarios
- diseñar primero para iOS
- intentar wake word desde el primer bloque
- romper la app Expo actual antes de que la nativa sea operable

## Plataforma objetivo

Plataforma primaria:

- Android moderno
- Kotlin
- Jetpack Compose
- app instalada en dispositivo principal del operador

Dispositivo esperado:

- Samsung Galaxy S Ultra de gama alta o equivalente
- almacenamiento 512 GB o 1 TB
- batería y CPU suficientes para voz, caché, logs y operación intensiva

La arquitectura no debe depender de un modelo Samsung concreto, pero sí puede optimizar el uso para un dispositivo Android potente y personal.

## Estado actual de la app Expo

La app actual en `mobile/` aporta:

- login móvil
- shell con Core, Inbox, Projects, Session, Rescue y Billing
- Core Console como entrada conversacional
- conversación de WorkSession
- canal de rescate
- operaciones iniciales sobre servidor dedicado
- voz push-to-talk con transcripción backend
- notificaciones Expo baseline
- almacenamiento seguro de sesión

Último endurecimiento relevante de voz:

- existe un motor común en `mobile/src/voice/useAteneaVoiceEngine.ts`
- Core, Conversación y Rescate usan el mismo motor
- la detección de silencio por medidor visual fue retirada
- el motor añade prewarm, readiness gate, watchdog y telemetría

Esta app queda como referencia funcional, pero no como destino final.

## Arquitectura Android objetivo

La app nativa debe ser modular.

Módulos propuestos:

- `:app`
  - shell principal
  - navegación
  - tema
  - lifecycle
  - composición de pantallas

- `:api`
  - cliente HTTP
  - autenticación
  - DTOs de Core, Mobile, Operations y WorkSession
  - SSE o streaming equivalente
  - manejo común de errores

- `:secure`
  - Android Keystore
  - almacenamiento cifrado
  - tokens
  - desbloqueo biométrico si aplica

- `:voice-engine`
  - captura con `AudioRecord` o API nativa equivalente
  - readiness real
  - watchdog
  - VAD
  - buffers
  - telemetría
  - subida de audio a backend
  - transcripción
  - futuros modos de foreground service y wake word

- `:core-console`
  - pantalla conversacional principal
  - scope global/proyecto/sesión
  - confirmaciones
  - aclaraciones
  - historial de comandos
  - respuestas operativas estructuradas

- `:operations`
  - hosts gestionados
  - estado de servicios
  - checks web
  - incidentes
  - recoveries
  - explicación detallada de lo ejecutado y verificado

- `:worksession`
  - resumen de sesión
  - conversación
  - runs
  - publish
  - sync PR
  - close
  - deliverables relacionados con sesión

- `:rescue`
  - canal operativo de rescate
  - conversación directa
  - estado del repo/proyecto

- `:notifications`
  - push
  - acciones desde notificación
  - routing interno
  - alertas críticas

- `:local-store`
  - caché local
  - estado offline parcial
  - historial reciente
  - preferencias de operador

## Principios de diseño

La app nativa debe seguir estos principios:

- Core-first: las mutaciones operativas deben pasar por `Atenea Core` salvo contrato explícito contrario.
- Mobile-safe: toda acción crítica debe ser confirmable, auditable y recuperable.
- Voz como canal: la voz no crea otro dominio, sólo transporta intención hacia Core.
- Lecturas densas: móvil debe reducir navegación, no multiplicar pantallas.
- Sin ruido visual: la UI debe favorecer ejecución rápida y claridad operativa.
- Estado explícito: la app debe decir qué está haciendo, qué ha hecho y cómo lo ha verificado.
- Fallos accionables: un error debe indicar si falló permiso, red, backend, transcripción, SSH, runbook o validación.
- Reanudable: una acción iniciada desde móvil debe poder observarse después de perder cobertura o bloquear pantalla.
- Personal primero: se optimiza para el operador único y su flujo real.

## Modelo de navegación objetivo

La primera pantalla útil pasa a ser `Inicio`, una superficie compacta que combina estado operativo y entrada conversacional inmediata.

La shell nativa v1 está documentada en `docs/android-native-shell-v1.md`.

Navegación principal actual:

- Inicio
- Core
- Ops
- Ajustes

Navegación objetivo ampliada:

- Core
- Operaciones
- WorkSessions
- Rescate
- Inbox
- Personal
- Ajustes

Inicio debe permitir escribir a Atenea sin decidir primero un dominio.

Core debe seguir siendo la superficie conversacional completa para texto y voz.

Operaciones debe permitir resolver problemas reales sin entrar por árbol de menús.

WorkSessions debe permitir seguir trabajo de desarrollo cuando el contexto pide detalle.

Rescate debe existir para situaciones de bloqueo o emergencia técnica.

Inbox debe agrupar atención pendiente, no ser una bandeja decorativa.

Personal queda reservado para la futura capa de vida diaria, agenda, recordatorios, decisiones y notas personales.

## Motor de voz nativo

El motor de voz debe ser un módulo independiente.

Estados mínimos:

- `Idle`
- `RequestingPermission`
- `Preparing`
- `WarmingUp`
- `Ready`
- `Listening`
- `SpeechDetected`
- `Finalizing`
- `Transcribing`
- `Completed`
- `Failed`

Capacidades fase 1:

- push-to-talk fiable
- preparación anticipada del audio
- inicio con readiness real
- watchdog si no llega audio
- captura de métricas
- subida a transcripción backend
- uso común desde Core, WorkSession y Rescate

Capacidades fase 2:

- VAD real
- auto-stop tras fin de habla
- buffer previo
- streaming parcial opcional
- cancelación limpia
- telemetría persistida

Capacidades fase 3:

- foreground service de comando activo
- notificación persistente de modo operativo
- integración con tile/shortcut/widget

Capacidades fase 4:

- wake word local con palabra `Atenea`
- ejecución local mínima antes de abrir sesión completa
- evaluación de `VoiceInteractionService` si se quiere aspirar a modo asistente Android

La fase 4 queda explícitamente fuera del primer bloque nativo.

## Realidad Android para background y voz

Android permite servicios foreground de tipo `microphone`, pero impone restricciones relevantes:

- el servicio debe declarar el tipo `microphone`
- requiere permiso runtime `RECORD_AUDIO`
- el acceso a micrófono está sujeto a restricciones while-in-use
- no se debe asumir que un servicio puede arrancar micrófono desde background arbitrario
- para casos de asistente real puede evaluarse `VoiceInteractionService`, pero requiere una integración y configuración más profunda

Por tanto, el camino razonable es:

1. voz perfecta con app visible
2. command mode con actividad visible
3. foreground service iniciado por acción explícita del usuario
4. wake word local o modo asistente como bloque independiente

## Contratos backend a reutilizar

Core:

- `POST /api/core/commands`
- `POST /api/core/voice/commands`
- `POST /api/core/commands/{commandId}/confirm`
- `GET /api/core/commands`
- `GET /api/core/commands/{commandId}/events`
- `GET /api/core/commands/{commandId}/events/stream`
- `GET /api/core/commands/{commandId}/speech`

Mobile/auth:

- `POST /api/mobile/auth/login`
- `POST /api/mobile/auth/refresh`
- `POST /api/mobile/auth/logout`
- `GET /api/mobile/auth/me`

Mobile reads:

- `GET /api/mobile/inbox`
- `GET /api/mobile/inbox/stream`
- `GET /api/mobile/projects/overview`
- `GET /api/mobile/sessions/{sessionId}/summary`
- `GET /api/mobile/sessions/{sessionId}/events`
- `GET /api/mobile/sessions/{sessionId}/events/stream`
- `GET /api/mobile/sessions/{sessionId}/conversation`
- `GET /api/mobile/billing/queue`
- `GET /api/mobile/billing/queue/summary`

Operations:

- `GET /api/mobile/operations/hosts`
- `GET /api/mobile/operations/hosts/{hostId}/status`
- `GET /api/mobile/operations/incidents`

Las acciones destructivas o sensibles de operations deben seguir pasando por Core para conservar confirmación, trazabilidad y mensajes explicativos.

## Seguridad

La app nativa debe asumir que puede ejecutar acciones sensibles.

Requisitos:

- tokens en almacenamiento cifrado
- refresh controlado
- bloqueo biométrico opcional al abrir app o antes de acciones críticas
- confirmación explícita para recoveries destructivos
- registro local de últimas acciones enviadas
- visibilidad de si una acción está pendiente, ejecutada, fallida o confirmada
- no guardar secretos SSH ni claves de servidores en el móvil
- todo acceso a servidores gestionados debe pasar por backend Atenea

## Operaciones

El dominio `operations` es una razón principal para la migración.

La app nativa debe hacer muy bien:

- comprobar servidor dedicado
- comprobar Apache
- comprobar webs externas
- lanzar recovery confirmado
- mostrar pasos ejecutados
- mostrar métricas
- mostrar verificación posterior
- mostrar incidencias abiertas
- cerrar incidencias resueltas
- avisar por push si una web va lenta o cae

La UX debe responder siempre:

- qué se ha hecho
- dónde se ha hecho
- con qué usuario/host
- qué comandos o scripts controlados se ejecutaron
- qué resultado devolvieron
- cómo se verificó que quedó bien
- qué queda pendiente

## Fases de migración

### Fase 0. Preparación documental

Objetivo:

- fijar esta decisión
- enlazarla desde documentación principal
- evitar que la app Expo siga considerándose destino final

Resultado esperado:

- este documento existe y queda referenciado
- `mobile/README.md` explica que Expo es baseline transitorio

### Fase 1. Proyecto Android nativo mínimo

Objetivo:

- crear el esqueleto Android nativo
- no tocar todavía el backend

Entregables:

- proyecto Gradle/Kotlin
- Jetpack Compose
- navegación base
- configuración de entornos
- pantalla de login
- almacenamiento seguro de tokens
- cliente API autenticado
- pantalla Core mínima con texto

Criterio de salida:

- se puede iniciar sesión y enviar un comando de texto a Core desde Android nativo

### Fase 2. Core Console nativa

Objetivo:

- sustituir la experiencia Core principal de Expo

Entregables:

- scopes global/proyecto/sesión
- historial de comandos
- confirmaciones
- aclaraciones
- respuesta estructurada
- eventos/polling
- acciones rápidas de operations

Criterio de salida:

- el operador puede usar Core nativo para operaciones habituales sin abrir Expo

### Fase 3. Voice Engine push-to-talk

Objetivo:

- voz fiable desde segundo operativo real

Entregables:

- módulo `:voice-engine`
- captura nativa
- readiness gate
- watchdog
- telemetría visible
- envío a `/api/core/voice/commands` o transcripción separada según flujo
- uso en Core, WorkSession y Rescate

Criterio de salida:

- la app no dice "escuchando" hasta que el motor esté listo
- no rechaza audio por un medidor visual congelado
- cada intento deja diagnóstico útil

### Fase 4. Operations nativo

Objetivo:

- operar servidores desde Android de forma cómoda y segura

Entregables:

- vista de hosts
- estado de Apache
- estado del host
- checks web
- incidencias
- recovery confirmado vía Core
- historial de runs

Criterio de salida:

- se puede diagnosticar y recuperar Apache del dedicado desde la app nativa

### Fase 5. WorkSession y Rescate

Objetivo:

- cubrir el trabajo de desarrollo móvil real

Entregables:

- conversación de WorkSession
- eventos de sesión
- runs
- publish/sync/close vía Core
- rescue session
- continuidad de contexto

Criterio de salida:

- se puede continuar una worksession desde móvil nativo y usar rescate sin Expo

### Fase 6. Notificaciones y acciones

Objetivo:

- hacer la app proactiva

Entregables:

- push nativo
- routing por payload
- acciones desde notificación cuando sea seguro
- alertas críticas de operations
- inbox operativo

Criterio de salida:

- una incidencia crítica puede llevar directamente a diagnóstico o recovery confirmado

### Fase 7. Foreground command mode

Objetivo:

- permitir operación por voz con servicio activo iniciado por el usuario

Entregables:

- foreground service tipo micrófono
- notificación persistente
- modo comando
- límites de batería
- cierre explícito

Criterio de salida:

- el operador puede activar un modo de escucha controlado sin depender de que la pantalla esté siempre en primer plano

### Fase 8. Wake word / asistente Android

Objetivo:

- evaluar invocación por palabra `Atenea`

Entregables:

- análisis técnico real sobre dispositivo
- prototipo local
- decisión entre foreground service, hotword local o `VoiceInteractionService`

Criterio de salida:

- decisión documentada y prototipo validado o descartado

## Orden recomendado de implementación

El orden recomendado es:

1. crear carpeta/proyecto Android nativo
2. login y cliente API
3. Core texto
4. Core confirmaciones/aclaraciones
5. voice-engine push-to-talk
6. operations
7. WorkSession conversación
8. rescate
9. notificaciones
10. foreground command mode
11. wake word

No debe empezarse por wake word. Depende de que el motor de voz, seguridad, Core y operations estén sólidos.

## Estructura de repo propuesta

Opción preferida:

```text
android/
  settings.gradle.kts
  build.gradle.kts
  app/
  api/
  secure/
  voice-engine/
  core-console/
  operations/
  worksession/
  rescue/
  notifications/
  local-store/
```

La carpeta `mobile/` queda para la app Expo existente.

Mientras ambas convivan:

- `mobile/` significa app Expo/React Native actual
- `android/` significa app Android nativa objetivo

## Gestión de coexistencia

Durante la migración:

- no se debe borrar `mobile/`
- no se deben romper scripts existentes de Expo
- los cambios backend deben mantener compatibilidad con ambas apps si es razonable
- cualquier nuevo endpoint debe documentar si lo consume Expo, Android nativo o ambos
- la app nativa puede avanzar con pantallas incompletas si Core y operations funcionan

Expo puede congelarse cuando:

- Android nativo tenga login
- Core texto
- voz push-to-talk
- operations/recovery
- WorkSession conversación
- Rescate

Expo puede retirarse cuando:

- el operador use Android nativo como app diaria durante un periodo real
- no queden acciones críticas exclusivas de Expo

## Definición de "alto nivel"

Para esta app, "alto nivel" significa:

- arranca rápido
- responde con claridad
- muestra estado real
- falla con diagnóstico
- no pierde acciones
- no oculta riesgos
- permite operar en 30 segundos desde una alerta
- permite dictar y ejecutar sin pelear con el micrófono
- separa acciones destructivas de acciones informativas
- conserva auditoría
- reduce carga mental

No significa:

- muchas tarjetas
- mucha decoración
- dashboards densos sin acción
- funciones genéricas
- multiplataforma por principio

## Criterios globales de éxito

La migración se considera correcta cuando:

- el operador puede usar Android nativo como herramienta principal diaria
- Core es más rápido y claro que en Expo
- la voz funciona de forma predecible desde el primer estado `Listening`
- un problema de Apache puede diagnosticarse y recuperarse desde móvil
- la app muestra qué hizo Atenea y cómo verificó el resultado
- las acciones sensibles requieren confirmación
- si la red falla, el operador entiende qué quedó pendiente
- el backend sigue siendo la fuente de verdad
- la app no duplica workflows del backend

## Registro de decisiones

### 2026-05-14

Se decide orientar Atenea Mobile hacia Android nativo.

Motivo:

- el operador principal usará Android como plataforma personal
- la app será herramienta crítica de trabajo y vida diaria
- el canal de voz y background requieren control nativo
- Expo queda como baseline funcional, no como destino final

Decisión complementaria:

- la fase wake word / pantalla apagada queda fuera del primer bloque
- primero se implementa Core, voz push-to-talk, operations, WorkSession y Rescate

### 2026-05-14. Inicio fase 1

Se crea el primer esqueleto Android nativo en `android/`.

Incluye:

- módulos `:app`, `:api`, `:secure` y `:core-console`
- Kotlin Android
- Jetpack Compose
- cliente HTTP mínimo sin lógica de dominio duplicada
- almacenamiento cifrado de sesión
- login contra `/api/mobile/auth/login`
- comando de texto contra `/api/core/commands`
- lectura de historial desde `/api/core/commands`
- build reproducible con `scripts/android-build.sh` y `docker/android-builder.Dockerfile`

Verificación:

- `scripts/android-build.sh` compila `:app:assembleDebug` correctamente en Docker
- el APK debug queda en `android/app/build/outputs/apk/debug/app-debug.apk`

Queda pendiente:

- completar confirmaciones/aclaraciones de Core
- portar el motor de voz nativo

## Próximo paso inmediato

El siguiente paso después del esqueleto inicial es instalar el APK debug en un dispositivo/emulador Android y validar login + comando Core contra producción.

La primera prueba útil sigue siendo:

1. abrir app nativa
2. iniciar sesión
3. escribir un comando en Core
4. recibir respuesta
5. ver historial básico

Sólo después tiene sentido portar el motor de voz nativo.
