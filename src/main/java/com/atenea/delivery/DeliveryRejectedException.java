package com.atenea.delivery;

public class DeliveryRejectedException extends RuntimeException {
    private final String code;
    public DeliveryRejectedException(String code) {
        super("Publicación no disponible: " + code);
        this.code = code;
    }
    public String code() { return code; }
}
