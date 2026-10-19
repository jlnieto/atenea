package com.atenea.delivery;

import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.service.mobile.MobileNotificationInboxService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Outbox only. No FCM/network IO inside the transaction that records an operation. */
@Service
public class ChangeProgressNotifications {
    private final JdbcTemplate jdbc;
    private final OperatorRepository operators;
    private final OperatorPushDeviceRepository devices;
    private final MobileNotificationInboxService inbox;
    public ChangeProgressNotifications(JdbcTemplate jdbc, OperatorRepository operators,
            OperatorPushDeviceRepository devices, MobileNotificationInboxService inbox) {
        this.jdbc=jdbc; this.operators=operators; this.devices=devices; this.inbox=inbox;
    }
    record Copy(String title,String body) { }
    static Copy copy(DeliveryOperation op,String outcome) {
        if (List.of("BLOCKED","FAILED","ROLLED_BACK","ROLLBACK_FAILED","QUARANTINED").contains(outcome))
            return new Copy("Tu cambio necesita atención","Un paso se ha detenido. Abre el cambio para consultar el motivo y la siguiente acción; no repitas la solicitud.");
        if ("INTEGRATION_READY".equals(outcome) && "PUBLISH_PR".equals(op.kind()))
            return new Copy("Cambio listo para integrar","Las comprobaciones han terminado correctamente. Abre el cambio y pulsa Integrar cambio. Todavía no se ha publicado.");
        if ("READY".equals(outcome)) return new Copy("Preparación terminada",
                "La preparación ha terminado. Abre el cambio para confirmar el siguiente paso; todavía no se ha publicado.");
        if (!"SUCCEEDED".equals(outcome)) return null;
        return switch(op.kind()) {
            case "INTEGRATE" -> new Copy("Cambio integrado","El código está integrado. Abre el cambio para consultar las publicaciones pendientes; integrar no despliega.");
            case "RELEASE" -> new Copy("Publicación terminada","La publicación de "+switch(op.target()) {
                case APP_PROD -> "Backend PROD"; case ANDROID_STABLE -> "Android estable"; case AX42_PLATFORM -> "Worker AX42";
            }+" ha terminado. Abre el cambio para ver el resultado y continuar.");
            case "ANDROID_VERSION" -> new Copy("Versión Android integrada","La versión está integrada. Abre el cambio para continuar con la construcción y publicación de la APK.");
            case "FINALIZE" -> new Copy("Cambio completado","El cambio ha finalizado. La conversación, el código y los recibos se conservan en su historial.");
            default -> null; // PR creation is not proof that integration gates passed.
        };
    }

    @Transactional
    public void record(DeliveryOperation op,String outcome) {
        var text=copy(op,outcome);
        if (text==null) return;
        var actor=operators.findById(op.operatorId()).filter(o->o.isActive()
                && o.getCodexOperationsRole()==com.atenea.persistence.auth.CodexOperationsRole.PLATFORM_ADMINISTRATOR).orElse(null);
        if(actor==null) return;
        UUID event=UUID.nameUUIDFromBytes(("change-progress:"+op.id()+":"+outcome).getBytes(StandardCharsets.UTF_8));
        int inserted=jdbc.update("INSERT INTO mobile_change_progress_notice(id,operation_id,outcome) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                event,op.id(),outcome);
        if(inserted==0) return;
        inbox.record(actor,event.toString(),"CHANGE_PROGRESS",text.title(),text.body(),"CONVERSATION",op.sessionId());
        for(var device:devices.findByOperatorIdOrderByUpdatedAtDesc(op.operatorId())) {
            if(!device.isActive() || !"android".equalsIgnoreCase(device.getPlatform())) continue;
            jdbc.update("INSERT INTO mobile_change_progress_delivery(id,notice_id,device_id,title,body) VALUES(?,?,?,?,?)",
                    UUID.randomUUID(),event,device.getId(),text.title(),text.body());
        }
    }
}
