package com.atenea.remoteworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class ValidationRecoveryGateway {
    private final RemoteWorkerClient worker;
    private final ObjectMapper mapper;
    public ValidationRecoveryGateway(RemoteWorkerClient worker, ObjectMapper mapper) { this.worker=worker; this.mapper=mapper; }
    public Proof call(ValidationRecoveryCommand command, ValidationRecoveryCommand.Action action) {
        var owner=command.owner(); var body=new TreeMap<String,Object>();
        body.put("schemaVersion",1); body.put("protocolVersion",ValidationRecoveryCommand.PROTOCOL);
        body.put("operation",action.name()); body.put("effect",action.effect);
        body.put("operationId",owner.operationId().toString()); body.put("idempotencyKey",owner.idempotencyKey().toString());
        body.put("changeKey",owner.changeKey().toString()); body.put("databaseProjectId",owner.databaseProjectId());
        body.put("projectId",owner.projectIdentity()); body.put("repository",owner.repository());
        body.put("repositoryBranch",owner.repositoryBranch()); body.put("baseCommit",owner.baseCommit());
        body.put("sourceCommit",owner.sourceCommit()); body.put("workspaceBranch",owner.workspaceBranch());
        body.put("workspaceIdentity",owner.workspaceIdentity()); body.put("workerId",owner.workerId());
        body.put("sourceRevision",owner.sourceRevision()); body.put("sourceFingerprintSha256",owner.sourceFingerprintSha256());
        body.put("validationOperationId",command.validationOperationId().toString());
        try { body.put("requestFingerprintSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(body)))); }
        catch (Exception impossible) { throw new IllegalStateException("Cannot seal repair intent",impossible); }
        JsonNode value=worker.validationRecovery(body,action);
        if (value==null || !value.isObject())
            throw new RemoteWorkerException("Validation recovery returned no verifiable evidence",409);
        var fields=new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(fields::add);
        var expected=new java.util.HashSet<>(body.keySet());
        expected.addAll(Set.of("state","recipeId","resultFingerprintSha256","receiptSha256","valuesExposed"));
        boolean exact=value.isObject() && fields.equals(expected) && value.path("valuesExposed").isBoolean()
                && !value.path("valuesExposed").asBoolean();
        for (var entry:body.entrySet()) {
            JsonNode wanted=mapper.valueToTree(entry.getValue()), actual=value.get(entry.getKey());
            exact &= actual!=null && (wanted.isIntegralNumber() && actual.isIntegralNumber()
                    ? wanted.bigIntegerValue().equals(actual.bigIntegerValue()) : wanted.equals(actual));
        }
        String state=value.path("state").asText(); String recipe=text(value,"recipeId");
        String fingerprint=text(value,"resultFingerprintSha256"), receipt=text(value,"receiptSha256");
        var states=switch(action) {
            case DIAGNOSE -> Set.of("AVAILABLE","UNSUPPORTED");
            case PREPARE -> Set.of("READY","APPLYING","APPLIED");
            case APPLY -> Set.of("APPLIED");
            case INSPECT -> Set.of("ABSENT","READY","APPLYING","APPLIED");
        };
        if (!exact || !states.contains(state)
                || recipe!=null && !recipe.equals("atenea-explicit-android-version-v1")
                || state.equals("AVAILABLE") && recipe==null
                || fingerprint!=null && !hash(fingerprint) || receipt!=null && !hash(receipt)
                || Set.of("READY","APPLYING","APPLIED").contains(state) && (!hash(receipt) || recipe==null)
                || state.equals("APPLIED") && !hash(fingerprint)) {
            throw new RemoteWorkerException("Validation recovery returned foreign or incomplete evidence",409);
        }
        return new Proof(state,recipe,fingerprint,receipt,value);
    }
    private static String text(JsonNode value,String key) {
        var field=value.get(key);
        if (field==null || !field.isNull() && !field.isTextual())
            throw new RemoteWorkerException("Validation recovery evidence has an invalid type",409);
        return field.isNull()?null:field.textValue();
    }
    private static boolean hash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
    public record Proof(String state,String recipeId,String resultFingerprintSha256,String receiptSha256,JsonNode evidence) { }
}
