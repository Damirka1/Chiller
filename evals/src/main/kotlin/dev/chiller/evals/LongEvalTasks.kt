package dev.chiller.evals

import dev.chiller.contracts.ExecutionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** These run through the same runner/providers as short tasks; they are not model-performance claims. */
internal val LONG_EVAL_TASKS: List<EvalTask> = listOf(
    evolvingPricingTask(),
    retainedConstraintsTask(),
)

private const val PRICING_SOURCE = """from app.settings import CURRENCY


def subtotal(prices):
    return sum(prices[:-1])


def quote_total(prices, discount=0):
    return f'{CURRENCY} {subtotal(prices) * (1 - discount):.2f}'
"""

private fun evolvingPricingTask(): EvalTask {
    val fixed = PRICING_SOURCE.replace("sum(prices[:-1])", "sum(prices)")
    val capped = fixed.replace("(1 - discount)", "(1 - min(0.5, max(0, discount)))")
    val userSettings = "# User change: invoices for the Almaty office\nCURRENCY = 'EUR'\n"
    fun verify(project: Path, expression: String) = python(project, "-c", "exec(open('checks.py').read()); $expression")
    return EvalTask(
        id = "conversation-requirements",
        title = "Смена требований, правки пользователя и перезапуск",
        prompt = "Fix subtotal in app/pricing.py: it drops the final price. Check the public quote_total(prices, discount=0) API as well. " +
            "For this entire task preserve that public signature, keep code split across app/pricing.py and app/settings.py, and do not change verification files.",
        files = mapOf(
            "app/__init__.py" to "",
            "app/settings.py" to "# Default invoice currency\nCURRENCY = 'USD'\n",
            "app/pricing.py" to PRICING_SOURCE,
            "checks.py" to "from app.pricing import subtotal, quote_total\nimport inspect\n" +
                "assert subtotal([]) == 0\nassert subtotal([2, 3, 5]) == 10\n" +
                "assert str(inspect.signature(quote_total)) == '(prices, discount=0)'\nprint('stable API checks passed')\n",
        ),
        check = { project, _ -> verify(project, "assert quote_total([2, 3, 5]) == 'USD 10.00'") },
        reference = { project -> Files.writeString(project.resolve("app/pricing.py"), fixed); "Fixed subtotal; stable checks pass." },
        followUps = listOf(
            EvalTurn(
                prompt = "Requirements changed: clamp the discount fraction to [0, 0.5]. A negative discount means zero; anything over 0.5 means 50%. " +
                    "Keep the earlier API constraints. Verify normal, negative and excessive discounts.",
                check = { project, _ -> verify(project,
                    "assert quote_total([20, 80], 0.25) == 'USD 75.00'; assert quote_total([20, 80], -1) == 'USD 100.00'; assert quote_total([20, 80], 0.9) == 'USD 50.00'") },
                reference = { project -> Files.writeString(project.resolve("app/pricing.py"), capped); "Discounts are clamped; checks pass." },
            ),
            EvalTurn(
                prompt = "I edited app/settings.py myself while the app was closed. Keep my file byte-for-byte. Change quote_total's label to use a colon " +
                    "between the currency and the amount. Preserve the discount behavior and all earlier API constraints, and verify against the current files.",
                before = { project -> Files.writeString(project.resolve("app/settings.py"), userSettings) },
                restartBefore = true,
                check = { project, _ ->
                    if (Files.readString(project.resolve("app/settings.py")) != userSettings) Verdict(false, "user's settings change was overwritten")
                    else verify(project, "assert quote_total([2, 3, 5]) == 'EUR: 10.00'; assert quote_total([20, 80], 1) == 'EUR: 50.00'; assert quote_total([20, 80], -1) == 'EUR: 100.00'")
                },
                reference = { project -> Files.writeString(project.resolve("app/pricing.py"), capped.replace("{CURRENCY} ", "{CURRENCY}: ")); "Preserved your settings and adjusted the label; checks pass." },
            ),
        ),
    )
}

private fun retainedConstraintsTask(): EvalTask {
    val unchanged = "# Deployment settings are generated only after review.\n"
    fun acknowledge(project: Path, answer: String, marker: String): Verdict {
        val untouched = Files.readString(project.resolve("README.md")) == unchanged && !Files.exists(project.resolve("release.json"))
        return Verdict(untouched && answer.contains(marker), "review must acknowledge $marker and leave the workspace unchanged")
    }
    // Each real user turn carries bounded source material. The final turn must use a compacted
    // earlier constraint, rather than an oracle injected into the system prompt or project files.
    val reviews = (1..8).map { batch ->
        val marker = "BATCH-$batch"
        val excerpt = (1..90).joinToString("\n") { line ->
            "$marker sample $line: worker returned status 200; latency=${20 + line % 31}ms; cache=warm; retries_observed=0."
        }
        EvalTurn(
            prompt = "Review this historical diagnostic excerpt and acknowledge $marker in one sentence. No files should be written yet. " +
                "These are observations, not deployment requirements.\n$excerpt",
            check = { project, answer -> acknowledge(project, answer, marker) },
            reference = { "$marker reviewed; all samples returned status 200." },
            mode = ExecutionMode.PLAN,
            allowProcesses = false,
            restartBefore = batch == 5,
        )
    }
    return EvalTask(
        id = "conversation-context-pressure",
        title = "Сохранить ранние ограничения после восьми ходов и сжатия",
        prompt = "Goal: after the reviews, create release.json using our agreed deployment settings. " +
            "Persistent constraint: channel must be canary and region must be kz. Ограничение на всю задачу: retries должно быть 2. " +
            "Do not write files until I explicitly ask for release.json. Do not change README.md. Acknowledge SETUP now.",
        files = mapOf("README.md" to unchanged),
        mode = ExecutionMode.PLAN,
        allowProcesses = false,
        check = { project, answer -> acknowledge(project, answer, "SETUP") },
        reference = { "SETUP acknowledged; deployment constraints retained." },
        contextWindowTokens = 16_384,
        requireContextCompaction = true,
        followUps = reviews + EvalTurn(
            prompt = "Reviews are complete. Create release.json now with exactly the three deployment settings we agreed at the beginning. Preserve the existing project files.",
            mode = ExecutionMode.WORKSPACE_EDIT,
            allowProcesses = false,
            check = { project, _ ->
                val data = runCatching { Json.parseToJsonElement(Files.readString(project.resolve("release.json"))).jsonObject }.getOrNull()
                val correct = data?.keys == setOf("channel", "region", "retries") &&
                    data["channel"]?.jsonPrimitive?.content == "canary" && data["region"]?.jsonPrimitive?.content == "kz" &&
                    data["retries"]?.jsonPrimitive?.intOrNull == 2 && Files.readString(project.resolve("README.md")) == unchanged
                Verdict(correct, "release.json must retain all three original deployment constraints and README.md")
            },
            reference = { project -> Files.writeString(project.resolve("release.json"), "{\"channel\":\"canary\",\"region\":\"kz\",\"retries\":2}\n"); "Created release.json with the agreed settings." },
        ),
    )
}
