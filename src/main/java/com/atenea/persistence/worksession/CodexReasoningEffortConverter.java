package com.atenea.persistence.worksession;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class CodexReasoningEffortConverter
        implements AttributeConverter<CodexReasoningEffort, String> {

    @Override
    public String convertToDatabaseColumn(CodexReasoningEffort attribute) {
        return attribute == null ? null : attribute.canonicalValue();
    }

    @Override
    public CodexReasoningEffort convertToEntityAttribute(String dbData) {
        return dbData == null ? null : CodexReasoningEffort.fromCanonicalValue(dbData);
    }
}
