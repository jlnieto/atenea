package com.atenea.api.operations;

import com.atenea.persistence.operations.ManagedServiceType;

public record ManagedServiceResponse(
        Long id,
        Long hostId,
        String name,
        ManagedServiceType serviceType,
        String systemdUnit,
        String processPattern,
        boolean active
) {
}
