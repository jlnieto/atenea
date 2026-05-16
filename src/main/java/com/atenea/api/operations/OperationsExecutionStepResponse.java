package com.atenea.api.operations;

public record OperationsExecutionStepResponse(
        String name,
        String status,
        String detail
) {
}
