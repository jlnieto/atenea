package com.atenea.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionProtocolNegotiationTest {

    @Test
    void onlyTheExactVersionAndSingleFlightDeclarationSelectFamilySemantics() {
        assertTrue(SessionProtocolNegotiation.resolve("FAMILY_V1", true, "FAMILY_V1")
                .familyProtocol());
        assertFalse(SessionProtocolNegotiation.resolve(null, null, "FAMILY_V1")
                .familyProtocol());
        assertFalse(SessionProtocolNegotiation.resolve("FAMILY_V1", null, "FAMILY_V1")
                .familyProtocol());
        assertFalse(SessionProtocolNegotiation.resolve("FAMILY_V1", false, "FAMILY_V1")
                .familyProtocol());
        assertFalse(SessionProtocolNegotiation.resolve("FAMILY_V2", true, "FAMILY_V1")
                .familyProtocol());
        assertFalse(SessionProtocolNegotiation.resolve(" FAMILY_V1", true, "FAMILY_V1")
                .familyProtocol());
    }

    @Test
    void missingOrMalformedServerConfigurationFailsClosed() {
        assertThrows(OperatorAuthenticationException.class,
                () -> SessionProtocolNegotiation.resolve(null, null, null));
        assertThrows(OperatorAuthenticationException.class,
                () -> SessionProtocolNegotiation.resolve(null, null, ""));
        assertThrows(OperatorAuthenticationException.class,
                () -> SessionProtocolNegotiation.resolve(null, null, " FAMILY_V1"));
    }
}
