package dev.chiller.evals

import dev.chiller.contracts.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path

/** The desktop app's default round limit; these tasks need more steps than the suite's 14. */
private const val HARD_MAX_ROUNDS = 24

/** Visible checks first, then cases the model never saw; both must pass. */
private fun bothPass(project: Path, hidden: String): Verdict {
    val visible = python(project, "checks.py")
    if (!visible.passed) return visible
    // Windows drops double quotes from a -c argument, so the script runs from a file outside the project; -c keeps the
    // project folder on the import path.
    val script = Files.createTempFile("chiller-hidden-", ".py")
    val unseen = try {
        Files.writeString(script, hidden)
        python(project, "-c", "exec(compile(open(r'${script.toAbsolutePath()}', encoding='utf-8').read(), 'hidden_checks.py', 'exec'))")
    } finally { Files.deleteIfExists(script) }
    // The detail names the script's exit and output, not the script itself.
    return if (unseen.passed) Verdict(true, "${visible.detail}; hidden cases pass") else Verdict(false, "hidden cases → ${unseen.detail.substringAfterLast(" → ")}")
}

private const val WH_ERRORS = """class WarehouseError(Exception):
    '''Base class for domain errors.'''


class UnknownSku(WarehouseError):
    def __init__(self, sku):
        super().__init__(f"unknown SKU: {sku}")
        self.sku = sku


class InvalidQuantity(WarehouseError):
    def __init__(self, qty):
        super().__init__(f"quantity must be a positive integer, got {qty!r}")
        self.qty = qty
"""

private const val WH_MODELS = """from dataclasses import dataclass


@dataclass
class Item:
    sku: str
    name: str
    on_hand: int = 0

    def to_dict(self):
        return {"sku": self.sku, "name": self.name, "on_hand": self.on_hand}

    @classmethod
    def from_dict(cls, data):
        return cls(sku=data["sku"], name=data["name"], on_hand=int(data["on_hand"]))
"""

private const val WH_STORAGE = """import json
import os

from warehouse.models import Item


def load(path):
    '''Read items from a JSON file; a missing file is an empty warehouse.'''
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    return {entry["sku"]: Item.from_dict(entry) for entry in raw["items"]}


def save(path, items):
    data = {"version": 1, "items": [item.to_dict() for item in sorted(items.values(), key=lambda i: i.sku)]}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
"""

private const val WH_SERVICE = """from warehouse import storage
from warehouse.errors import InvalidQuantity, UnknownSku
from warehouse.models import Item


def _check_qty(qty):
    if not isinstance(qty, int) or isinstance(qty, bool) or qty <= 0:
        raise InvalidQuantity(qty)


class Warehouse:
    def __init__(self, path):
        self.path = path
        self.items = storage.load(path)

    def add_item(self, sku, name):
        self.items.setdefault(sku, Item(sku, name))
        return self.items[sku]

    def _item(self, sku):
        try:
            return self.items[sku]
        except KeyError:
            raise UnknownSku(sku) from None

    def receive(self, sku, qty):
        _check_qty(qty)
        self._item(sku).on_hand += qty

    def ship(self, sku, qty):
        _check_qty(qty)
        item = self._item(sku)
        if qty > item.on_hand:
            raise ValueError("not enough stock")
        item.on_hand -= qty

    def save(self):
        storage.save(self.path, self.items)
"""

private const val WH_REPORT = """def stock_line(item):
    return f"{item.sku:<8} {item.name:<20} on hand {item.on_hand:>5}"


def stock_report(items):
    return "\n".join(stock_line(item) for item in sorted(items.values(), key=lambda i: i.sku))
"""

private const val WH_CLI = """import sys

from warehouse.errors import WarehouseError
from warehouse.report import stock_report
from warehouse.service import Warehouse


def main(argv, path="stock.json", out=sys.stdout):
    wh = Warehouse(path)
    command, *args = argv
    try:
        if command == "add":
            wh.add_item(args[0], args[1])
        elif command == "receive":
            wh.receive(args[0], int(args[1]))
        elif command == "ship":
            wh.ship(args[0], int(args[1]))
        elif command == "report":
            print(stock_report(wh.items), file=out)
            return 0
        else:
            print(f"unknown command: {command}", file=out)
            return 2
    except WarehouseError as error:
        print(f"error: {error}", file=out)
        return 1
    wh.save()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
"""

private const val WH_SPEC = """# Reservations

Orders reserve stock first and ship it later. Implement reservations across the warehouse package.

1. `Item` gets a `reserved` count (default 0) and an `available` property: `on_hand - reserved`.
2. `Warehouse.reserve(sku, qty)` sets stock aside; `on_hand` does not change. Reserving more than `available`
   raises `InsufficientStock`.
3. `Warehouse.release(sku, qty)` gives reserved stock back. Releasing more than is reserved raises `NotReserved`.
4. `Warehouse.ship(sku, qty)` ships only unreserved stock: more than `available` raises `InsufficientStock`
   (instead of the current ValueError).
5. Both new errors live in warehouse/errors.py and subclass `WarehouseError`:
   - `InsufficientStock(sku, requested, available)` with the message
     `insufficient stock for {sku}: requested {requested}, available {available}`
   - `NotReserved(sku, requested, reserved)` with the message `cannot release {requested} of {sku}: only {reserved} reserved`
   Quantities are validated as before (`InvalidQuantity`), and unknown SKUs raise `UnknownSku`.
6. Storage writes `"version": 2` and each item's `reserved`. Files written by the old version (`"version": 1`,
   no `reserved` key) must still load, with nothing reserved.
7. The stock report line becomes
   `f"{sku:<8} {name:<20} on hand {on_hand:>5} reserved {reserved:>5} available {available:>5}"`.
8. The CLI gets `reserve SKU QTY` and `release SKU QTY` commands that behave like `receive` and `ship`:
   errors print `error: <message>` and return 1.
"""

private const val WH_CHECKS = """import json
import os
import tempfile

from warehouse.errors import InsufficientStock, NotReserved, WarehouseError
from warehouse.service import Warehouse

path = os.path.join(tempfile.mkdtemp(), "stock.json")
wh = Warehouse(path)
wh.add_item("B-1", "Bolts")
wh.receive("B-1", 10)
wh.reserve("B-1", 4)
item = wh.items["B-1"]
assert item.on_hand == 10 and item.reserved == 4 and item.available == 6, item
try:
    wh.reserve("B-1", 7)
    raise SystemExit("reserving more than available must fail")
except InsufficientStock as error:
    assert str(error) == "insufficient stock for B-1: requested 7, available 6", str(error)
    assert isinstance(error, WarehouseError)
wh.release("B-1", 1)
assert item.reserved == 3
try:
    wh.release("B-1", 5)
    raise SystemExit("releasing more than reserved must fail")
except NotReserved as error:
    assert str(error) == "cannot release 5 of B-1: only 3 reserved", str(error)
try:
    wh.ship("B-1", 8)
    raise SystemExit("shipping reserved stock must fail")
except InsufficientStock:
    pass
wh.ship("B-1", 7)
assert item.on_hand == 3 and item.available == 0
wh.save()
with open(path, encoding="utf-8") as f:
    saved = json.load(f)
assert saved["version"] == 2 and saved["items"][0]["reserved"] == 3, saved
assert Warehouse(path).items["B-1"].reserved == 3
print("reservations ok")
"""

private const val WH_HIDDEN = """import io, json, os, tempfile
from warehouse import cli
from warehouse.errors import InvalidQuantity, UnknownSku
from warehouse.report import stock_line
from warehouse.service import Warehouse
tmp = tempfile.mkdtemp()
old = os.path.join(tmp, "old.json")
with open(old, "w", encoding="utf-8") as f:
    json.dump({"version": 1, "items": [{"sku": "N-7", "name": "Nuts", "on_hand": 5}]}, f)
wh = Warehouse(old)
assert wh.items["N-7"].reserved == 0 and wh.items["N-7"].available == 5, "old file must load with nothing reserved"
wh.reserve("N-7", 2)
expected = f"{'N-7':<8} {'Nuts':<20} on hand {5:>5} reserved {2:>5} available {3:>5}"
assert stock_line(wh.items["N-7"]) == expected, repr(stock_line(wh.items["N-7"]))
for bad in (0, -1, "2"):
    try:
        wh.reserve("N-7", bad)
        raise SystemExit("reserve accepted quantity %r" % (bad,))
    except InvalidQuantity:
        pass
try:
    wh.release("X", 1)
    raise SystemExit("release accepted an unknown SKU")
except UnknownSku:
    pass
path = os.path.join(tmp, "cli.json")
out = io.StringIO()
assert cli.main(["add", "W-1", "Washers"], path=path, out=out) == 0
assert cli.main(["receive", "W-1", "3"], path=path, out=out) == 0
assert cli.main(["reserve", "W-1", "2"], path=path, out=out) == 0, out.getvalue()
assert Warehouse(path).items["W-1"].reserved == 2, "cli reserve must be saved"
out = io.StringIO()
assert cli.main(["reserve", "W-1", "5"], path=path, out=out) == 1
assert out.getvalue().strip() == "error: insufficient stock for W-1: requested 5, available 1", out.getvalue()
assert cli.main(["release", "W-1", "2"], path=path, out=out) == 0
assert Warehouse(path).items["W-1"].reserved == 0
print("hidden reservation checks ok")
"""

private fun reservationsTask(): EvalTask = EvalTask(
    id = "hard-reservations",
    title = "Фича через шесть модулей по спецификации",
    prompt = "Implement the reservations feature described in SPEC.md in the warehouse package. It touches several modules. " +
        "Run `python3 checks.py` until it passes; it does not cover every point of the spec, so check the spec yourself.",
    files = mapOf(
        "warehouse/__init__.py" to "",
        "warehouse/errors.py" to WH_ERRORS,
        "warehouse/models.py" to WH_MODELS,
        "warehouse/storage.py" to WH_STORAGE,
        "warehouse/service.py" to WH_SERVICE,
        "warehouse/report.py" to WH_REPORT,
        "warehouse/cli.py" to WH_CLI,
        "SPEC.md" to WH_SPEC,
        "checks.py" to WH_CHECKS,
    ),
    check = { project, _ -> bothPass(project, WH_HIDDEN) },
    reference = { project ->
        Files.writeString(project.resolve("warehouse/errors.py"), WH_ERRORS + """

class InsufficientStock(WarehouseError):
    def __init__(self, sku, requested, available):
        super().__init__(f"insufficient stock for {sku}: requested {requested}, available {available}")
        self.sku, self.requested, self.available = sku, requested, available


class NotReserved(WarehouseError):
    def __init__(self, sku, requested, reserved):
        super().__init__(f"cannot release {requested} of {sku}: only {reserved} reserved")
        self.sku, self.requested, self.reserved = sku, requested, reserved
""")
        Files.writeString(project.resolve("warehouse/models.py"), WH_MODELS
            .replace("    on_hand: int = 0\n", "    on_hand: int = 0\n    reserved: int = 0\n\n    @property\n    def available(self):\n        return self.on_hand - self.reserved\n")
            .replace("\"on_hand\": self.on_hand}", "\"on_hand\": self.on_hand, \"reserved\": self.reserved}")
            .replace("on_hand=int(data[\"on_hand\"]))", "on_hand=int(data[\"on_hand\"]), reserved=int(data.get(\"reserved\", 0)))"))
        replaceIn(project, "warehouse/storage.py", "\"version\": 1", "\"version\": 2")
        Files.writeString(project.resolve("warehouse/service.py"), WH_SERVICE
            .replace("from warehouse.errors import InvalidQuantity, UnknownSku", "from warehouse.errors import InsufficientStock, InvalidQuantity, NotReserved, UnknownSku")
            .replace("        if qty > item.on_hand:\n            raise ValueError(\"not enough stock\")\n",
                "        if qty > item.available:\n            raise InsufficientStock(sku, qty, item.available)\n")
            .replace("    def save(self):", """    def reserve(self, sku, qty):
        _check_qty(qty)
        item = self._item(sku)
        if qty > item.available:
            raise InsufficientStock(sku, qty, item.available)
        item.reserved += qty

    def release(self, sku, qty):
        _check_qty(qty)
        item = self._item(sku)
        if qty > item.reserved:
            raise NotReserved(sku, qty, item.reserved)
        item.reserved -= qty

    def save(self):"""))
        replaceIn(project, "warehouse/report.py", "on hand {item.on_hand:>5}\"", "on hand {item.on_hand:>5} reserved {item.reserved:>5} available {item.available:>5}\"")
        replaceIn(project, "warehouse/cli.py", "        elif command == \"report\":",
            "        elif command == \"reserve\":\n            wh.reserve(args[0], int(args[1]))\n        elif command == \"release\":\n            wh.release(args[0], int(args[1]))\n        elif command == \"report\":")
        "Implemented reservations in errors, models, storage, service, report and cli; checks pass."
    },
    maxRounds = HARD_MAX_ROUNDS,
)

private const val SHOP_MONEY = """'''Money helpers. All amounts are Decimal; never use float for money.'''
from decimal import Decimal, ROUND_HALF_UP

CENT = Decimal("0.01")


def to_money(value):
    '''Convert an int, str or Decimal to a Decimal amount.'''
    if isinstance(value, float):
        raise TypeError("use str or Decimal for money, not float")
    return Decimal(value)


def round_money(amount):
    '''Round to whole cents, halves away from zero (commercial rounding).'''
    return amount.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)


def percent(amount, rate_percent):
    '''rate_percent is a number of percent, e.g. 7.5 for 7.5%.'''
    return amount * to_money(str(rate_percent)) / Decimal(100)


def format_money(amount, currency="EUR"):
    return f"{round_money(amount):.2f} {currency}"
"""

private const val SHOP_DISCOUNTS = """'''Discount rules, applied in this order: the bulk discount per line, then the coupon on the discounted subtotal.'''
from shop.money import percent, round_money, to_money

BULK_MIN_QTY = 10
BULK_RATE = 5  # percent off a line


COUPONS = {
    "SPRING10": 10,
    "VIP15": 15,
}


def bulk_discount(qty, line_total):
    '''Lines with BULK_MIN_QTY or more units get BULK_RATE percent off.'''
    if qty > BULK_MIN_QTY:
        return round_money(percent(line_total, BULK_RATE))
    return to_money(0)


def coupon_discount(code, subtotal):
    '''Coupon codes are case-insensitive. An unknown code is an error, not a silent zero.'''
    if code is None:
        return to_money(0)
    try:
        rate = COUPONS[code.upper()]
    except KeyError:
        raise ValueError(f"unknown coupon: {code}") from None
    return round_money(percent(subtotal, rate))
"""

private const val SHOP_LEGACY = """# Old float-based helpers from the 2019 checkout. Unused; kept for the archive importer. Do not edit.


def round_money(x):
    return round(x, 2)


def bulk_discount(qty, total):
    return total * 0.05 if qty > 10 else 0.0
"""

private const val SHOP_CHECKS = """from decimal import Decimal as D

from shop.order import price_order

r = price_order([("BRACKET", 3), ("HINGE", 2)], "DE")
assert (r["subtotal"], r["vat"], r["total"]) == (D("19.95"), D("3.79"), D("23.74")), r
r = price_order([("BOLT", 10), ("NUT", 4)], "KZ")
assert (r["subtotal"], r["vat"], r["total"]) == (D("3.80"), D("0.46"), D("4.26")), r
r = price_order([("BRACKET", 2), ("WASHER", 12)], "FR", coupon="spring10")
assert (r["subtotal"], r["discount"], r["vat"], r["total"]) == (D("10.55"), D("1.06"), D("1.90"), D("11.39")), r
print("pricing ok")
"""

private const val SHOP_HIDDEN = """from decimal import Decimal as D
from shop import discounts
from shop.money import format_money
from shop.order import price_order
assert discounts.BULK_MIN_QTY == 10 and discounts.BULK_RATE == 5, "bulk rule constants must stay as documented"
cases = [
    (([("BOLT", 9)], "DE", None), ("3.15", "0", "0.60", "3.75")),
    (([("BOLT", 11), ("HINGE", 1)], "KZ", "VIP15"), ("6.15", "0.92", "0.63", "5.86")),
    (([("NUT", 10)], "FR", None), ("1.14", "0", "0.23", "1.37")),
    (([("HINGE", 3)], "KZ", "SPRING10"), ("7.47", "0.75", "0.81", "7.53")),
]
for (lines, country, coupon), expected in cases:
    r = price_order(lines, country, coupon)
    got = (r["subtotal"], r["discount"], r["vat"], r["total"])
    assert got == tuple(D(x) for x in expected), (lines, country, coupon, got)
assert format_money(D("2.345")) == "2.35 EUR", format_money(D("2.345"))
print("hidden pricing cases ok")
"""

private fun pricingBugsTask(): EvalTask = EvalTask(
    id = "hard-pricing-bugs",
    title = "Два связанных бага в расчёте цены среди приманок",
    prompt = "`python3 checks.py` fails. Find the root causes in the shop package and fix them. The documented rules in the code are correct. " +
        "Do not change legacy/ or checks.py, and do not special-case the checked orders.",
    files = mapOf(
        "shop/__init__.py" to "",
        "shop/money.py" to SHOP_MONEY,
        "shop/catalog.py" to """from shop.money import to_money

# Unit prices in EUR.
PRICES = {
    "BOLT": to_money("0.35"),
    "NUT": to_money("0.12"),
    "WASHER": to_money("0.05"),
    "BRACKET": to_money("4.99"),
    "HINGE": to_money("2.49"),
}


def unit_price(sku):
    try:
        return PRICES[sku]
    except KeyError:
        raise ValueError(f"unknown product: {sku}") from None
""",
        "shop/discounts.py" to SHOP_DISCOUNTS,
        "shop/tax.py" to """from shop.money import percent, round_money

VAT_PERCENT = {"KZ": 12, "DE": 19, "FR": 20}


def vat(amount, country):
    '''VAT on the net amount, rounded to cents.'''
    try:
        rate = VAT_PERCENT[country]
    except KeyError:
        raise ValueError(f"no VAT rate for {country}") from None
    return round_money(percent(amount, rate))
""",
        "shop/order.py" to """from shop.catalog import unit_price
from shop.discounts import bulk_discount, coupon_discount
from shop.money import round_money, to_money
from shop.tax import vat


def price_order(lines, country, coupon=None):
    '''lines: list of (sku, qty). Returns subtotal (after bulk discounts), coupon discount, VAT and total.'''
    subtotal = to_money(0)
    for sku, qty in lines:
        line_total = unit_price(sku) * qty
        subtotal += line_total - bulk_discount(qty, line_total)
    discount = coupon_discount(coupon, subtotal)
    net = subtotal - discount
    tax = vat(net, country)
    return {"subtotal": round_money(subtotal), "discount": discount, "vat": tax, "total": round_money(net + tax)}
""",
        "legacy/money_v1.py" to SHOP_LEGACY,
        "checks.py" to SHOP_CHECKS,
    ),
    check = { project, _ ->
        if (Files.readString(project.resolve("legacy/money_v1.py")) != SHOP_LEGACY) Verdict(false, "legacy/money_v1.py was changed")
        else bothPass(project, SHOP_HIDDEN)
    },
    reference = { project ->
        replaceIn(project, "shop/money.py", "amount.quantize(Decimal(\"0.1\")", "amount.quantize(CENT")
        replaceIn(project, "shop/discounts.py", "if qty > BULK_MIN_QTY:", "if qty >= BULK_MIN_QTY:")
        "Two root causes: round_money rounded to 0.1 instead of cents, and the bulk discount skipped exactly BULK_MIN_QTY units."
    },
    maxRounds = HARD_MAX_ROUNDS,
)

/** Eight differently written dd.mm.yyyy formats, and what each becomes once it goes through format_date. */
private val DATE_SLOTS = listOf(
    "{start.strftime('%d.%m.%Y')}" to "{format_date(start)}",
    "{row['day'].strftime('%d.%m.%Y')}" to "{format_date(row['day'])}",
    "when.strftime(\"%d.%m.%Y\")" to "format_date(when)",
    "{due:%d.%m.%Y}" to "{format_date(due)}",
    "\"%02d.%02d.%04d\" % (d.day, d.month, d.year)" to "format_date(d)",
    "{first.strftime('%d.%m.%Y')} - {datetime.date.strftime(last, '%d.%m.%Y')}" to "{format_date(first)} - {format_date(last)}",
    "\"{:%d.%m.%Y}\".format(created)" to "format_date(created)",
    "\".\".join([f\"{d.day:02d}\", f\"{d.month:02d}\", str(d.year)])" to "format_date(d)",
)

private val DATE_REPORTS = listOf(
    "def invoice_header(number, start, customer):\n    return f\"Invoice {number} for {customer}, issued @@\"\n",
    "def payment_lines(rows):\n    lines = []\n    for row in rows:\n        lines.append(f\"{row['amount']:>8.2f} paid on @@\")\n    return \"\\n\".join(lines)\n",
    "def delivery_note(when, address):\n    return \"Delivery to \" + address + \" on \" + @@\n",
    "def due_reminder(invoice, due):\n    days = (due - datetime.date(due.year, 1, 1)).days + 1\n    return f\"Invoice {invoice} is due @@ (day {days} of the year)\"\n",
    "def audit_entry(user, d):\n    return \"[\" + @@ + \"] \" + user\n",
    "def period_summary(first, last, total):\n    return f\"Period @@: total {total:.2f}\"\n",
    "def contract_footer(created, owner):\n    return \"Contract created \" + @@ + \" by \" + owner\n",
    "def archive_label(d, box):\n    return f\"Box {box:03d} / \" + @@\n",
)

/** About 450 lines: the eight reports spread among table helpers that have nothing to do with dates. */
private fun reportBuilder(iso: Boolean): String = buildString {
    appendLine("'''Text reports for the back office. Each report function returns a string.'''")
    appendLine("import datetime")
    if (iso) appendLine("\nfrom reports.dates import format_date")
    appendLine()
    val areas = listOf("invoice", "payment", "delivery", "reminder", "audit", "period", "contract", "archive")
    DATE_REPORTS.forEachIndexed { index, report ->
        repeat(9) { k ->
            val n = index * 9 + k + 1
            appendLine()
            appendLine("def _column_width_$n(values):")
            appendLine("    '''Width of column $n in the ${areas[index]} table.'''")
            appendLine("    widths = [len(str(value)) for value in values]")
            appendLine("    return max(widths, default=${n % 7 + 1})")
            appendLine()
        }
        val (original, isoForm) = DATE_SLOTS[index]
        appendLine()
        append(report.replace("@@", if (iso) isoForm else original))
        appendLine()
    }
}

private const val DATES_CHECKS = """import datetime

from reports.builder import (archive_label, audit_entry, contract_footer, delivery_note, due_reminder, invoice_header,
                             payment_lines, period_summary)
from reports.dates import format_date

D = datetime.date
assert format_date(D(2026, 1, 2)) == "2026-01-02"
assert invoice_header(17, D(2026, 3, 5), "ACME") == "Invoice 17 for ACME, issued 2026-03-05"
assert payment_lines([{"amount": 12.5, "day": D(2026, 1, 9)}, {"amount": 100, "day": D(2026, 12, 31)}]) == \
    "   12.50 paid on 2026-01-09\n  100.00 paid on 2026-12-31"
assert delivery_note(D(2026, 7, 1), "Main st 1") == "Delivery to Main st 1 on 2026-07-01"
assert due_reminder("A-1", D(2026, 2, 10)) == "Invoice A-1 is due 2026-02-10 (day 41 of the year)"
assert audit_entry("root", D(2026, 11, 2)) == "[2026-11-02] root"
assert period_summary(D(2026, 1, 1), D(2026, 3, 31), 1234.5) == "Period 2026-01-01 - 2026-03-31: total 1234.50"
assert contract_footer(D(2025, 12, 24), "Dana") == "Contract created 2025-12-24 by Dana"
assert archive_label(D(2026, 4, 30), 7) == "Box 007 / 2026-04-30"
print("iso reports ok")
"""

/** Every date must come from format_date: with it replaced by a marker, no formatted date may remain. */
private const val DATES_HIDDEN = """import datetime, re
import reports.builder as b
import reports.dates as dates
marker = lambda value: "<D>"
dates.format_date = marker
if hasattr(b, "format_date"):
    b.format_date = marker
D = datetime.date(2026, 5, 6)
outputs = [b.invoice_header(1, D, "x"), b.payment_lines([{"amount": 1, "day": D}]), b.delivery_note(D, "x"), b.due_reminder("x", D),
           b.audit_entry("x", D), b.period_summary(D, D, 1), b.contract_footer(D, "x"), b.archive_label(D, 1)]
for text in outputs:
    assert re.search(r"\d{2}\.\d{2}\.\d{4}|\d{4}-\d{2}-\d{2}", text) is None and "<D>" in text, "not formatted through format_date: " + text
print("hidden: every report uses format_date")
"""

private fun isoDatesTask(): EvalTask = EvalTask(
    id = "hard-iso-dates",
    title = "Восемь разных форматов дат в большом файле через один помощник",
    prompt = "Reports must show ISO 8601 dates (YYYY-MM-DD). Create reports/dates.py with format_date(value) that returns that format, " +
        "and make every report function in reports/builder.py format its dates only through format_date. The dates are written in several different ways. " +
        "Keep everything else in the reports unchanged. Run `python3 checks.py`.",
    files = mapOf(
        "reports/__init__.py" to "",
        "reports/builder.py" to reportBuilder(iso = false),
        "checks.py" to DATES_CHECKS,
    ),
    check = { project, _ -> bothPass(project, DATES_HIDDEN) },
    reference = { project ->
        Files.writeString(project.resolve("reports/dates.py"), "def format_date(value):\n    return value.strftime('%Y-%m-%d')\n")
        Files.writeString(project.resolve("reports/builder.py"), reportBuilder(iso = true))
        "Added reports/dates.py with format_date and routed all eight report dates through it."
    },
    maxRounds = HARD_MAX_ROUNDS,
)

private const val DURATIONS_SOURCE = """'''Durations as compact text, e.g. "1h30m".

parse_duration(text) -> int seconds
- text is one or more <number><unit> parts, optionally separated by spaces: "1h30m", "1h 30m", "45s", "2d".
- units: d = 86400, h = 3600, m = 60, s = 1 seconds; units are case-insensitive.
- each unit may appear at most once, in the order d, h, m, s.
- numbers are non-negative integers ("1.5h" is invalid).
- a bare integer with no unit at all means seconds: "90" -> 90.
- leading and trailing spaces are ignored; empty or blank text is invalid.
- invalid text raises ValueError("invalid duration: " + repr(text)), with text exactly as given.

format_duration(seconds) -> str
- the inverse: largest unit first, zero parts left out, no spaces: 5400 -> "1h30m", 90061 -> "1d1h1m1s".
- 0 -> "0s".
- negative seconds raise ValueError("negative duration: <seconds>"), e.g. "negative duration: -5".
'''


def parse_duration(text):
    raise NotImplementedError


def format_duration(seconds):
    raise NotImplementedError
"""

private const val DURATIONS_HIDDEN = """from durations import format_duration, parse_duration
for text, expected in {"1H30M": 5400, "1h 30m": 5400, " 2d ": 172800, "90": 90, "1d1h1m1s": 90061, "0s": 0, "10m5s": 605, "1D 2H": 93600}.items():
    got = parse_duration(text)
    assert got == expected, f"parse_duration({text!r}) = {got!r}, expected {expected}"
for text in ["", "   ", "30m1h", "1h1h", "1.5h", "5x", "h", "-5s", "1h-30m"]:
    try:
        got = parse_duration(text)
    except ValueError as error:
        assert str(error) == "invalid duration: " + repr(text), f"message for {text!r}: {error}"
    else:
        raise AssertionError(f"parse_duration({text!r}) returned {got!r}, expected ValueError")
for seconds, expected in {3600: "1h", 172805: "2d5s", 59: "59s", 61: "1m1s"}.items():
    assert format_duration(seconds) == expected, f"format_duration({seconds}) = {format_duration(seconds)!r}"
try:
    format_duration(-5)
    raise AssertionError("format_duration(-5) must raise ValueError")
except ValueError as error:
    assert str(error) == "negative duration: -5", str(error)
for seconds in [1, 59, 60, 3599, 3600, 86399, 86400, 100000]:
    assert parse_duration(format_duration(seconds)) == seconds, seconds
print("hidden duration cases ok")
"""

private fun durationSpecTask(): EvalTask = EvalTask(
    id = "hard-duration-spec",
    title = "Реализация по спецификации, проверка скрытыми случаями",
    prompt = "Implement parse_duration and format_duration in durations.py exactly as its docstring specifies. " +
        "checks.py has only a few examples; the implementation will also be checked against every rule in the docstring. Run `python3 checks.py`.",
    files = mapOf(
        "durations.py" to DURATIONS_SOURCE,
        "checks.py" to """from durations import format_duration, parse_duration
assert parse_duration("1h30m") == 5400
assert parse_duration("45s") == 45
assert parse_duration("2d") == 172800
assert format_duration(5400) == "1h30m"
assert format_duration(0) == "0s"
try:
    parse_duration("soon")
    raise SystemExit("'soon' must be rejected")
except ValueError:
    pass
print("durations ok")
""",
    ),
    check = { project, _ -> bothPass(project, DURATIONS_HIDDEN) },
    reference = { project ->
        Files.writeString(project.resolve("durations.py"), DURATIONS_SOURCE.substringBefore("\n\ndef parse_duration") + """

import re

UNITS = (("d", 86400), ("h", 3600), ("m", 60), ("s", 1))
_PART = re.compile(r"(\d+)([dhms])")


def parse_duration(text):
    invalid = ValueError("invalid duration: " + repr(text))
    cleaned = text.strip().lower()
    if not cleaned:
        raise invalid
    if cleaned.isdigit():
        return int(cleaned)
    compact = cleaned.replace(" ", "")
    pos, last, total = 0, -1, 0
    for match in _PART.finditer(compact):
        index = "dhms".index(match.group(2))
        if match.start() != pos or index <= last:
            raise invalid
        last, pos = index, match.end()
        total += int(match.group(1)) * UNITS[index][1]
    if pos == 0 or pos != len(compact):
        raise invalid
    return total


def format_duration(seconds):
    if seconds < 0:
        raise ValueError(f"negative duration: {seconds}")
    parts = []
    for unit, size in UNITS:
        count, seconds = divmod(seconds, size)
        if count:
            parts.append(f"{count}{unit}")
    return "".join(parts) or "0s"
""")
        "Implemented parse_duration and format_duration per the docstring."
    },
    maxRounds = HARD_MAX_ROUNDS,
)

private const val BANK = """'''A small ledger used by the payments service.'''


class InsufficientFunds(Exception):
    '''Raised when an account cannot cover a withdrawal or a transfer.'''


class Account:
    def __init__(self, owner, balance=0):
        if balance < 0:
            raise ValueError("opening balance cannot be negative")
        self.owner = owner
        self.balance = balance
        self.history = []

    def deposit(self, amount):
        if amount <= 0:
            raise ValueError("deposit must be positive")
        self.balance += amount
        self.history.append(("deposit", amount))

    def withdraw(self, amount):
        if amount <= 0:
            raise ValueError("withdrawal must be positive")
        if amount > self.balance:
            raise InsufficientFunds(f"{self.owner} has {self.balance}, needs {amount}")
        self.balance -= amount
        self.history.append(("withdraw", amount))


def transfer(source, target, amount, fee=0):
    '''Move amount from source to target. The fee is charged to source on top of amount and is not received
    by target. If source cannot cover amount + fee, nothing changes.'''
    if fee < 0:
        raise ValueError("fee cannot be negative")
    if amount + fee > source.balance:
        raise InsufficientFunds(f"{source.owner} cannot cover {amount} plus fee {fee}")
    source.withdraw(amount + fee)
    target.deposit(amount)
"""

/** Each mutant breaks one documented behavior; a good test suite fails on every one of them. */
private val BANK_MUTANTS: List<Triple<String, String, String>> = listOf(
    Triple("withdrawing the whole balance fails", "        if amount > self.balance:", "        if amount >= self.balance:"),
    Triple("the fee reaches the target", "    target.deposit(amount)\n", "    target.deposit(amount + fee)\n"),
    Triple("a failed transfer still credits the target",
        "    if amount + fee > source.balance:\n        raise InsufficientFunds(f\"{source.owner} cannot cover {amount} plus fee {fee}\")\n    source.withdraw(amount + fee)\n    target.deposit(amount)\n",
        "    target.deposit(amount)\n    source.withdraw(amount + fee)\n"),
    Triple("a zero deposit is accepted", "        if amount <= 0:\n            raise ValueError(\"deposit must be positive\")", "        if amount < 0:\n            raise ValueError(\"deposit must be positive\")"),
    Triple("withdrawals are missing from history", "        self.history.append((\"withdraw\", amount))", "        pass"),
    Triple("a negative opening balance is accepted", "        if balance < 0:", "        if False:"),
    Triple("a negative fee is accepted", "    if fee < 0:", "    if False:"),
).onEach { (name, old, _) -> check(BANK.split(old).size == 2) { "mutant '$name' must change exactly one place" } }

private fun mutationVerdict(project: Path): Verdict {
    val tests = project.resolve("test_bank.py")
    if (!Files.isRegularFile(tests)) return Verdict(false, "test_bank.py missing")
    if (Files.readString(project.resolve("bank.py")) != BANK) return Verdict(false, "bank.py was changed")
    // Every run, the correct one too, sees only test_bank.py and bank.py: a suite that cannot even import there would
    // otherwise "catch" every mutant.
    fun passesWith(bank: String): Verdict {
        val copy = Files.createTempDirectory("chiller-mutant-")
        return try {
            Files.copy(tests, copy.resolve("test_bank.py"))
            Files.writeString(copy.resolve("bank.py"), bank)
            python(copy, "-m", "unittest", "test_bank")
        } finally { copy.toFile().deleteRecursively() }
    }
    val clean = passesWith(BANK)
    if (!clean.passed) return Verdict(false, "tests fail on the correct bank.py: ${clean.detail}")
    val survivors = BANK_MUTANTS.filter { (_, old, new) -> passesWith(BANK.replace(old, new)).passed }.map { it.first }
    return if (survivors.isEmpty()) Verdict(true, "tests pass on bank.py and catch all ${BANK_MUTANTS.size} mutants")
        else Verdict(false, "tests miss ${survivors.size}/${BANK_MUTANTS.size} mutants: ${survivors.joinToString()}")
}

private fun mutationTestsTask(): EvalTask = EvalTask(
    id = "hard-mutation-tests",
    title = "Тесты, которые ловят поломки",
    prompt = "Write unittest tests for bank.py in test_bank.py so that `python3 -m unittest test_bank` passes now and would catch regressions. " +
        "Cover every public behavior, including error cases and boundaries. Do not change bank.py. " +
        "The tests will also be run against broken copies of bank.py and must fail on each of them.",
    files = mapOf("bank.py" to BANK),
    check = { project, _ -> mutationVerdict(project) },
    reference = { project ->
        Files.writeString(project.resolve("test_bank.py"), """import unittest

from bank import Account, InsufficientFunds, transfer


class AccountTest(unittest.TestCase):
    def test_opening_balance(self):
        self.assertEqual(Account("a", 5).balance, 5)
        with self.assertRaises(ValueError):
            Account("a", -1)

    def test_deposit(self):
        a = Account("a")
        a.deposit(3)
        self.assertEqual((a.balance, a.history), (3, [("deposit", 3)]))
        for bad in (0, -1):
            with self.assertRaises(ValueError):
                a.deposit(bad)

    def test_withdraw(self):
        a = Account("a", 5)
        a.withdraw(5)
        self.assertEqual((a.balance, a.history), (0, [("withdraw", 5)]))
        with self.assertRaises(InsufficientFunds):
            a.withdraw(1)
        with self.assertRaises(ValueError):
            a.withdraw(0)


class TransferTest(unittest.TestCase):
    def test_fee_goes_nowhere(self):
        s, t = Account("s", 10), Account("t")
        transfer(s, t, 6, fee=1)
        self.assertEqual((s.balance, t.balance), (3, 6))

    def test_failed_transfer_changes_nothing(self):
        s, t = Account("s", 5), Account("t", 1)
        with self.assertRaises(InsufficientFunds):
            transfer(s, t, 5, fee=1)
        self.assertEqual((s.balance, t.balance, s.history, t.history), (5, 1, [], []))

    def test_negative_fee(self):
        with self.assertRaises(ValueError):
            transfer(Account("s", 5), Account("t"), 3, fee=-1)


if __name__ == "__main__":
    unittest.main()
""")
        "Wrote test_bank.py covering balances, history, errors, boundaries, fees and atomic transfers."
    },
    maxRounds = HARD_MAX_ROUNDS,
)

private fun configTraceTask(): EvalTask = EvalTask(
    id = "hard-config-trace",
    title = "Проследить настройку через три слоя конфигурации (только чтение)",
    prompt = "In the production profile, what timeout in seconds does the sync job actually use, and which file sets that final value? Trace it through the code.",
    files = mapOf(
        "config/__init__.py" to "",
        "config/defaults.py" to "SETTINGS = {\n    \"sync_timeout\": 30,\n    \"sync_batch\": 100,\n    \"retry_limit\": 3,\n    \"cleanup_timeout\": 600,\n    \"log_level\": \"info\",\n}\n",
        "config/loader.py" to """'''Effective settings: defaults, then the profile module, then the deploy env file of the profile.'''
import importlib
import os

from config.defaults import SETTINGS


def _from_env_file(path):
    values = {}
    if not os.path.exists(path):
        return values
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, _, raw = line.partition("=")
            key = key.strip().lower()
            if key.endswith("_ms"):
                values[key[:-3]] = int(raw) // 1000
            else:
                values[key] = int(raw) if raw.strip().isdigit() else raw.strip()
    return values


def load(profile):
    settings = dict(SETTINGS)
    settings.update(importlib.import_module(f"config.profiles.{profile}").OVERRIDES)
    settings.update(_from_env_file(os.path.join("deploy", f"{profile}.env")))
    return settings
""",
        "config/profiles/__init__.py" to "",
        "config/profiles/production.py" to "# Production tuning, see docs/operations.md\nOVERRIDES = {\n    \"sync_timeout_s\": 45,\n    \"retry_limit\": 5,\n    \"log_level\": \"warning\",\n}\n",
        "config/profiles/staging.py" to "OVERRIDES = {\"sync_timeout\": 20}\n",
        "deploy/production.env" to "# production deploy\nSYNC_TIMEOUT_MS=90000\nSYNC_BATCH=250\n",
        "deploy/staging.env" to "SYNC_TIMEOUT_MS=15000\n",
        "jobs/__init__.py" to "",
        "jobs/sync.py" to "from config.loader import load\n\n\ndef run(profile, fetch):\n    settings = load(profile)\n    timeout = settings[\"sync_timeout\"]\n    batch = settings[\"sync_batch\"]\n    return fetch(batch=batch, timeout=timeout)\n",
        "jobs/cleanup.py" to "from config.loader import load\n\n\ndef run(profile, purge):\n    return purge(timeout=load(profile)[\"cleanup_timeout\"])\n",
        "docs/operations.md" to "# Operations\n\n## Sync job\n\nIn production the sync job times out after 60 seconds; staging uses 20.\n",
        "tests/fake_settings.py" to "SETTINGS = {\"sync_timeout\": 5, \"sync_batch\": 1}\n",
    ),
    mode = ExecutionMode.PLAN,
    allowProcesses = false,
    check = { _, answer ->
        val names = Regex("""\b90\b""").containsMatchIn(answer) && answer.contains("production.env")
        Verdict(names, if (names) "answer names 90 seconds from deploy/production.env" else "answer must name 90 seconds and deploy/production.env")
    },
    reference = { "90 seconds: deploy/production.env sets SYNC_TIMEOUT_MS=90000, which config/loader.py converts to sync_timeout last; production.py's sync_timeout_s is never read." },
    maxRounds = HARD_MAX_ROUNDS,
)

/**
 * Harder tasks for models that pass the short ones: a feature across six modules, two chained bugs among decoys, a refactor
 * of eight differently written date formats in a large file, a spec checked by cases the model never sees, tests that must
 * catch seven mutants, and a setting traced through three configuration layers. They get the app's round limit.
 * Declared last: building the tasks reads the values above, which a file initializes in order.
 */
internal val HARD_EVAL_TASKS: List<EvalTask> = listOf(
    reservationsTask(),
    pricingBugsTask(),
    isoDatesTask(),
    durationSpecTask(),
    mutationTestsTask(),
    configTraceTask(),
)
