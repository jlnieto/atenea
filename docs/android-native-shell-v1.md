# Atenea Native Shell v1

Este documento describe la primera base de producto de la app Android nativa después de separar la app en una shell real y pantallas enfocadas.

## Objetivo

La app debe funcionar como una consola móvil profesional:

- abrir Atenea y entender el estado operativo en segundos
- escribir a Core sin navegar
- entrar en Operaciones con estado compacto y acciones claras
- mantener mantenimiento interno, como actualizaciones, fuera del flujo de trabajo diario
- crecer por módulos sin convertir cada pantalla en una mezcla de UI, estado y llamadas HTTP

## Estructura actual

Código principal:

- `android/app/src/main/java/com/atenea/android/MainActivity.kt`
  - composition root Android
  - crea `AteneaApiClient`
  - conecta `AteneaSessionStore`

- `android/core-console/src/main/java/com/atenea/android/coreconsole/CoreConsoleApp.kt`
  - decide si mostrar login o shell autenticada
  - no contiene navegación ni pantallas de dominio

- `android/core-console/src/main/java/com/atenea/android/coreconsole/AteneaShell.kt`
  - shell autenticada
  - top bar
  - drawer de navegación
  - comprobación silenciosa de actualización
  - routing entre pantallas

- `android/core-console/src/main/java/com/atenea/android/coreconsole/AteneaDesign.kt`
  - componentes visuales comunes
  - `AteneaPanel`
  - `StatusPill`
  - `MetricLine`
  - `ErrorPanel`
  - `OperationalLevel`

- `android/core-console/src/main/java/com/atenea/android/coreconsole/HomeScreen.kt`
  - primera pantalla diaria
  - resumen operativo compacto
  - input conversacional directo

- `android/core-console/src/main/java/com/atenea/android/coreconsole/CoreScreen.kt`
  - consola Core completa
  - scopes
  - input fijo abajo
  - respuesta, confirmaciones, aclaraciones e historial reciente

- `android/core-console/src/main/java/com/atenea/android/coreconsole/OperationsScreen.kt`
  - estado del dedicado
  - webs
  - incidencias
  - acciones operativas compactas
  - recuperación Apache vía Core con confirmación

- `android/core-console/src/main/java/com/atenea/android/coreconsole/SettingsScreen.kt`
  - versión instalada
  - actualización manual
  - información técnica de versión

- `android/core-console/src/main/java/com/atenea/android/coreconsole/DiagnosticsScreen.kt`
  - métricas vivas de memoria y proceso Android
  - último crash Java capturado
  - última razón de salida reportada por Android
  - generación y subida de informe técnico

- `android/core-console/src/main/java/com/atenea/android/coreconsole/CoreCommandUi.kt`
  - tarjeta común de comando Core
  - historial
  - resolución de aclaraciones
  - helpers de formato

- `android/core-console/src/main/java/com/atenea/android/coreconsole/ProjectsScreen.kt`
  - listado de proyectos desde `/api/mobile/projects/overview`
  - apertura/resolucion de WorkSession
  - entrada hacia Conversacion y Rescate

- `android/core-console/src/main/java/com/atenea/android/coreconsole/WorkSessionConversationScreen.kt`
  - conversacion nativa de WorkSession
  - envio de turnos a Codex
  - superficie visual basada en la app no nativa

- `android/core-console/src/main/java/com/atenea/android/coreconsole/RescueScreen.kt`
  - canal de rescate nativo
  - resolve automatico por proyecto
  - misma superficie conversacional que WorkSession

- `android/core-console/src/main/java/com/atenea/android/coreconsole/ConversationSurface.kt`
  - componente compartido para Conversacion y Rescate
  - estilo oscuro, compacto y monoespaciado tomado como baseline de la app no nativa

## Navegación

La shell usa un drawer modal abierto desde el botón de menú superior. No debe haber navegación inferior fija porque reduce el espacio útil de trabajo en una ventana móvil.

Destinos actuales:

- `Inicio`
- `Proyectos`
- `Estado`
- `Core`
- `Voz`
- `Operaciones`
- `Archivos`
- `Costes API`
- `Diagnostico`
- `Ajustes`

`Conversación` y `Rescate` no son destinos globales del drawer. Son superficies internas de un proyecto o WorkSession concreta y se abren desde `Proyectos`.

Cuando la app entra en una superficie conversacional de trabajo:

- la cabecera global desaparece;
- la pantalla ocupa todo el ancho disponible;
- la propia superficie muestra solo navegación contextual: volver, abrir Core y actualizar;
- el compositor inferior queda reducido a campo de texto y enviar;
- no aparecen botones globales de Voz, Proyectos u Operaciones dentro de la zona de escritura.

La app ya no muestra actualizaciones como tarjeta central. La shell comprueba silenciosamente al arrancar y sólo muestra una entrada pequeña hacia Ajustes si hay versión disponible.

La barra superior debe usar `AteneaTopChrome`, no una `Row` genérica dentro de `Scaffold`.

Problema corregido en `Premium Chrome v1`:

- el menú no puede ser `Text("☰")`; debe ser un control dibujado y alineado como parte del sistema visual
- el estado global no debe ser un gráfico ambiguo ni texto `OK`; debe ser un punto de color accionable
- la barra debe integrarse con el status bar Android mediante edge-to-edge
- la tipografía no debe depender de `Typography()` por defecto de Material

La barra superior debe mantenerse compacta:

- botón de menú a la izquierda
- título de la pantalla
- punto de estado operativo a la derecha
- indicador mínimo de actualización si procede

El indicador operativo de cabecera es accionable:

- `OK`: no hay puntos abiertos
- número rojo: hay incidencias, webs caídas o errores de consulta
- `REV`: hay datos incompletos o pendientes de revisión
- al pulsarlo se abre `Estado`

## Principios UX

El estándar de esta shell es:

- una pantalla debe poder leerse en 3 segundos
- el estado global debe ser explícito: `OK`, `Atención`, `Crítico`, `Sin datos`
- no se debe mostrar JSON crudo al operador
- los botones grandes se reservan para acciones primarias reales
- las acciones frecuentes pueden ser botones compactos
- las acciones críticas siguen pasando por Core y confirmación
- los textos deben explicar resultado y verificación, no decorar la pantalla
- las actualizaciones y versión viven en Ajustes
- diagnóstico técnico, memoria, crashes e informes viven en Diagnostico
- la paleta debe ser neutra; no usar colores Material por defecto como morado/lila
- el radio visual debe ser mínimo, orientado a herramienta profesional

## Operaciones

`OperationsScreen` aplica esta regla:

- estado global arriba
- resumen de servicios, webs e incidencias en una línea
- si hay problema crítico, la acción recomendada `Recuperar Apache` sube a acción principal
- las acciones secundarias quedan compactas:
  - `Actualizar`
  - `Diagnóstico`
  - `Apache`
- al pulsar cualquier acción se limpia el resultado anterior y se muestra una sección `En curso`
- la salida de scripts se lee desde `report`
- `stdoutSummary` sólo se muestra si no parece JSON
- la información se organiza con secciones y separadores, no con tarjetas decorativas

Significado de las acciones:

- `Actualizar`: consulta estructurada de Atenea sobre host, webs e incidencias. No ejecuta una conversación Core.
- `Diagnóstico`: comando Core general `revisa el dedicado`, pensado para una explicación operativa más narrativa.
- `Apache`: comando Core específico `comprueba apache en el dedicado`, enfocado sólo en servicio y procesos Apache.
- `Recuperar Apache`: acción sensible con confirmación Core antes de ejecutar recuperación.

## Core

`CoreScreen` queda como superficie conversacional pura:

- scopes arriba
- contenido/historial en el centro
- input fijo abajo
- confirmaciones y aclaraciones reutilizan `CommandCard`

## Inicio

`HomeScreen` es la superficie diaria:

- permite escribir a Atenea inmediatamente desde una superficie conversacional integrada
- evita botones secundarios grandes y estados desactivados que ocupen pantalla
- no dedica el área principal a estado operativo, porque eso vive en cabecera y en `Estado`

El objetivo no es sustituir las pantallas de dominio, sino evitar que el operador tenga que decidir primero dónde ir.

La pantalla de inicio debe sentirse como una consola privada de trabajo:

- poco espacio muerto en la primera vista
- jerarquía tipográfica precisa
- controles compactos
- entrada conversacional como elemento central
- acción de envío dentro del mismo bloque de composición

## Estado

`Estado` es la pantalla de detalle del indicador gráfico de cabecera.

Debe escalar de un dedicado a varios VPS:

- resume el estado global
- lista cada host gestionado
- muestra webs con problema
- muestra incidencias abiertas
- permite refrescar manualmente

La cabecera es el punto de entrada rápido. `Operaciones` queda como pantalla de acciones.

## Archivos

`Archivos` permite subir imágenes, PDF, hojas de cálculo u otros ficheros desde Android al VPS de Atenea.

Endpoint backend:

- `POST /api/mobile/uploads`
- protegido con la misma sesión móvil Bearer
- límite por defecto: 50 MB por fichero

Ruta estable dentro del backend:

- contenedor: `/workspace/repos/internal/atenea/operator-uploads`
- host VPS: `/srv/atenea/workspace/repos/internal/atenea/operator-uploads`

Archivos importantes:

- `latest.json`: metadatos del último fichero subido
- `uploads.jsonl`: histórico append-only de subidas
- `inbox/YYYY/MM/DD/...`: ficheros físicos subidos

Esto permite que desde Codex CLI el operador pueda decir "revisa la última imagen subida" o "revisa el último PDF subido" y el agente tenga una ubicación estable que consultar:

```text
/srv/atenea/workspace/repos/internal/atenea/operator-uploads/latest.json
```

La carpeta host debe mantenerse con grupo `atenea` y bit setgid para que el backend pueda escribir y Codex CLI pueda leer/administrar:

```bash
chown -R jose:atenea /srv/atenea/workspace/repos/internal/atenea/operator-uploads
chmod -R 2775 /srv/atenea/workspace/repos/internal/atenea/operator-uploads
```

## Diagnostico

`Diagnostico` es una pantalla propia dentro de `Sistema`. No debe mezclarse con `Ajustes`, porque su uso no es configurar la app sino observar el estado técnico real cuando hay cierres, lentitud o sospecha de recursos insuficientes en el móvil.

La pantalla debe mostrar:

- heap Java usado, reservado y máximo;
- heap nativo usado y reservado;
- memoria total y disponible del dispositivo;
- clase de memoria Android asignada a la app;
- hilos activos, procesadores, CPU de proceso y uptime;
- almacenamiento disponible en cache y datos de app;
- último crash capturado por el manejador Java;
- última salida de proceso reportada por `ApplicationExitInfo`;
- acción para subir un informe JSON completo a Atenea.

El informe se genera desde `AteneaDiagnostics` e incluye:

- versión de app;
- dispositivo;
- snapshot runtime;
- último crash;
- razones históricas de salida de proceso;
- eventos recientes de app, voz, WebRTC, conversación y diagnóstico.

Para depurar cierres de conversación, la pantalla de WorkSession registra al cargar:

- `sessionId`;
- número de turnos visibles;
- total de caracteres cargados.

Esto permite diferenciar entre:

- crash Java capturado;
- cierre nativo o kill del sistema reportado por Android;
- presión real de memoria;
- conversación excesivamente grande o render demasiado costoso.

## Pantalla Activa

`MainActivity` activa `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`. Mientras Atenea esté abierta en primer plano, Android no debe apagar la pantalla por timeout. Si más adelante se quiere controlar por pantalla, esta bandera debe moverse a un controlador de shell o a un `DisposableEffect` por destino.

## Calidad de código

Reglas para futuras iteraciones:

- no volver a concentrar pantallas nuevas en `CoreConsoleApp.kt`
- las llamadas HTTP se quedan en `:api`
- la persistencia segura se queda en `:secure`
- las pantallas deben trabajar con estado de presentación, no pintar JSON crudo
- las mutaciones sensibles deben mantener Core como puerta de entrada
- si una pieza de UI se repite dos veces, debe moverse a `AteneaDesign.kt` o un componente común específico

## Siguiente evolución

Después de esta shell, los siguientes pasos recomendados son:

1. mover `core-console` hacia módulos de dominio reales cuando crezca:
   - `:home`
   - `:operations`
   - `:worksession`
   - `:settings`
2. añadir ViewModels por pantalla para sobrevivir mejor a rotación y recreación
3. añadir `WorkSessions` como destino de navegación
4. evolucionar `ConversationSurface` hasta paridad total con la app no nativa
5. añadir notificaciones operativas y monitorización periódica
