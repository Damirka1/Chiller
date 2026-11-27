package dev.chiller.evals

import dev.chiller.contracts.pngSize
import java.nio.file.Files
import java.nio.file.Path

/*
 * Tasks shaped like the user's own sessions with Gemma: single-file HTML apps on Tailwind, a game, a page around a
 * generated image, and a long page edited in several places. They catch what the Python tasks did not: pages cut off
 * by the reply limit, earlier features lost in a later turn, a large file rewritten instead of edited. The checks read
 * the page's structure and its scripts' syntax; nothing runs in a browser.
 */

private fun hasId(html: String, id: String) = Regex("""\bid\s*=\s*["']${Regex.escape(id)}["']""").containsMatchIn(html)
private fun defines(html: String, name: String) =
    Regex("""function\s+${Regex.escape(name)}\s*\(|\b${Regex.escape(name)}\s*=\s*(?:async\s+)?(?:function\b|\(|[A-Za-z_$][\w$]*\s*=>)""").containsMatchIn(html)

/** Reads [path] and checks it is a whole page with these ids; the first problem found, or null. */
private fun pageProblem(project: Path, path: String, ids: List<String>): String? {
    val file = project.resolve(path)
    if (!Files.isRegularFile(file)) return "$path missing"
    val html = Files.readString(file)
    htmlProblem(html)?.let { return "$path $it" }
    ids.firstOrNull { !hasId(html, it) }?.let { return "$path has no element with id=\"$it\"" }
    return null
}

private fun verdict(problem: String?, ok: String) = if (problem == null) Verdict(true, ok) else Verdict(false, problem)

private val WEATHER_IDS = listOf("city", "search", "current", "forecast")

private fun weatherProblem(project: Path, ids: List<String>): String? {
    pageProblem(project, "weather.html", ids)?.let { return it }
    val html = Files.readString(project.resolve("weather.html"))
    return when {
        "cdn.tailwindcss.com" !in html -> "weather.html does not load Tailwind from its CDN"
        !Regex("""\b(?:const|let|var)\s+SAMPLE_DATA\s*=""").containsMatchIn(html) -> "weather.html has no SAMPLE_DATA object"
        listOf("Almaty", "Astana", "Shymkent").any { it !in html } -> "SAMPLE_DATA lacks one of Almaty, Astana, Shymkent"
        !defines(html, "renderForecast") -> "weather.html does not define renderForecast"
        else -> null
    }
}

/** A reference page for each turn of the weather scenario; the model's page is checked by structure, not compared to it. */
private fun weatherPage(desktop: Boolean, units: Boolean): String = buildString {
    appendLine("<!DOCTYPE html>")
    appendLine("<html lang=\"ru\">")
    appendLine("<head>")
    appendLine("  <meta charset=\"utf-8\">")
    appendLine("  <title>Погода</title>")
    appendLine("  <script src=\"https://cdn.tailwindcss.com\"></script>")
    appendLine("</head>")
    appendLine("<body class=\"bg-slate-900 text-white\">")
    if (desktop) appendLine("  <div class=\"flex flex-col md:flex-row\">")
    if (desktop) appendLine("    <aside id=\"saved\" class=\"md:w-64\"><ul><li>Almaty</li><li>Astana</li><li>Shymkent</li></ul></aside>")
    appendLine("    <main class=\"flex-1 p-6\">")
    appendLine("      <input id=\"city\" value=\"Almaty\" class=\"text-black\"> <button id=\"search\">Найти</button>")
    if (units) appendLine("      <button id=\"unit-toggle\">°C / °F</button>")
    appendLine("      <section id=\"current\"></section>")
    appendLine("      <section id=\"forecast\" class=\"grid grid-cols-5 gap-2\"></section>")
    appendLine("    </main>")
    if (desktop) appendLine("  </div>")
    appendLine("  <script>")
    appendLine("    const SAMPLE_DATA = {")
    appendLine("      Almaty: { now: 12, days: [10, 11, 13, 9, 8] },")
    appendLine("      Astana: { now: -3, days: [-5, -2, 0, 1, -4] },")
    appendLine("      Shymkent: { now: 18, days: [17, 19, 20, 16, 15] },")
    appendLine("    };")
    if (units) {
        appendLine("    let fahrenheit = false;")
        appendLine("    function show(c) { return fahrenheit ? Math.round(c * 9 / 5 + 32) + '°F' : c + '°C'; }")
    } else appendLine("    function show(c) { return c + '°C'; }")
    appendLine("    let shown = SAMPLE_DATA.Almaty;")
    appendLine("    function renderForecast(data) {")
    appendLine("      shown = data;")
    appendLine("      document.getElementById('current').textContent = show(data.now);")
    appendLine("      document.getElementById('forecast').innerHTML = data.days.map(function (t) { return '<div class=\"day\">' + show(t) + '</div>'; }).join('');")
    appendLine("    }")
    appendLine("    document.getElementById('search').addEventListener('click', function () {")
    appendLine("      const data = SAMPLE_DATA[document.getElementById('city').value.trim()];")
    appendLine("      if (data) renderForecast(data);")
    appendLine("    });")
    if (units) appendLine("    document.getElementById('unit-toggle').addEventListener('click', function () { fahrenheit = !fahrenheit; renderForecast(shown); });")
    appendLine("    renderForecast(SAMPLE_DATA.Almaty);")
    appendLine("  </script>")
    appendLine("</body>")
    appendLine("</html>")
}

private const val PLAYER_ACCENT = "      --accent: #7c3aed;"
private const val PLAYER_NEXT = "        <button id=\"next\" class=\"ctrl\" title=\"Next\">⏭</button>"

/** A music player of about 850 lines, like the pages the user had Gemma write: long CSS, a long data array, a few functions. */
private val PLAYER_HTML: String = buildString {
    appendLine("<!DOCTYPE html>")
    appendLine("<html lang=\"en\">")
    appendLine("<head>")
    appendLine("  <meta charset=\"utf-8\">")
    appendLine("  <title>Pulse Player</title>")
    appendLine("  <style>")
    appendLine("    :root {")
    appendLine(PLAYER_ACCENT)
    appendLine("      --bg: #0f172a;")
    appendLine("      --text: #e2e8f0;")
    appendLine("    }")
    appendLine("    body { margin: 0; background: var(--bg); color: var(--text); font-family: system-ui, sans-serif; }")
    appendLine("    .ctrl { background: var(--accent); border: 0; border-radius: 999px; color: white; padding: 8px 14px; }")
    for (i in 1..240) appendLine("    .theme-$i .card { padding: ${i % 12 + 4}px; border-radius: ${i % 9 + 2}px; }")
    appendLine("  </style>")
    appendLine("</head>")
    appendLine("<body>")
    appendLine("  <header>")
    appendLine("    <h1 class=\"title\">Pulse Player</h1>")
    appendLine("  </header>")
    appendLine("  <main>")
    appendLine("    <section class=\"player\">")
    appendLine("      <div id=\"now\">Nothing playing</div>")
    appendLine("      <div class=\"controls\">")
    appendLine("        <button id=\"prev\" class=\"ctrl\" title=\"Previous\">⏮</button>")
    appendLine("        <button id=\"play\" class=\"ctrl\" title=\"Play\">▶</button>")
    appendLine(PLAYER_NEXT)
    appendLine("      </div>")
    appendLine("    </section>")
    appendLine("    <ul id=\"list\"></ul>")
    appendLine("  </main>")
    appendLine("  <script>")
    appendLine("    const tracks = [")
    for (i in 1..520) appendLine("      { title: 'Track %03d', artist: 'Artist %d', length: %d },".format(i, i % 37 + 1, 120 + i * 7 % 240))
    appendLine("    ];")
    appendLine("    let current = 0;")
    appendLine("    function formatTime(seconds) {")
    appendLine("      const m = Math.floor(seconds / 60);")
    appendLine("      const s = seconds % 60;")
    appendLine("      return m + ':' + (s < 10 ? '0' + s : s);")
    appendLine("    }")
    appendLine("    function renderList() {")
    appendLine("      const list = document.getElementById('list');")
    appendLine("      list.innerHTML = '';")
    appendLine("      tracks.forEach(function (track, index) {")
    appendLine("        const item = document.createElement('li');")
    appendLine("        item.textContent = track.title + ' — ' + track.artist + ' (' + formatTime(track.length) + ')';")
    appendLine("        item.addEventListener('click', function () { playTrack(index); });")
    appendLine("        list.appendChild(item);")
    appendLine("      });")
    appendLine("    }")
    appendLine("    function playTrack(index) {")
    appendLine("      current = (index + tracks.length) % tracks.length;")
    appendLine("      document.getElementById('now').textContent = 'Playing: ' + tracks[current].title;")
    appendLine("    }")
    appendLine("    document.getElementById('prev').addEventListener('click', function () { playTrack(current - 1); });")
    appendLine("    document.getElementById('play').addEventListener('click', function () { playTrack(current); });")
    appendLine("    document.getElementById('next').addEventListener('click', function () { playTrack(current + 1); });")
    appendLine("    renderList();")
    appendLine("  </script>")
    appendLine("</body>")
    appendLine("</html>")
}

/** Every line of the original page except those the task changes must still be there, in order and byte for byte. */
private fun playerProblem(project: Path): String? {
    pageProblem(project, "player.html", listOf("prev", "play", "next", "list", "shuffle"))?.let { return it }
    val html = Files.readString(project.resolve("player.html"))
    when {
        "--accent: #0ea5e9;" !in html || "#7c3aed" in html -> return "the accent colour is not #0ea5e9 everywhere"
        "Pulse Player" in html -> return "\"Pulse Player\" is still in the page"
        !Regex("""<title>\s*Chiller Player\s*</title>""").containsMatchIn(html) -> return "the <title> is not Chiller Player"
        !Regex("""<h1[^>]*>\s*Chiller Player\s*</h1>""").containsMatchIn(html) -> return "the <h1> is not Chiller Player"
        !defines(html, "shuffleTracks") -> return "player.html does not define shuffleTracks"
        Regex("""\bshuffleTracks\b""").findAll(html).count() < 2 -> return "shuffleTracks is defined but never called"
    }
    val changed = setOf(PLAYER_ACCENT, PLAYER_NEXT, "  <title>Pulse Player</title>", "    <h1 class=\"title\">Pulse Player</h1>")
    val kept = PLAYER_HTML.lines().filter { it !in changed && it.isNotBlank() }
    val actual = html.lines()
    var at = 0
    for ((index, line) in kept.withIndex()) {
        while (at < actual.size && actual[at] != line) at++
        if (at == actual.size) return "an unrelated line changed or went missing: \"${line.trim().take(80)}\" (original line ${PLAYER_HTML.lines().indexOf(line) + 1}, ${kept.size - index} kept lines left)"
        at++
    }
    return null
}

private fun snakePage(): String = """<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <title>Змейка</title>
  <style>
    body { background: #020617; color: #e2e8f0; display: flex; flex-direction: column; align-items: center; font-family: system-ui; }
    canvas { border-radius: 12px; box-shadow: 0 0 40px #22c55e55; }
  </style>
</head>
<body>
  <div>Счёт: <span id="score">0</span> · Рекорд: <span id="best">0</span></div>
  <canvas id="board" width="400" height="400"></canvas>
  <button id="restart">Заново</button>
  <script>
    const ctx = document.getElementById('board').getContext('2d');
    const size = 20;
    let snake, dir, food, score;
    let best = Number(localStorage.getItem('snake-best') || 0);
    document.getElementById('best').textContent = best;
    function reset() { snake = [{ x: 10, y: 10 }]; dir = { x: 1, y: 0 }; food = { x: 5, y: 5 }; score = 0; document.getElementById('score').textContent = 0; }
    function tick() {
      const head = { x: (snake[0].x + dir.x + size) % size, y: (snake[0].y + dir.y + size) % size };
      if (snake.some(function (p) { return p.x === head.x && p.y === head.y; })) { reset(); return; }
      snake.unshift(head);
      if (head.x === food.x && head.y === food.y) {
        score++;
        document.getElementById('score').textContent = score;
        if (score > best) { best = score; localStorage.setItem('snake-best', String(best)); document.getElementById('best').textContent = best; }
        food = { x: Math.floor(Math.random() * size), y: Math.floor(Math.random() * size) };
      } else snake.pop();
      ctx.fillStyle = '#020617'; ctx.fillRect(0, 0, 400, 400);
      ctx.fillStyle = '#ef4444'; ctx.fillRect(food.x * 20, food.y * 20, 18, 18);
      ctx.fillStyle = '#22c55e'; snake.forEach(function (p) { ctx.fillRect(p.x * 20, p.y * 20, 18, 18); });
    }
    document.addEventListener('keydown', function (e) {
      const turns = { ArrowUp: { x: 0, y: -1 }, ArrowDown: { x: 0, y: 1 }, ArrowLeft: { x: -1, y: 0 }, ArrowRight: { x: 1, y: 0 } };
      if (turns[e.key]) dir = turns[e.key];
    });
    document.getElementById('restart').addEventListener('click', reset);
    reset();
    setInterval(tick, 120);
  </script>
</body>
</html>
"""

private fun snakeProblem(project: Path): String? {
    pageProblem(project, "snake.html", listOf("board", "score", "best", "restart"))?.let { return it }
    val html = Files.readString(project.resolve("snake.html"))
    return when {
        !Regex("""<canvas\b[^>]*\bid\s*=\s*["']board["']""").containsMatchIn(html) -> "#board is not a canvas"
        "getContext" !in html -> "the canvas is never drawn on"
        "localStorage" !in html || "snake-best" !in html -> "the best score is not kept in localStorage under snake-best"
        "keydown" !in html || listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight").none { it in html } -> "no arrow-key control"
        listOf("requestAnimationFrame", "setInterval", "setTimeout").none { it in html } -> "the game never ticks"
        else -> null
    }
}

private fun imagePageProblem(project: Path): String? {
    val logo = project.resolve("assets/logo.png")
    val size = if (Files.isRegularFile(logo)) pngSize(Files.readAllBytes(logo)) else null
    if (size == null) return "assets/logo.png is missing or not a PNG"
    if (size.first < 256 || size.second < 256) return "the logo is only ${size.first}x${size.second}"
    pageProblem(project, "index.html", listOf("play"))?.let { return it }
    val html = Files.readString(project.resolve("index.html"))
    return when {
        !Regex("""<img\b[^>]*\bsrc\s*=\s*["'](?:\./)?assets/logo\.png["']""").containsMatchIn(html) -> "index.html does not show assets/logo.png in an <img>"
        "Pixel Quest" !in html -> "index.html has no \"Pixel Quest\" title"
        else -> null
    }
}

internal val APP_EVAL_TASKS: List<EvalTask> = listOf(
    EvalTask(
        id = "app-weather-turns",
        title = "Приложение погоды на Tailwind в три хода, как в живой сессии",
        prompt = "Давай weather app. Сделай его на html и tailwind в одном файле weather.html, максимально красиво. Ключа к API нет, поэтому возьми образцы данных " +
            "в объекте SAMPLE_DATA для городов Almaty, Astana и Shymkent: текущая температура и прогноз на 5 дней. Для проверки нужны: поле ввода id=\"city\", " +
            "кнопка id=\"search\", блок текущей погоды id=\"current\", блок прогноза id=\"forecast\" и функция renderForecast(data), которая их заполняет. " +
            "Tailwind подключи с cdn.tailwindcss.com. Покажи мне файл, когда закончишь.",
        files = mapOf("README.md" to "# Weather\n\nA single-page weather app. No API key yet: use sample data.\n"),
        check = { project, _ -> verdict(weatherProblem(project, WEATHER_IDS), "weather.html is a whole page with the search, SAMPLE_DATA and renderForecast") },
        reference = { project -> Files.writeString(project.resolve("weather.html"), weatherPage(desktop = false, units = false)); "Создал weather.html." },
        followUps = listOf(
            EvalTurn(
                prompt = "Норм, только ты сделал дизайн для мобилки, а можно для десктопа? Слева панель со списком сохранённых городов (id=\"saved\"), справа основная часть; " +
                    "на узком экране панель уходит вниз или наверх. Всё, что уже работает, должно остаться.",
                check = { project, _ ->
                    val problem = weatherProblem(project, WEATHER_IDS + "saved") ?: Files.readString(project.resolve("weather.html")).let { html ->
                        if (!Regex("""\b(?:sm|md|lg|xl):[a-z]""").containsMatchIn(html)) "weather.html has no responsive md:/lg: classes" else null
                    }
                    verdict(problem, "the desktop layout has the saved-cities panel and keeps the search and forecast")
                },
                reference = { project -> Files.writeString(project.resolve("weather.html"), weatherPage(desktop = true, units = false)); "Сделал десктопную раскладку." },
            ),
            EvalTurn(
                prompt = "Добавь переключатель °C/°F (id=\"unit-toggle\"): по нажатию все температуры на странице пересчитываются. Ничего из сделанного раньше не ломай.",
                check = { project, _ ->
                    val problem = weatherProblem(project, WEATHER_IDS + listOf("saved", "unit-toggle")) ?: Files.readString(project.resolve("weather.html")).let { html ->
                        if (!Regex("""9\s*/\s*5|1\.8|5\s*/\s*9""").containsMatchIn(html)) "weather.html never converts between °C and °F" else null
                    }
                    verdict(problem, "the unit toggle converts and every earlier part is still there")
                },
                reference = { project -> Files.writeString(project.resolve("weather.html"), weatherPage(desktop = true, units = true)); "Добавил переключатель °C/°F." },
            ),
        ),
    ),
    EvalTask(
        id = "app-player-edits",
        title = "Три правки в большой странице плеера без порчи остального",
        prompt = "В player.html три правки: 1) акцентный цвет --accent поменяй на #0ea5e9; 2) название «Pulse Player» в <title> и в заголовке h1 замени на «Chiller Player»; " +
            "3) рядом с кнопкой next добавь кнопку перемешивания с id=\"shuffle\" и функцию shuffleTracks(), которая перемешивает массив tracks и перерисовывает список, " +
            "кнопка должна её вызывать. Остальной файл не трогай.",
        files = mapOf("player.html" to PLAYER_HTML),
        check = { project, _ -> verdict(playerProblem(project), "the three changes are made and every other line is unchanged") },
        reference = { project ->
            replaceIn(project, "player.html", PLAYER_ACCENT, "      --accent: #0ea5e9;")
            replaceIn(project, "player.html", "<title>Pulse Player</title>", "<title>Chiller Player</title>")
            replaceIn(project, "player.html", "<h1 class=\"title\">Pulse Player</h1>", "<h1 class=\"title\">Chiller Player</h1>")
            replaceIn(project, "player.html", PLAYER_NEXT, PLAYER_NEXT + "\n        <button id=\"shuffle\" class=\"ctrl\" title=\"Shuffle\">🔀</button>")
            replaceIn(project, "player.html", "    renderList();\n  </script>",
                "    function shuffleTracks() {\n      for (let i = tracks.length - 1; i > 0; i--) {\n        const j = Math.floor(Math.random() * (i + 1));\n" +
                    "        const t = tracks[i]; tracks[i] = tracks[j]; tracks[j] = t;\n      }\n      renderList();\n    }\n" +
                    "    document.getElementById('shuffle').addEventListener('click', shuffleTracks);\n    renderList();\n  </script>")
            "Сделал три правки в player.html."
        },
    ),
    EvalTask(
        id = "app-snake-game",
        title = "Змейка на canvas с рекордом",
        prompt = "Ок, змейку сделаешь? Максимально красивую, в одном файле snake.html. Для проверки: canvas с id=\"board\", счёт в элементе id=\"score\", " +
            "рекорд хранится в localStorage под ключом snake-best и показывается в id=\"best\", кнопка id=\"restart\" начинает заново, управление стрелками. " +
            "Покажи мне файл, когда закончишь.",
        files = mapOf("README.md" to "# Games\n\nSmall browser games, one file each.\n"),
        check = { project, _ -> verdict(snakeProblem(project), "snake.html is a whole game: canvas, score, best score in localStorage, arrows, restart") },
        reference = { project -> Files.writeString(project.resolve("snake.html"), snakePage()); "Сделал snake.html." },
    ),
    EvalTask(
        id = "app-image-page",
        title = "Пиксель-арт логотип и стартовая страница с ним",
        prompt = "Нарисуй пиксель-арт логотип для моей игры Pixel Quest и сохрани в assets/logo.png. Потом сделай index.html — стартовый экран игры в том же пиксельном стиле: " +
            "логотип крупно по центру, заголовок «Pixel Quest» и кнопка «Играть» с id=\"play\". Покажи мне страницу.",
        files = mapOf("README.md" to "# Pixel Quest\n\nA tiny browser game. The start screen goes to index.html.\n"),
        check = { project, _ -> verdict(imagePageProblem(project), "the logo is a PNG and index.html shows it with the title and the play button") },
        reference = { project ->
            Files.createDirectories(project.resolve("assets"))
            Files.write(project.resolve("assets/logo.png"), REFERENCE_PNG)
            Files.writeString(project.resolve("index.html"), "<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\"><title>Pixel Quest</title></head>\n<body>\n" +
                "  <img src=\"assets/logo.png\" alt=\"Pixel Quest\">\n  <h1>Pixel Quest</h1>\n  <button id=\"play\">Играть</button>\n</body>\n</html>\n")
            "Нарисовал логотип и сделал index.html."
        },
        requiredTools = setOf("generate_image"),
    ),
)
