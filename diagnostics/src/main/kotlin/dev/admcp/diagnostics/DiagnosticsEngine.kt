package dev.admcp.diagnostics

import dev.admcp.core.model.Availability
import dev.admcp.core.model.DiagnosisConfidence
import dev.admcp.core.model.FieldCrashPage
import dev.admcp.core.model.FieldCrashQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DiagnosticFinding(
    val id: String,
    val severity: String,
    val message: String,
    val source: String,
    val confidence: DiagnosisConfidence,
)

data class DiagnosticReport(
    val findings: List<DiagnosticFinding>,
    val raw: JsonObject,
    val availability: Availability,
    val evidence: List<String>,
)

class DiagnosticsEngine(private val analyzerAvailable: Boolean) {
    fun analyze(rawJson: String?, source: String): DiagnosticReport {
        if (!analyzerAvailable) {
            return DiagnosticReport(emptyList(), JsonObject(emptyMap()), Availability.UNAVAILABLE, listOf("diagnostics backend is not available"))
        }
        if (rawJson.isNullOrBlank()) {
            return DiagnosticReport(emptyList(), JsonObject(emptyMap()), Availability.LIMITED, listOf("no analyzer output"))
        }
        val raw = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrElse {
            return DiagnosticReport(emptyList(), JsonObject(emptyMap()), Availability.FAILED, listOf("analyzer output was not valid JSON"))
        }
        val findings = raw["issues"]?.jsonArray?.mapNotNull { element ->
            val issue = element.jsonObject
            DiagnosticFinding(
                issue["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                issue["severity"]?.jsonPrimitive?.content ?: "UNKNOWN",
                issue["message"]?.jsonPrimitive?.content ?: "",
                source,
                DiagnosisConfidence.MEDIUM,
            )
        }.orEmpty()
        return DiagnosticReport(findings, raw, Availability.AVAILABLE, listOf("source=$source", "issues=${findings.size}"))
    }
}

class FieldCrashClient(private val enabled: Boolean, private val query: suspend (FieldCrashQuery) -> FieldCrashPage) {
    suspend fun fetch(request: FieldCrashQuery): FieldCrashPage {
        check(enabled) { "field crash intelligence is disabled; no cloud credentials are configured" }
        return query(request)
    }
}

data class AiDiagnosis(val summary: String, val confidence: DiagnosisConfidence, val evidence: List<String>, val hypothesis: Boolean = true)

object AiDiagnosisFormatter {
    fun format(report: DiagnosticReport): AiDiagnosis {
        val top = report.findings.maxByOrNull { severityRank(it.severity) }
        return AiDiagnosis(
            summary = top?.let { "${it.severity}: ${it.message}" } ?: "No deterministic finding was produced",
            confidence = if (top == null) DiagnosisConfidence.UNASSESSED else DiagnosisConfidence.MEDIUM,
            evidence = report.evidence + report.findings.map { "${it.source}:${it.id}" },
        )
    }

    private fun severityRank(severity: String): Int = when (severity.uppercase()) {
        "FATAL", "ERROR" -> 4
        "WARNING" -> 3
        "INFO" -> 2
        else -> 1
    }
}
