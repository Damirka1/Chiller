package dev.chiller.evals

import dev.chiller.contracts.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path

private const val CONVERT_SOURCE = "def to_fahrenheit(tmp):\n    \"\"\"Convert Celsius to Fahrenheit.\"\"\"\n    if tmp is None:\n        raise ValueError(\"tmp is required\")\n" +
    "    return tmp * 9 / 5 + 32\n\n\ndef describe(tmp):\n    return f\"{tmp} C = {to_fahrenheit(tmp)} F\"\n"

private const val PATTERNS_SOURCE = """import re

VERSION_RE = re.compile(r"^v(\d+)\.(\d+)$")
LOG_DIR = r"C:\logs\app"
GREETING = "say \"hi\"\n"
"""

private const val MAKEFILE = ".PHONY: test build\n\ntest:\n\tpython3 -m unittest discover -s tests\n\nbuild:\n\tpython3 -m build\n"

/** Targets with their recipe lines; a recipe line indented with spaces instead of a tab fails on its own. */
private fun checkMakefile(text: String): Verdict {
    val lines = text.lines()
    val spaced = lines.firstOrNull { it.startsWith(" ") && it.isNotBlank() }
    if (spaced != null) return Verdict(false, "a recipe line starts with spaces, not a tab: '${spaced.trim()}'")
    val phony = lines.firstOrNull { it.startsWith(".PHONY:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
    if (phony != setOf("test", "build", "clean")) return Verdict(false, ".PHONY lists $phony")
    val recipes = mutableMapOf<String, MutableList<String>>()
    var target: String? = null
    for (line in lines) when {
        line.startsWith("\t") -> target?.let { recipes.getValue(it) += line.substring(1) }
        line.endsWith(":") && !line.startsWith(".") -> { target = line.removeSuffix(":"); recipes[target!!] = mutableListOf() }
        line.isNotBlank() && !line.startsWith(".PHONY") -> target = null
    }
    val expected = mapOf("test" to listOf("python3 -m unittest discover -s tests -f"), "build" to listOf("python3 -m build"), "clean" to listOf("rm -rf dist"))
    return if (recipes == expected) Verdict(true, "recipes and .PHONY as requested, tabs kept") else Verdict(false, "recipes: $recipes")
}

/** 1200 constants with the two targets near the start and the end, so no single read covers both. */
private val BIG_CONSTANTS: String = buildString {
    appendLine("\"\"\"Tuning constants. One per line; generated.\"\"\"")
    for (i in 1..1200) when (i) {
        140 -> appendLine("RETRY_LIMIT = 3")
        1130 -> appendLine("CACHE_TTL = 60")
        else -> appendLine("LIMIT_%04d = %d".format(i, i * 3))
    }
}

/** Functions with unrelated names three lines apart, so the name cannot be computed from the line number. */
private val REGISTRY_NAMES: List<String> = run {
    val first = listOf("load", "save", "parse", "render", "merge", "split", "check", "build", "fetch", "store", "scan", "trim")
    val second = listOf("invoice", "ledger", "parcel", "voucher", "tariff", "quota", "badge", "ticket", "report", "batch")
    (0 until 100).map { i -> "${first[(i * 7) % first.size]}_${second[(i * 3) % second.size]}_${(i * 37) % 101}" }
}
private val REGISTRY_SOURCE: String = REGISTRY_NAMES.joinToString("") { "def $it():\n    return '${it.reversed()}'\n\n" }
private const val PROBE_INDEX = 72
private val PROBE_LINE = PROBE_INDEX * 3 + 1
private val PROBE_FUNCTION = REGISTRY_NAMES[PROBE_INDEX]

private const val SQUARES_COUNT_LAST = 399

/**
 * Short tasks aimed at one tool-calling skill each: exact edits at the end of a file, JSON escaping of
 * backslashes and quotes, tabs, replace-all counts, chained edits, partial reads, long output and writing
 * more than one reply can hold. They are small on purpose, so a failure points at the harness or the call.
 */
internal val TOOL_PROBE_TASKS: List<EvalTask> = listOf(
    EvalTask(
        id = "tool-eof-edit",
        title = "Правка последней функции файла",
        prompt = "Implement area(w, h) and perimeter(w, h) of a rectangle in shapes.py so that `python3 checks.py` passes. Run the checks.",
        files = mapOf(
            "shapes.py" to "def area(w, h):\n    raise NotImplementedError\n\n\ndef perimeter(w, h):\n    raise NotImplementedError\n",
            "checks.py" to "from shapes import area, perimeter\nassert area(2, 3) == 6\nassert perimeter(2, 3) == 10\nprint('shapes ok')\n",
        ),
        check = { project, _ -> python(project, "checks.py") },
        reference = { project ->
            Files.writeString(project.resolve("shapes.py"), "def area(w, h):\n    return w * h\n\n\ndef perimeter(w, h):\n    return 2 * (w + h)\n")
            "Implemented both functions; checks pass."
        },
    ),
    EvalTask(
        id = "tool-replace-all",
        title = "Заменить все вхождения в одном файле",
        prompt = "In convert.py rename the parameter `tmp` to `celsius` in both functions, including the error message text. Change nothing else. Run `python3 checks.py`.",
        files = mapOf(
            "convert.py" to CONVERT_SOURCE,
            "checks.py" to "import inspect\nimport convert\nassert convert.to_fahrenheit(100) == 212\nassert convert.describe(0) == '0 C = 32.0 F'\n" +
                "assert list(inspect.signature(convert.to_fahrenheit).parameters) == ['celsius']\nassert list(inspect.signature(convert.describe).parameters) == ['celsius']\n" +
                "try:\n    convert.to_fahrenheit(None)\n    raise SystemExit('None must raise ValueError')\nexcept ValueError as error:\n    assert str(error) == 'celsius is required', error\nprint('convert ok')\n",
        ),
        check = { project, _ ->
            val actual = Files.readString(project.resolve("convert.py"))
            if (actual != CONVERT_SOURCE.replace("tmp", "celsius")) Verdict(false, "convert.py differs from the exact rename") else python(project, "checks.py")
        },
        reference = { project -> Files.writeString(project.resolve("convert.py"), CONVERT_SOURCE.replace("tmp", "celsius")); "Renamed tmp to celsius (7 places)." },
    ),
    EvalTask(
        id = "tool-escapes",
        title = "Правка строк с обратными слэшами и кавычками",
        prompt = "In patterns.py: (1) make VERSION_RE also require a patch number, so it matches `v1.2.3` and no longer matches `v1.2`; " +
            "(2) change LOG_DIR to the Windows path `D:\\logs\\chiller`, keeping it a raw string; (3) change GREETING so the quoted word is hello instead of hi, " +
            "keeping the escaped quotes and the trailing \\n escape. Change nothing else. Run `python3 checks.py`.",
        files = mapOf(
            "patterns.py" to PATTERNS_SOURCE,
            "checks.py" to """import patterns
assert patterns.VERSION_RE.match('v1.2.3').groups()[:3] == ('1', '2', '3')
assert patterns.VERSION_RE.match('v1.2') is None
assert patterns.VERSION_RE.match('v1.2.3x') is None
assert patterns.LOG_DIR == 'D:\\logs\\chiller', repr(patterns.LOG_DIR)
assert patterns.GREETING == 'say "hello"\n', repr(patterns.GREETING)
print('patterns ok')
""",
        ),
        check = { project, _ ->
            val lines = Files.readString(project.resolve("patterns.py")).lines()
            when {
                lines.firstOrNull() != "import re" -> Verdict(false, "the import line changed")
                lines.none { it.startsWith("LOG_DIR = r\"") } -> Verdict(false, "LOG_DIR is no longer a raw string")
                else -> python(project, "checks.py")
            }
        },
        reference = { project ->
            Files.writeString(project.resolve("patterns.py"), PATTERNS_SOURCE.replace("""(\d+)\.(\d+)$""", """(\d+)\.(\d+)\.(\d+)$""")
                .replace("""C:\logs\app""", """D:\logs\chiller""").replace("""\"hi\"""", """\"hello\""""))
            "Updated VERSION_RE, LOG_DIR and GREETING; checks pass."
        },
    ),
    EvalTask(
        id = "tool-tabs",
        title = "Сохранить табуляцию в Makefile",
        prompt = "In the Makefile: (1) add the `-f` flag at the end of the `test` recipe so it reads `python3 -m unittest discover -s tests -f`; " +
            "(2) add a new target `clean` at the end of the file whose recipe is `rm -rf dist`; (3) add `clean` to the .PHONY line. " +
            "Make recipes must start with a tab character. Do not run commands.",
        files = mapOf("Makefile" to MAKEFILE),
        allowProcesses = false,
        check = { project, _ -> checkMakefile(Files.readString(project.resolve("Makefile"))) },
        reference = { project ->
            Files.writeString(project.resolve("Makefile"), MAKEFILE.replace(".PHONY: test build", ".PHONY: test build clean")
                .replace("-s tests\n", "-s tests -f\n") + "\nclean:\n\trm -rf dist\n")
            "Added -f to test, a clean target and .PHONY entry; recipes use tabs."
        },
    ),
    EvalTask(
        id = "tool-chain-edits",
        title = "Три правки одного файла подряд",
        prompt = "Make three changes in inventory.py: (1) raise MAX_ITEMS to 50; (2) remove_item must not fail for a missing name, it leaves items unchanged then; " +
            "(3) add a function total_quantity(items) at the end of the file that returns the sum of all quantities. Run `python3 checks.py`.",
        files = mapOf(
            "inventory.py" to "MAX_ITEMS = 10\n\n\ndef add_item(items, name, qty=1):\n    if len(items) >= MAX_ITEMS:\n        raise ValueError(\"inventory full\")\n" +
                "    items[name] = items.get(name, 0) + qty\n    return items\n\n\ndef remove_item(items, name):\n    del items[name]\n    return items\n",
            "checks.py" to "from inventory import MAX_ITEMS, add_item, remove_item, total_quantity\nassert MAX_ITEMS == 50\nitems = {}\nadd_item(items, 'a', 2)\nadd_item(items, 'b')\n" +
                "assert remove_item(dict(items), 'zzz') == {'a': 2, 'b': 1}\nassert total_quantity(items) == 3\nassert remove_item(items, 'a') == {'b': 1}\nprint('inventory ok')\n",
        ),
        check = { project, _ -> python(project, "checks.py") },
        reference = { project ->
            val file = project.resolve("inventory.py")
            Files.writeString(file, Files.readString(file).replace("MAX_ITEMS = 10", "MAX_ITEMS = 50").replace("    del items[name]\n", "    items.pop(name, None)\n") +
                "\n\ndef total_quantity(items):\n    return sum(items.values())\n")
            "Raised MAX_ITEMS, made remove_item tolerant, added total_quantity; checks pass."
        },
    ),
    EvalTask(
        id = "tool-far-edits",
        title = "Две правки в разных концах большого файла",
        prompt = "In constants.py set RETRY_LIMIT to 7 and CACHE_TTL to 600. Change nothing else in the file.",
        files = mapOf("constants.py" to BIG_CONSTANTS),
        allowProcesses = false,
        check = { project, _ ->
            val expected = BIG_CONSTANTS.replace("RETRY_LIMIT = 3\n", "RETRY_LIMIT = 7\n").replace("CACHE_TTL = 60\n", "CACHE_TTL = 600\n")
            val actual = Files.readString(project.resolve("constants.py"))
            if (actual == expected) Verdict(true, "only RETRY_LIMIT and CACHE_TTL changed")
            else Verdict(false, "constants.py differs: ${actual.lines().zip(expected.lines()).count { (a, b) -> a != b } + kotlin.math.abs(actual.lines().size - expected.lines().size)} line(s) wrong")
        },
        reference = { project ->
            Files.writeString(project.resolve("constants.py"), BIG_CONSTANTS.replace("RETRY_LIMIT = 3\n", "RETRY_LIMIT = 7\n").replace("CACHE_TTL = 60\n", "CACHE_TTL = 600\n"))
            "Set RETRY_LIMIT = 7 and CACHE_TTL = 600."
        },
    ),
    EvalTask(
        id = "tool-line-range",
        title = "Прочитать нужный диапазон строк (только чтение)",
        prompt = "What is the name of the function whose definition starts on line $PROBE_LINE of registry.py? Answer with the function name on the first line.",
        files = mapOf("registry.py" to REGISTRY_SOURCE),
        mode = ExecutionMode.PLAN,
        allowProcesses = false,
        check = { _, answer ->
            val first = answer.trim().lineSequence().firstOrNull().orEmpty()
            Verdict(Regex("\\b$PROBE_FUNCTION\\b").containsMatchIn(first), "first line: '${first.take(80)}' (expected $PROBE_FUNCTION)")
        },
        reference = { "$PROBE_FUNCTION\nIt starts on line $PROBE_LINE." },
    ),
    EvalTask(
        id = "tool-long-output",
        title = "Найти причину в длинном выводе проверки",
        prompt = "`python3 checks.py` fails. It prints a line for every case, so the output is long. Find which cases fail, fix the cause in buckets.py and run the checks again.",
        files = mapOf(
            "buckets.py" to "import bisect\n\n# Lower bound of every bucket; bucket i covers [BOUNDS[i], BOUNDS[i + 1]).\n" +
                "BOUNDS = [0, 100, 200, 300, 400, 500, 600, 800, 800, 900, 1000, 1100, 1200, 1300, 1400]\n\n\n" +
                "def bucket(n):\n    return bisect.bisect_right(BOUNDS, n) - 1\n",
            "checks.py" to "from buckets import bucket\nfailures = 0\nfor n in range(1500):\n    actual = bucket(n)\n    if actual == n // 100:\n        print(f'case {n}: ok')\n" +
                "    else:\n        failures += 1\n        print(f'case {n}: FAILED, expected {n // 100}, got {actual}')\n" +
                "print(f'{failures} failures')\nraise SystemExit(1 if failures else 0)\n",
        ),
        check = { project, _ -> python(project, "checks.py") },
        reference = { project -> replaceIn(project, "buckets.py", "600, 800, 800", "600, 700, 800"); "Cases 700-799 failed: BOUNDS had 800 instead of 700. Fixed; checks pass." },
    ),
    EvalTask(
        id = "tool-move-delete",
        title = "Удалить и перенести файлы",
        prompt = "Delete legacy.py (nothing uses it) and move helpers.py to utils/helpers.py, updating the import in main.py. Run `python3 checks.py`.",
        files = mapOf(
            "legacy.py" to "# Deprecated: nothing imports this any more.\ndef old():\n    return 0\n",
            "helpers.py" to "def double(x):\n    return 2 * x\n",
            "main.py" to "from helpers import double\n\nprint(double(21))\n",
            "checks.py" to "import os\nimport subprocess\nimport sys\nassert not os.path.exists('legacy.py'), 'legacy.py still exists'\n" +
                "assert not os.path.exists('helpers.py'), 'helpers.py was not moved'\nassert os.path.isfile('utils/helpers.py'), 'utils/helpers.py missing'\n" +
                "run = subprocess.run([sys.executable, '-B', 'main.py'], capture_output=True, text=True)\nassert run.returncode == 0 and run.stdout.strip() == '42', run\nprint('layout ok')\n",
        ),
        check = { project, _ -> python(project, "checks.py") },
        reference = { project ->
            Files.delete(project.resolve("legacy.py"))
            Files.createDirectories(project.resolve("utils"))
            Files.move(project.resolve("helpers.py"), project.resolve("utils/helpers.py"))
            replaceIn(project, "main.py", "from helpers import", "from utils.helpers import")
            "Deleted legacy.py, moved helpers.py to utils/helpers.py, updated main.py; checks pass."
        },
    ),
    EvalTask(
        id = "tool-chunked-write",
        title = "Записать файл больше одного ответа",
        prompt = "Create squares.py that defines SQUARES as a list literal of the squares of 0 to $SQUARES_COUNT_LAST, written out one number per line " +
            "(`    0,` then `    1,` then `    4,` and so on). No loops, comprehensions, range() or other computation in the file: every number is written literally. " +
            "Then run `python3 checks.py`.",
        files = mapOf(
            "checks.py" to "import ast\nimport pathlib\ntree = ast.parse(pathlib.Path('squares.py').read_text())\n" +
                "assert not any(isinstance(node, (ast.For, ast.comprehension, ast.Call, ast.BinOp)) for node in ast.walk(tree)), 'values are computed, not literal'\n" +
                "import squares\nassert squares.SQUARES == [i * i for i in range(${SQUARES_COUNT_LAST + 1})], 'wrong or missing values'\nprint('squares ok')\n",
        ),
        check = { project, _ -> if (!Files.exists(project.resolve("squares.py"))) Verdict(false, "squares.py missing") else python(project, "checks.py") },
        reference = { project ->
            Files.writeString(project.resolve("squares.py"), "SQUARES = [\n" + (0..SQUARES_COUNT_LAST).joinToString("") { "    ${it * it},\n" } + "]\n")
            "Created squares.py with ${SQUARES_COUNT_LAST + 1} literal values; checks pass."
        },
    ),
)
