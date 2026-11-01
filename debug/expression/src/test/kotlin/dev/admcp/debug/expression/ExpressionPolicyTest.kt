package dev.admcp.debug.expression

import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ExpressionLanguage
import dev.admcp.core.model.ExpressionOperation
import dev.admcp.core.model.ExpressionPolicy
import dev.admcp.core.model.InspectionContext
import dev.admcp.core.model.ProcessId
import dev.admcp.core.model.ProcessInstanceId
import dev.admcp.core.model.ProcessRef
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpressionPolicyTest {
    private val policy = ExpressionPolicy(true, false, Duration.ofSeconds(2), 4, 100)
    private val context = InspectionContext(
        ProcessRef(DeviceId("d1"), ProcessId(1), "pkg", ProcessInstanceId("i1"), null),
        dev.admcp.core.model.StopEpoch(1),
        requestedBytes = 16,
        objectDepth = 1,
    )

    @Test
    fun allowsReadOnlyJavaLocal() {
        assertTrue(ExpressionPolicyEngine().decide(ExpressionLanguage.JAVA, ExpressionOperation.READ_LOCAL, policy, context, Duration.ofSeconds(1)).allowed)
    }

    @Test
    fun deniesAssignmentAndArbitraryCode() {
        assertFalse(ExpressionPolicyEngine().decide(ExpressionLanguage.KOTLIN, ExpressionOperation.ASSIGNMENT, policy, context, Duration.ofSeconds(1)).allowed)
        assertFalse(ExpressionPolicyEngine().decide(ExpressionLanguage.JAVA, ExpressionOperation.ARBITRARY_CODE, policy, context, Duration.ofSeconds(1)).allowed)
    }

    @Test
    fun deniesMethodInvocationUnlessExplicitlyEnabled() {
        assertFalse(ExpressionPolicyEngine().decide(ExpressionLanguage.JAVA, ExpressionOperation.METHOD_INVOCATION, policy, context, Duration.ofSeconds(1)).allowed)
        val enabled = policy.copy(allowMethodInvocation = true)
        assertFalse(ExpressionPolicyEngine().decide(ExpressionLanguage.JAVA, ExpressionOperation.METHOD_INVOCATION, enabled, context, Duration.ofSeconds(1)).allowed)
    }

    @Test
    fun enforcesNativeMemoryBoundsAndTimeout() {
        assertFalse(ExpressionPolicyEngine().decide(ExpressionLanguage.NATIVE_CPP, ExpressionOperation.NATIVE_MEMORY_READ, policy, context.copy(requestedBytes = 1024), Duration.ofSeconds(1)).allowed)
        assertFalse(ExpressionPolicyEngine().decide(ExpressionLanguage.NATIVE_CPP, ExpressionOperation.NATIVE_SCOPE_READ, policy, context, Duration.ofSeconds(30)).allowed)
        assertTrue(ExpressionPolicyEngine().decide(ExpressionLanguage.NATIVE_CPP, ExpressionOperation.NATIVE_MEMORY_READ, policy, context, Duration.ofSeconds(1)).allowed)
    }

    @Test
    fun enforcesObjectDepthAndArrayBounds() {
        assertFalse(ExpressionPolicyEngine().decide(ExpressionLanguage.JAVA, ExpressionOperation.PROPERTY_ACCESS, policy, context.copy(objectDepth = 10), Duration.ofSeconds(1)).allowed)
        assertFalse(ExpressionPolicyEngine().decide(ExpressionLanguage.JAVA, ExpressionOperation.READ_ARRAY_ELEMENT, policy, context, Duration.ofSeconds(1), arrayIndex = 1000).allowed)
    }
}
