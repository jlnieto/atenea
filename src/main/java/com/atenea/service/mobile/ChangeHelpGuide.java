package com.atenea.service.mobile;

import com.atenea.api.mobile.ChangeHelpResponse;
import com.atenea.api.mobile.ChangeHelpResponse.Navigation;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** State decides the next destination; neither the question nor an LLM grants actions. */
public final class ChangeHelpGuide {
    private ChangeHelpGuide() { }
    public static ChangeHelpResponse explain(ChangeHelpContext c, String question, boolean simple) {
        String stage, answer, shortAnswer, label, reason, destination = "CHANGE";
        String normalized = Normalizer.normalize(question == null ? "" : question, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        var readiness=c.deliveryReadiness();
        boolean apkQuestion=normalized.matches("(?s).*(apk|android|version|menu|actualiza|publicaciones).*" );
        if (apkQuestion && readiness!=null && readiness.android()!=null) {
            var apk=readiness.android();
            stage="ANDROID_CHANNEL";
            answer="La APK realmente publicada es "+apk.versionName()+" / "+apk.versionCode()+", fuente "+apk.sourceCommit().substring(0,12)+". "
                    + switch (readiness.androidStatus()) {
                        case "INCLUDED" -> "Atenea ha comprobado que contiene este cambio integrado. ";
                        case "NOT_INCLUDED" -> "Esa APK todavía no contiene este cambio; desplegar el backend no publica Android. ";
                        default -> "Esta consulta no confirma que esa APK contenga el cambio de esta conversación. ";
                    };
            boolean update=c.reportedInstalledVersionCode()!=null && c.reportedInstalledVersionCode()<apk.versionCode();
            if (update) answer+="Este móvil informa una versión anterior. Actualiza Atenea desde el botón de abajo. ";
            else if (c.reportedInstalledVersionCode()!=null && c.reportedInstalledVersionCode()==apk.versionCode()) answer+="La versión informada por este móvil coincide con el canal. ";
            else answer+="La versión instalada no se ha confirmado en esta petición. Puedes comprobarla en Actualizaciones. ";
            answer+="Puedes consultar publicaciones e historial incluso con la conversación cerrada o sin validar una pregunta de diagnóstico. No necesitas crear otro cambio.";
            shortAnswer=answer;
            destination=update ? "APP_UPDATES" : "DELIVERY";
            label=update ? "Actualizar Atenea" : "Ver publicaciones";
            reason="Abre una pantalla disponible; no publica ni envía un prompt.";
        } else if ("COMPLETED".equals(c.changeStatus()) && "CLOSED".equals(c.sessionStatus())) {
            stage="COMPLETED"; answer="Este cambio está completado y su conversación se conserva como historia. Una petición nueva debe ir en un nuevo cambio.";
            shortAnswer="Este trabajo ya está terminado. Puedes consultar su historia.";
            destination="CHAT"; label="Ver conversación"; reason="Consultar no reabre ni recrea el cambio.";
        } else if ("CLOSED".equals(c.sessionStatus())) {
            stage="HISTORY"; answer="Esta sesión está cerrada. Su conversación y resultados se conservan como historia. El estado del cambio aparece en los hechos guardados; esta consulta no lo da por completado ni reabre la sesión.";
            shortAnswer="Esta conversación está cerrada y se puede consultar como historia.";
            destination="CHAT"; label="Ver conversación"; reason="Consultar no reabre ni ejecuta tareas.";
        } else if (c.requiredChecks() == 0) {
            stage="WORKFLOW_NOT_CONFIRMED"; answer="Puedo mostrar el estado guardado de este cambio, pero no hay un catálogo de validación y entrega confirmado para este proyecto en esta ayuda. No voy a aplicar el flujo de Atenea a otro repositorio ni inventar acciones. Continúa la conversación de este mismo cambio para identificar la capacidad que falta.";
            shortAnswer="Falta confirmar qué acciones están soportadas para este proyecto.";
            destination="CHAT"; label="Volver a la conversación"; reason="No se inventa un catálogo ni se lanza otra tarea.";
        } else if (c.runInProgress()) {
            stage="RUNNING"; answer="Codex todavía está trabajando. No envíes otra vez la misma petición ni reintentes la tarea. Puedes consultar su progreso y volver a preguntar cuando termine.";
            shortAnswer="La tarea sigue en marcha. Espera; no hace falta repetirla.";
            destination="ACTIVITY"; label="Ver progreso"; reason="Abre el detalle de la ejecución actual.";
        } else if (c.validationInProgress()) {
            stage="VALIDATING"; answer="Las pruebas ya están en curso. Consultar su resultado no inicia ni repite pruebas. Cuando terminen, Atenea mostrará qué pasó.";
            shortAnswer="Atenea está comprobando el cambio. Espera al resultado.";
            label="Ver validación"; reason="Consulta la operación existente; no solicites otra.";
        } else if (c.sourceUpdateInProgress()) {
            stage="SOURCE_UPDATE"; answer="Hay una actualización de la base o una resolución de conflictos pendiente. Hay que seguir su misma operación antes de validar o integrar. No se debe crear otra PR o volver a enviar el trabajo.";
            shortAnswer="Se está preparando la revisión del mismo cambio. Consulta esa operación.";
            label="Ver actualización de base"; reason="Las opciones de recuperación conservan la operación existente.";
        } else if (!"READY".equals(c.workspaceState()) || "BLOCKED".equals(c.sourceState())
                || "STALE".equals(c.sourceState()) || "BLOCKED".equals(c.remoteCloseState())) {
            stage="WORKSPACE_ATTENTION"; answer="Atenea no confirma ahora un workspace listo y coherente para continuar. El trabajo sigue vinculado al mismo cambio. Abre sus acciones para ver el motivo y las comprobaciones disponibles; no cambies identidades ni crees otra sesión.";
            shortAnswer="Hay que comprobar el espacio de trabajo del mismo cambio antes de continuar.";
            label="Ver estado del cambio"; reason="Sólo las acciones autorizadas podrán comprobar o recuperar su estado.";
        } else if ("FAILED".equals(c.latestRunStatus()) || "CANCELLED".equals(c.latestRunStatus())) {
            stage="TASK_ATTENTION"; answer="La última tarea no terminó correctamente. Eso no significa que se haya perdido el cambio. Abre el detalle para consultar el error y si permite recuperar o reintentar esa misma tarea. No dupliques el prompt.";
            shortAnswer="La tarea necesita atención. Revisa su error antes de hacer otro intento.";
            destination="ACTIVITY"; label="Ver tarea y recuperación"; reason="El detalle decide si existe un reintento autorizado.";
        } else if (c.failedCheck() != null) {
            boolean manifest=c.validationFailure()!=null && c.validationFailure().startsWith("[UNSUPPORTED_DEPENDENCY_MANIFEST]");
            stage=manifest?"VALIDATION_ENVIRONMENT_ATTENTION":"VALIDATION_ATTENTION";
            answer=manifest
                    ? "Android no pudo empezar: su configuración de construcción no coincide con la autorizada por el validador. No demuestra un fallo de tu funcionalidad. Atenea está detenida; esperar o repetir Validar cambio no lo resolverá. Abre Recuperar validación: si reconoce una reparación exacta, podrás prepararla y confirmar su aplicación. Si no, podrás encargar la investigación desde este mismo cambio. Tu código y conversación se conservan."
                    : "Una comprobación de esta revisión necesita atención. Atenea está detenida, no trabajando. La causa guardada aparece debajo. Puedes investigar y encargar la corrección desde el mismo cambio; no dupliques el prompt funcional ni crees otra sesión. La investigación no ejecuta pruebas ni publica por sí sola.";
            shortAnswer=manifest?"No ha fallado tu funcionalidad: el validador no reconoce la configuración Android. Abre Recuperar validación; no repitas las pruebas."
                    :"Una comprobación necesita atención. Abre Recuperar validación para investigar el bloqueo sin perder tu cambio.";
            destination="VALIDATION_RECOVERY";label="Recuperar validación";
            reason="Consultar no modifica nada. Preparar, aplicar o encargar una investigación requieren una acción explícita.";
        } else if ("CURRENT".equals(c.validationState()) && c.passedChecks()!=c.requiredChecks()) {
            stage="VALIDATION_EVIDENCE_MISSING"; answer="El estado dice validado, pero esta consulta no encuentra todas las comprobaciones vigentes de la misma revisión. No se puede afirmar que las pruebas hayan pasado. Consulta el resultado guardado antes de integrar o publicar.";
            shortAnswer="Falta confirmar la evidencia de las pruebas de esta revisión.";
            label="Consultar evidencia"; reason="No se promueve el cambio por un estado sin sus resultados.";
        } else if ("BLOCKED".equals(c.validationState())) {
            stage="VALIDATION_UNKNOWN_BLOCKER"; answer="Atenea marca la validación como bloqueada, pero no hay un resultado vigente que explique exactamente la causa en esta consulta. No voy a inventar el motivo ni afirmar que los tests fallaron. Consulta el resultado guardado y el estado del mismo cambio.";
            shortAnswer="La validación está bloqueada, pero falta evidencia de la causa. Consulta su resultado.";
            label="Consultar evidencia"; reason="Sin evidencia no se inventa un fallo ni se recomienda un reintento automático.";
        } else if (!"CURRENT".equals(c.validationState()) && "DIRTY".equals(c.sourceState())) {
            stage="VALIDATION_REQUIRED"; answer="Hay cambios guardados, pero todavía falta validar esta revisión. Terminar la tarea de Codex no significa que hayan pasado las pruebas. En Cambio, pulsa Validar cambio una vez y espera el resultado.";
            if (c.agentReportedGitInstructions()) answer += " La última respuesta de Codex menciona las referencias Git del trabajo manual. Ese mensaje no es un resultado de pruebas fallidas: la validación gestionada de Atenea tiene su propia autoridad sobre este cambio.";
            shortAnswer="El cambio está guardado. Ahora toca comprobarlo: abre Cambio y pulsa Validar cambio una vez.";
            label="Ir a Validar cambio"; reason="Abre las acciones; esta consulta no inicia pruebas.";
        } else if ("CURRENT".equals(c.validationState()) && (!c.deliveryEnabled()
                || !"PLATFORM_ADMINISTRATOR".equals(c.operatorRole()))) {
            stage="DELIVERY_NOT_AVAILABLE";
            answer=!c.deliveryEnabled()
                    ? "El código consta validado, pero las operaciones de entrega no están habilitadas en App. Esta consulta no puede habilitarlas ni publicar por otro canal. Consulta el estado del mismo cambio."
                    : "El código consta validado, pero tu rol actual no permite las operaciones administrativas de entrega. Consultar esta ayuda no concede esos permisos. Debe resolverse por el mecanismo de autorización previsto, sin recrear el ticket.";
            shortAnswer="Las pruebas están registradas; falta habilitación o autorización para la entrega.";
            label="Ver estado de entrega"; reason="No se inventan permisos ni se ofrece publicar por terminal.";
        } else if ("RELEASE".equals(c.deliveryKind()) && "READY".equals(c.deliveryState())) {
            if (c.deliveryExpiresAt() == null || !c.deliveryPlanVerified()) {
                stage="DELIVERY_ATTENTION";
                answer="La operación dice preparada, pero no hay evidencia suficiente de un plan válido para confirmar. Consulta el estado en Cambio. No se puede publicar con esta información ni se debe inventar que el plan está vigente.";
                shortAnswer="Falta confirmar la evidencia del plan. En Cambio, utiliza Consultar estado.";
                label="Ir a Consultar estado"; reason="La evidencia incompleta no habilita una confirmación.";
            } else if (c.deliveryExpiresAt() <= c.observedAt().getEpochSecond()) {
                stage="DELIVERY_ATTENTION";
                answer="La preparación de " + c.deliveryTargetLabel() + " ha caducado sin publicar. No hay un despliegue trabajando. Atenea debe registrar el cierre de ese plan; después podrás preparar uno nuevo desde Cambio. No vuelvas a validar ni integrar la PR.";
                shortAnswer="El plan ha caducado; no se ha publicado. Consulta Cambio para preparar de nuevo cuando cierre ese plan.";
                label="Ver preparación caducada"; reason="Un plan caducado no se puede confirmar ni renovar silenciosamente.";
            } else {
                stage="PUBLICATION_CONFIRMATION";
                answer="La preparación de " + c.deliveryTargetLabel() + " terminó. Atenea no está trabajando ni va a publicar sola: falta tu confirmación. En Cambio, pulsa Confirmar " + c.deliveryTargetLabel() + " y utiliza tu autenticador. La autorización será sólo para ese plan. Puedes hacerlo desde el móvil; no necesitas SSH ni otra sesión.";
                shortAnswer="La preparación está lista. Ahora te toca: Cambio → Confirmar " + c.deliveryTargetLabel() + ".";
                label="Ir a Confirmar " + c.deliveryTargetLabel(); reason="Abre la confirmación; esta ayuda no publica ni concede permisos.";
            }
        } else if (c.deliveryState() != null && !List.of("SUCCEEDED","FAILED","BLOCKED","ROLLED_BACK","ROLLBACK_FAILED").contains(c.deliveryState())) {
            stage="DELIVERY_PENDING";
            if ("WAITING_CI".equals(c.deliveryState())) {
                answer="La misma operación está esperando las comprobaciones de GitHub. Atenea continuará al recibir el resultado. Puedes salir de la app; no tienes que repetir la acción.";
                shortAnswer="GitHub está comprobando el cambio. Espera; puedes salir de la app.";
            } else if ("PLANNING".equals(c.deliveryState()) || "PREPARING".equals(c.deliveryState())) {
                answer="Atenea está preparando " + c.deliveryTargetLabel() + ". Aún no está publicando. Puedes salir de la app: la misma operación conserva su resultado. Cuando esté lista faltará tu confirmación.";
                shortAnswer="Preparando " + c.deliveryTargetLabel() + ". Espera; después habrá que confirmar.";
            } else {
                answer="La misma operación de entrega está en curso. Puedes salir de la app; Atenea conserva y consulta su resultado. No repitas la acción ni envíes otro prompt.";
                shortAnswer="La operación está trabajando. Espera; no tienes que mantener abierta la app.";
            }
            label="Ver progreso de entrega"; reason="Consulta la misma operación; no vuelve a solicitarla.";
        } else if ("RELEASE".equals(c.deliveryKind()) && !"APP_PROD".equals(c.deliveryTarget())
                && List.of("FAILED","BLOCKED","ROLLED_BACK","ROLLBACK_FAILED").contains(c.deliveryState())) {
            stage="DELIVERY_ATTENTION";
            answer="La última publicación de " + c.deliveryTargetLabel() + " necesita atención. Que Backend PROD esté sano no confirma la entrega de otro destino. Consulta el resultado de esa operación; esta ayuda no hace un segundo intento ni da el ticket por terminado.";
            shortAnswer=c.deliveryTargetLabel() + " necesita atención. Consulta esa publicación antes de dar el cambio por terminado.";
            label="Ver resultado de " + c.deliveryTargetLabel(); reason="Backend, Android y Worker tienen resultados independientes.";
            if ("PLAN_EXPIRED".equals(c.deliveryError())) {
                answer="La preparación de " + c.deliveryTargetLabel() + " caducó sin publicar. Puedes preparar de nuevo desde Cambio; Atenea volverá a comprobar la versión y sus permisos. Que el backend esté desplegado no publica este destino.";
                shortAnswer="El plan de " + c.deliveryTargetLabel() + " caducó. Prepara de nuevo y comprueba el resultado.";
            }
        } else if (readiness!=null && "CONFIRMED".equals(readiness.requirementsStatus()) && readiness.ready()) {
            stage="COMPLETION_READY";
            answer="Atenea ha confirmado todas las publicaciones que requiere el diff de este cambio. Ya puedes comprobar el resultado y utilizar Finalizar cambio. Si se ha publicado Android, instalar esa actualización en el móvil es una acción separada.";
            shortAnswer="Las entregas requeridas están confirmadas. Comprueba el resultado y finaliza el cambio.";
            destination="DELIVERY"; label="Ir a Finalizar cambio"; reason="El cierre vuelve a comprobar las entregas y conserva el historial.";
        } else if (readiness!=null && "CONFIRMED".equals(readiness.requirementsStatus())
                && readiness.requiredTargets().contains(com.atenea.delivery.DeliveryTarget.ANDROID_STABLE)
                && !"INCLUDED".equals(readiness.androidStatus())) {
            stage="ANDROID_PUBLICATION_REQUIRED";
            answer="El diff de este cambio modifica Android, pero no hay una APK del canal que Atenea haya confirmado que lo contenga. Un backend desplegado no basta. Abre Publicaciones y utiliza Preparar Android estable; después confirma esa misma operación. La versión declarada en el código debe superar la del canal; Atenea lo comprueba antes de publicar.";
            shortAnswer="Falta publicar Android. Abre Publicaciones y prepara Android estable; no crees otro cambio.";
            destination="DELIVERY"; label="Ver publicación Android"; reason="Preparar y confirmar son acciones distintas; esta ayuda no las ejecuta.";
        } else if (readiness!=null && "UNAVAILABLE".equals(readiness.requirementsStatus()) && "MERGED".equals(c.pullRequestState())) {
            stage="DELIVERY_ATTENTION"; answer="Falta confirmar el diff o sus publicaciones. No voy a afirmar que el ticket esté entregado sólo porque PROD esté sano. Abre Publicaciones y consulta el estado.";
            shortAnswer="Falta confirmar las publicaciones requeridas."; destination="DELIVERY"; label="Consultar publicaciones"; reason="No inicia ni repite una publicación.";
        } else if (readiness==null && "DEPLOYED".equals(c.deploymentStatus()) && !"ROLLBACK_FAILED".equals(c.deliveryState())) {
            stage="COMPLETION_READY";
            answer="Atenea confirma que Backend PROD está sano y contiene este cambio. No hay un despliegue pendiente. Comprueba el resultado funcional; si el cambio incluye Android, publicar e instalar su APK es un paso separado en Otras publicaciones. Después utiliza Finalizar cambio. Finalizar no vuelve a desplegar y conserva la conversación.";
            shortAnswer="El backend ya contiene el cambio y está sano. Comprueba el resultado y, cuando esté aceptado, utiliza Finalizar cambio.";
            label="Ir a Finalizar cambio"; reason="El cierre vuelve a comprobar entrega, validación y ausencia de operaciones activas.";
        } else if (c.deliveryError() != null) {
            stage="DELIVERY_ATTENTION"; answer="La última operación de entrega guardó un bloqueo. Consulta el código y las opciones de recuperación del mismo cambio. Preguntar aquí no hace un nuevo intento ni publica nada.";
            shortAnswer="La entrega necesita atención. Abre sus opciones de recuperación.";
            label="Ver bloqueo de entrega"; reason="La acción normal volverá a comprobar estado y permisos.";
            if ("RELEASE".equals(c.deliveryKind()) && List.of("PLAN_EXPIRED","CANONICAL_MAIN_MOVED").contains(c.deliveryError())) {
                answer="La preparación anterior de " + c.deliveryTargetLabel() + " ya no se puede usar. No se ha publicado ni hay un despliegue trabajando. En Cambio, prepara de nuevo; Atenea volverá a comprobar la versión aprobada. La validación y la integración del ticket se conservan.";
                shortAnswer="La preparación anterior ya no sirve. Ahora te toca: preparar " + c.deliveryTargetLabel() + " de nuevo.";
                label="Ir a Preparar " + c.deliveryTargetLabel(); reason="Es una preparación nueva explícita; no repite pruebas, PR ni despliegue.";
            }
        } else if (List.of("UNHEALTHY","UNAVAILABLE").contains(c.deploymentStatus())) {
            stage="DELIVERY_ATTENTION"; answer="Atenea no ha podido confirmar un despliegue sano que contenga este cambio. Consultar estado vuelve a leer el despliegue; no publica ni reintenta nada. No se puede finalizar con una entrega sin confirmar.";
            shortAnswer="Falta confirmar el estado de PROD. En Cambio, utiliza Consultar estado.";
            label="Ir a Consultar estado"; reason="Sólo consulta; una observación no confirmada no habilita una publicación.";
        } else if ("MERGED".equals(c.pullRequestState()) || "CURRENT".equals(c.integrationState())) {
            stage="PUBLICATION"; answer="El cambio consta integrado. Integrar no equivale a desplegar el backend o instalar una APK. Revisa los recibos y prepara la publicación que corresponda. Sólo se puede finalizar cuando Atenea confirme un despliegue sano que contenga este cambio.";
            shortAnswer="El código está integrado. Revisa la publicación antes de dar el cambio por terminado.";
            label="Ver publicaciones y recibos"; reason="Preparar, confirmar, publicar y finalizar son pasos distintos.";
        } else if (!"NOT_CREATED".equals(c.pullRequestState()) && !"UNKNOWN".equals(c.pullRequestState())) {
            stage="PULL_REQUEST"; answer="La PR ya existe según el estado guardado. Revisa sus comprobaciones y si tiene conflictos. No se crea otra PR. Atenea debe confirmar el head actual, CI y mergeability antes de ofrecer la integración.";
            shortAnswer="Ya tienes una PR. Consulta sus comprobaciones antes de integrar.";
            label="Ver estado de la PR"; reason="El estado de GitHub puede cambiar y se comprobará mediante la acción normal.";
        } else if ("CURRENT".equals(c.validationState())) {
            stage="READY_FOR_PR"; answer="Esta revisión consta validada. El siguiente paso es crear su PR desde las acciones del cambio. Después GitHub debe comprobar ese head antes de integrar. Crear la PR no despliega ni publica una APK.";
            shortAnswer="El cambio consta validado. Ahora puedes ir al paso Crear PR.";
            label="Ir al paso Crear PR"; reason="Las acciones comprueban nuevamente revisión, permisos y disponibilidad.";
        } else {
            stage="IMPLEMENTATION"; answer="Todavía no consta una revisión con cambios pendiente de validar. Puedes continuar la conversación del mismo cambio y pedir a Codex que implemente lo que falta. No necesitas otra sesión.";
            shortAnswer="Continúa este mismo cambio en su conversación.";
            destination="CHAT"; label="Volver a la conversación"; reason="No se envía un prompt automáticamente.";
        }
        if (normalized.contains("integrar") && normalized.contains("public")) {
            answer = "Validar comprueba el código; la PR permite revisarlo; integrar lo incorpora a main; publicar lo lleva al backend, al worker o a Android. Son pasos distintos. " + answer;
        }
        return new ChangeHelpResponse("change-help/v1", c.sessionId(), c.changeKey(), c.sourceRevision(),
                c.observedAt(), stage, simple ? shortAnswer : answer, shortAnswer, c.facts(),
                new Navigation(destination,label,reason), List.of("¿Qué tengo que hacer ahora?", "Explícamelo más fácil", "¿Qué falta para publicar?"), "RECORDED_STATE");
    }
}
