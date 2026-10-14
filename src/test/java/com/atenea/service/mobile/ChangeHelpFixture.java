package com.atenea.service.mobile;

import java.time.Instant;
import java.util.UUID;

public class ChangeHelpFixture {
    public String change="OPEN", session="OPEN", workspace="READY", source="DIRTY", validation="NOT_STARTED";
    public String pr="NOT_CREATED", integration="NOT_STARTED", release="NOT_STARTED", close="NOT_STARTED";
    public String runStatus="SUCCEEDED", failure, check, checkFailure, deliveryKind, deliveryState, deliveryError;
    public boolean activeRun, activeValidation, sourceUpdate, gitWarning=true;
    public String operatorRole="PLATFORM_ADMINISTRATOR";
    public boolean deliveryEnabled=true;
    public int passed, required=4;
    public long revision=1;
    public ChangeHelpContext build() {
        return new ChangeHelpContext(22L,1L,UUID.fromString("a4ff9100-2e8a-46df-911f-53874886701e"),revision,
                Instant.parse("2026-10-14T08:00:00Z"),change,session,workspace,source,validation,pr,integration,release,
                activeRun,activeValidation,sourceUpdate,close,113L,runStatus,failure,gitWarning,passed,required,check,
                checkFailure,deliveryKind,deliveryState,deliveryError,operatorRole,deliveryEnabled,null);
    }
}
