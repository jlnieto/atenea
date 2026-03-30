package com.atenea.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private final ObjectMapper objectMapper;
    private final OperatorAuthProperties properties;
    private final byte[] secretBytes;

    public JwtTokenService(ObjectMapper objectMapper, OperatorAuthProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("atenea.auth.jwt.secret must not be blank");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public IssuedToken issueAccessToken(AuthenticatedOperator operator) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getJwt().getAccessTokenTtl());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getJwt().getIssuer());
        claims.put("sub", operator.email());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("tokenType", "access");
        claims.put("operatorId", operator.operatorId());
        claims.put("displayName", operator.displayName());
        return new IssuedToken(sign(claims), expiresAt);
    }

    public AuthenticatedOperator parseAccessToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new OperatorAuthenticationException("Invalid access token format");
            }

            String signedContent = parts[0] + "." + parts[1];
            byte[] expectedSignature = signBytes(signedContent.getBytes(StandardCharsets.UTF_8));
            byte[] providedSignature = Base64.getUrlDecoder().decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expectedSignature, providedSignature)) {
                throw new OperatorAuthenticationException("Invalid access token signature");
            }

            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"access".equals(payload.path("tokenType").asText())) {
                throw new OperatorAuthenticationException("Invalid access token type");
            }
            if (!properties.getJwt().getIssuer().equals(payload.path("iss").asText())) {
                throw new OperatorAuthenticationException("Invalid access token issuer");
            }
            Instant expiresAt = Instant.ofEpochSecond(payload.path("exp").asLong());
            if (!expiresAt.isAfter(Instant.now())) {
                throw new OperatorAuthenticationException("Access token expired");
            }

            long operatorId = payload.path("operatorId").asLong(-1);
            if (operatorId <= 0) {
                throw new OperatorAuthenticationException("Access token missing operator id");
            }
            String email = payload.path("sub").asText();
            if (email == null || email.isBlank()) {
                throw new OperatorAuthenticationException("Access token missing subject");
            }

            return new AuthenticatedOperator(
                    operatorId,
                    email,
                    payload.path("displayName").asText("")
            );
        } catch (OperatorAuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OperatorAuthenticationException("Invalid access token");
        }
    }

    private String sign(Map<String, Object> claims) {
        try {
            String header = encodeJson(Map.of("alg", "HS256", "typ", "JWT"));
            String payload = encodeJson(claims);
            String signedContent = header + "." + payload;
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    signBytes(signedContent.getBytes(StandardCharsets.UTF_8)));
            return signedContent + "." + signature;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not issue access token", exception);
        }
    }

    private String encodeJson(Map<String, Object> content) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(content));
    }

    private byte[] signBytes(byte[] content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        return mac.doFinal(content);
    }

    public record IssuedToken(
            String token,
            Instant expiresAt
    ) {
    }
}
