package dev.admcp.debug.expression

import dev.admcp.core.model.ExpressionLanguage
import dev.admcp.core.model.ExpressionOperation
import dev.admcp.core.model.ExpressionPolicy
import dev.admcp.core.model.ExpressionPolicyDecision
import dev.admcp.core.model.InspectionContext
import java.time.Duration

class ExpressionPolicyEngine {
    fun decide(
        language: ExpressionLanguage,
        operation: ExpressionOperation,
        policy: ExpressionPolicy,
        context: InspectionContext,
        timeout: Duration,
        arrayIndex: Int? = null,
    ): ExpressionPolicyDecision {
        if (timeout.isNegative || timeout > policy.maxEvaluationTime) {
            return ExpressionPolicyDecision(false, "evaluation timeout exceeds policy")
        }
        if (context.requestedBytes > 64 * 1024) {
            return ExpressionPolicyDecision(false, "requested memory exceeds hard byte limit")
        }
        if (context.objectDepth > policy.maxObjectDepth) {
            return ExpressionPolicyDecision(false, "object depth exceeds policy")
        }
        if (arrayIndex != null && (arrayIndex < 0 || arrayIndex >= policy.maxArrayElements)) {
            return ExpressionPolicyDecision(false, "array index exceeds policy")
        }
        if (operation == ExpressionOperation.ASSIGNMENT || operation == ExpressionOperation.ARBITRARY_CODE) {
            return ExpressionPolicyDecision(false, "mutating and arbitrary code evaluation are not permitted")
        }
        if (!policy.allowReadOnly) {
            return ExpressionPolicyDecision(false, "read-only evaluation is disabled")
        }
        if (operation == ExpressionOperation.METHOD_INVOCATION) {
            return ExpressionPolicyDecision(false, "method invocation requires a separate explicit approval and is disabled in v1")
        }
        return when (language) {
            ExpressionLanguage.NATIVE_CPP -> when (operation) {
                ExpressionOperation.NATIVE_SCOPE_READ, ExpressionOperation.NATIVE_MEMORY_READ ->
                    if (context.requestedBytes <= policy.maxArrayElements * 8) ExpressionPolicyDecision(true, null)
                    else ExpressionPolicyDecision(false, "native read size exceeds policy")
                else -> ExpressionPolicyDecision(false, "operation is not a native read")
            }
            ExpressionLanguage.JAVA, ExpressionLanguage.KOTLIN, ExpressionLanguage.DART -> when (operation) {
                ExpressionOperation.READ_LOCAL, ExpressionOperation.READ_FIELD,
                ExpressionOperation.READ_ARRAY_ELEMENT, ExpressionOperation.PROPERTY_ACCESS ->
                    ExpressionPolicyDecision(true, null)
                else -> ExpressionPolicyDecision(false, "operation is not a read-only JVM inspection")
            }
        }
    }
}
