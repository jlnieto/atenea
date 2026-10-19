package com.atenea.remoteworker;

import com.atenea.persistence.worksession.ValidationOperationEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Closed evidence, not arbitrary logs or candidate instructions. */
@Service
public class ValidationDiagnosticGateway {
    private final RemoteWorkerClient worker;
    public ValidationDiagnosticGateway(RemoteWorkerClient worker) { this.worker=worker; }

    public JsonNode read(WorkSessionEntity session, ValidationOperationEntity failed) {
        JsonNode value=worker.validationDiagnostic(session,failed.getId().toString());
        if (!fields(value,Set.of("schemaVersion","protocolVersion","workspaceIdentity","projectId",
                "artifactManifestSha256","diagnosticSha256","failureDetailsState","diagnostic","valuesExposed"))
                || !value.path("schemaVersion").isIntegralNumber() || value.path("schemaVersion").asInt()!=1
                || !text(value,"protocolVersion","closed-validation-diagnostic/v1")
                || !text(value,"workspaceIdentity",session.getWorkspaceIdentity())
                || !text(value,"projectId",ProjectCodexIdentity.PROJECT_IDENTITY)
                || !hash(value,"artifactManifestSha256") || !text(value,"artifactManifestSha256",failed.getArtifactManifestSha256())
                || !hash(value,"diagnosticSha256") || !noValues(value)
                || !Set.of("RECORDED","NOT_RECORDED").contains(value.path("failureDetailsState").asText())) throw rejected();
        JsonNode d=value.get("diagnostic");
        if (!fields(d,Set.of("schemaVersion","operationId","sessionId","operation","definitionRevision",
                "sourceTreeFingerprintSha256","phase","errorCode","failureClass","exitCode","durationMillis",
                "outputSha256","testClasses","testFailures","valuesExposed"))
                || !d.path("schemaVersion").isIntegralNumber() || d.path("schemaVersion").asInt()!=1
                || !text(d,"operationId",failed.getId().toString())
                || !text(d,"sessionId",session.getRemoteSessionId().toString())
                || !text(d,"operation",failed.getOperation().name())
                || !text(d,"definitionRevision",failed.getDefinitionRevision())
                || !hash(d,"sourceTreeFingerprintSha256")
                || !text(d,"sourceTreeFingerprintSha256",failed.getSourceTreeFingerprintSha256())
                || !symbol(d,"phase","[A-Z_]{1,80}") || !symbol(d,"errorCode","[A-Z_]{1,80}")
                || !Set.of("NONE","CANDIDATE","INFRASTRUCTURE","POLICY","VALIDATION","OWNERSHIP").contains(d.path("failureClass").asText())
                || !d.path("exitCode").canConvertToInt() || !d.path("exitCode").isIntegralNumber()
                || !d.path("durationMillis").isIntegralNumber() || !d.path("durationMillis").canConvertToLong() || d.path("durationMillis").asLong()<0
                || !hash(d,"outputSha256") || !noValues(d)
                || !d.path("testClasses").isArray() || d.path("testClasses").size()>8
                || !d.path("testFailures").isArray() || d.path("testFailures").size()>16
                || !text(value,"failureDetailsState",d.path("testFailures").isEmpty()?"NOT_RECORDED":"RECORDED")) throw rejected();
        for (JsonNode c:d.path("testClasses")) if (!c.isTextual() || !c.asText().matches("com\\.atenea\\.[A-Za-z0-9_.$]{1,180}")) throw rejected();
        for (JsonNode f:d.path("testFailures")) {
            if (!fields(f,Set.of("testClass","testMethod","failureType","sourceLine"))
                    || !symbol(f,"testClass","com\\.atenea\\.[A-Za-z0-9_.$]{1,180}")
                    || !symbol(f,"testMethod","[A-Za-z_$][A-Za-z0-9_$]{0,120}")
                    || !f.path("failureType").isNull() && !symbol(f,"failureType","(?:org\\.(?:opentest4j|junit|mockito|springframework)|java\\.lang|jakarta|com\\.atenea)\\.[A-Za-z0-9_.$]{1,180}")
                    || !f.path("sourceLine").isNull() && (!f.path("sourceLine").isIntegralNumber() || !f.path("sourceLine").canConvertToInt()
                        || f.path("sourceLine").asInt()<1 || f.path("sourceLine").asInt()>999999)) throw rejected();
        }
        return value.deepCopy();
    }
    private static boolean fields(JsonNode n,Set<String> expected) {
        if(n==null||!n.isObject())return false;var keys=new HashSet<String>();n.fieldNames().forEachRemaining(keys::add);return keys.equals(expected);
    }
    private static boolean text(JsonNode n,String k,String expected) { return expected!=null&&n.path(k).isTextual()&&expected.equals(n.path(k).asText()); }
    private static boolean symbol(JsonNode n,String k,String pattern) {return n.path(k).isTextual()&&n.path(k).asText().matches(pattern);}
    private static boolean hash(JsonNode n,String k) {return symbol(n,k,"[0-9a-f]{64}");}
    private static boolean noValues(JsonNode n) {return n.path("valuesExposed").isBoolean()&&!n.path("valuesExposed").asBoolean();}
    private static RemoteWorkerException rejected() {return new RemoteWorkerException("VALIDATION_DIAGNOSTIC_EVIDENCE_CONFLICT",409);}
}
