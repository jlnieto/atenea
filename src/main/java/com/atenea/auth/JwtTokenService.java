package com.atenea.auth;

import com.atenea.auth.session.SessionVersions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private static final Pattern AUTHENTICATION_METHOD =
            Pattern.compile("^[a-z0-9][a-z0-9_-]{1,31}$");

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
        return issueToken(operator, null, null, null, null, issuedAt);
    }

    public IssuedToken issueAccessToken(
            AuthenticatedOperator operator,
            UUID sessionFamilyId,
            SessionVersions versions
    ) {
        return issueAccessToken(operator, sessionFamilyId, versions, Instant.now(), List.of("pwd"));
    }

    public IssuedToken issueAccessToken(
            AuthenticatedOperator operator,
            UUID sessionFamilyId,
            SessionVersions versions,
            Instant authenticatedAt,
            List<String> authenticationMethods
    ) {
        if (sessionFamilyId == null
                || versions == null
                || authenticatedAt == null
                || authenticationMethods == null
                || authenticationMethods.isEmpty()) {
            throw new IllegalArgumentException("Family-bound access token metadata is required");
        }
        return issueToken(
                operator,
                sessionFamilyId,
                versions,
                authenticatedAt,
                validateAuthenticationMethods(authenticationMethods),
                Instant.now());
    }

    private IssuedToken issueToken(
            AuthenticatedOperator operator,
            UUID sessionFamilyId,
            SessionVersions versions,
            Instant authenticatedAt,
            List<String> authenticationMethods,
            Instant issuedAt
    ) {
        Instant expiresAt = issuedAt.plus(properties.getJwt().getAccessTokenTtl());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getJwt().getIssuer());
        claims.put("sub", operator.email());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("tokenType", "access");
        claims.put("operatorId", operator.operatorId());
        claims.put("displayName", operator.displayName());
        if (sessionFamilyId != null) {
            claims.put("sid", sessionFamilyId.toString());
            claims.put("credentialVersion", versions.credentialVersion());
            claims.put("roleVersion", versions.roleVersion());
            claims.put("auth_time", authenticatedAt.getEpochSecond());
            claims.put("amr", authenticationMethods);
        }
        return new IssuedToken(sign(claims), expiresAt);
    }

    public AuthenticatedOperator parseAccessToken(String token) {
        return parseSessionAccessToken(token).operator();
    }

    public ParsedAccessToken parseSessionAccessToken(String token) {
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

            boolean hasFamily = payload.has("sid");
            boolean hasCredentialVersion = payload.has("credentialVersion");
            boolean hasRoleVersion = payload.has("roleVersion");
            boolean hasAuthenticationTime = payload.has("auth_time");
            boolean hasAuthenticationMethods = payload.has("amr");
            if (payload.has("sessionFamilyId")) {
                throw new OperatorAuthenticationException("Non-canonical session access token");
            }
            int sessionClaimCount = (hasFamily ? 1 : 0)
                    + (hasCredentialVersion ? 1 : 0)
                    + (hasRoleVersion ? 1 : 0)
                    + (hasAuthenticationTime ? 1 : 0)
                    + (hasAuthenticationMethods ? 1 : 0);
            if (sessionClaimCount != 0 && sessionClaimCount != 5) {
                throw new OperatorAuthenticationException("Incomplete session access token");
            }

            UUID familyId = null;
            Long credentialVersion = null;
            Long roleVersion = null;
            Instant authenticatedAt = null;
            List<String> authenticationMethods = List.of();
            if (hasFamily) {
                familyId = UUID.fromString(payload.path("sid").asText());
                credentialVersion = requiredNonNegativeLong(payload, "credentialVersion");
                roleVersion = requiredNonNegativeLong(payload, "roleVersion");
                authenticatedAt = Instant.ofEpochSecond(
                        requiredNonNegativeLong(payload, "auth_time"));
                Instant issuedAt = Instant.ofEpochSecond(
                        requiredNonNegativeLong(payload, "iat"));
                if (authenticatedAt.isAfter(issuedAt)) {
                    throw new OperatorAuthenticationException("Invalid access token authentication time");
                }
                authenticationMethods = requiredAuthenticationMethods(payload);
            }

            return new ParsedAccessToken(
                    new AuthenticatedOperator(
                            operatorId,
                            email,
                            payload.path("displayName").asText("")),
                    familyId,
                    credentialVersion,
                    roleVersion,
                    authenticatedAt,
                    authenticationMethods,
                    hasFamily);
        } catch (OperatorAuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OperatorAuthenticationException("Invalid access token");
        }
    }

    private long requiredNonNegativeLong(JsonNode payload, String claim) {
        JsonNode value = payload.path(claim);
        if (!value.isIntegralNumber() || value.asLong() < 0) {
            throw new OperatorAuthenticationException("Invalid access token session version");
        }
        return value.asLong();
    }

    private List<String> requiredAuthenticationMethods(JsonNode payload) {
        JsonNode methods = payload.path("amr");
        if (!methods.isArray() || methods.isEmpty()) {
            throw new OperatorAuthenticationException("Invalid access token authentication methods");
        }
        List<String> values = new ArrayList<>();
        methods.forEach(method -> {
            if (!method.isTextual() || !AUTHENTICATION_METHOD.matcher(method.asText()).matches()) {
                throw new OperatorAuthenticationException("Invalid access token authentication methods");
            }
            values.add(method.asText());
        });
        if (new HashSet<>(values).size() != values.size()) {
            throw new OperatorAuthenticationException("Invalid access token authentication methods");
        }
        return List.copyOf(values);
    }

    private List<String> validateAuthenticationMethods(List<String> methods) {
        Set<String> unique = new HashSet<>();
        for (String method : methods) {
            if (method == null
                    || !AUTHENTICATION_METHOD.matcher(method).matches()
                    || !unique.add(method)) {
                throw new IllegalArgumentException("Invalid authentication method");
            }
        }
        return List.copyOf(methods);
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

    public record ParsedAccessToken(
            AuthenticatedOperator operator,
            UUID sessionFamilyId,
            Long credentialVersion,
            Long roleVersion,
            Instant authenticatedAt,
            List<String> authenticationMethods,
            boolean sessionBound
    ) {
    }
}
