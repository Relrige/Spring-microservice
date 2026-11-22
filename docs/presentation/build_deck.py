"""Builds voltstore.pptx from reference.pptx (design template) following slides.md.

Run from this folder:  python build_deck.py
Keeps the template's theme, master and layouts, removes its slides and adds ours.
Diagrams are native PowerPoint shapes.
"""
import copy
from pathlib import Path

from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt

HERE = Path(__file__).parent
SRC = HERE / "reference.pptx"
import sys
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "voltstore.pptx"

# Palette of the template (theme "Эркер" and the sample's diagrams)
ORANGE = "FE8637"
BLUE = "7598D9"
LIGHT_BLUE = "E1E7F3"
YELLOW = "F5CD2D"
RED = "B32C16"
DARK = "575F6D"
TEXT = "262B33"
GREY = "777C84"
LIGHT = "F2F3F5"
BORDER = "C9CDD4"
WHITE = "FFFFFF"

LAYOUT_TITLE, LAYOUT_TEXT, LAYOUT_SECTION, LAYOUT_TITLE_ONLY = 0, 1, 2, 5

prs = Presentation(str(SRC))

# The slide-number placeholder of the sample (orange circle) is copied onto every content slide.
_SLDNUM = next(
    copy.deepcopy(sh._element)
    for sh in prs.slides[2].shapes
    if sh.is_placeholder and sh.placeholder_format.idx == 11
)
_old_slide_ids = list(prs.slides._sldIdLst)


# ---------------------------------------------------------------- helpers
def rgb(hex_):
    return RGBColor.from_string(hex_)


def _drop_style(shape):
    st = shape._element.find(qn("p:style"))
    if st is not None:
        shape._element.remove(st)


def _no_effects(shape):
    sppr = shape._element.spPr
    if sppr.find(qn("a:effectLst")) is None:
        etree.SubElement(sppr, qn("a:effectLst"))


def _runs(p, parts, size, color, bold):
    """parts: str or list of (text, {size, color, bold, font}) tuples."""
    if isinstance(parts, str):
        parts = [(parts, {})]
    for txt, o in parts:
        r = p.add_run()
        r.text = txt
        f = r.font
        if size:
            f.size = Pt(o.get("size", size))
        f.bold = o.get("bold", bold)
        if color:
            f.color.rgb = rgb(o.get("color", color))
        if "font" in o:
            f.name = o["font"]


def fill_text(tf, paras, size=14, color=TEXT, bold=False, align=PP_ALIGN.CENTER,
              anchor=MSO_ANCHOR.MIDDLE, margin=0.05, space_after=0):
    """paras: list of paragraphs; each is str or list of (text, opts)."""
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = Inches(margin)
    tf.margin_top = tf.margin_bottom = Inches(0.02)
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        if space_after:
            p.space_after = Pt(space_after)
        _runs(p, para, size, color, bold)


def box(slide, x, y, w, h, paras=None, fill=LIGHT, line=BORDER, color=TEXT, size=14,
        bold=False, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.15, dash=False, line_w=1.25,
        align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE, margin=0.06):
    s = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    _drop_style(s)
    if fill:
        s.fill.solid()
        s.fill.fore_color.rgb = rgb(fill)
    else:
        s.fill.background()
    if line:
        s.line.color.rgb = rgb(line)
        s.line.width = Pt(line_w)
        if dash:
            s.line.dash_style = 4  # MSO_LINE.DASH
    else:
        s.line.fill.background()
    _no_effects(s)
    if shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        s.adjustments[0] = radius
    if paras is not None:
        fill_text(s.text_frame, paras, size=size, color=color, bold=bold, align=align,
                  anchor=anchor, margin=margin)
    return s


def text(slide, x, y, w, h, paras, size=14, color=TEXT, bold=False, align=PP_ALIGN.LEFT,
         anchor=MSO_ANCHOR.TOP, space_after=0):
    t = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    fill_text(t.text_frame, paras, size=size, color=color, bold=bold, align=align,
              anchor=anchor, margin=0.0, space_after=space_after)
    return t


def line(slide, x1, y1, x2, y2, color=DARK, width=1.75, dash=False, head=True, tail_both=False):
    c = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1),
                                   Inches(x2), Inches(y2))
    _drop_style(c)
    ln = c._element.spPr.get_or_add_ln()
    for child in list(ln):
        ln.remove(child)
    ln.set("w", str(int(Pt(width))))
    sf = etree.SubElement(ln, qn("a:solidFill"))
    etree.SubElement(sf, qn("a:srgbClr")).set("val", color)
    if dash:
        etree.SubElement(ln, qn("a:prstDash")).set("val", "dash")
    etree.SubElement(ln, qn("a:round"))
    if tail_both:
        etree.SubElement(ln, qn("a:headEnd")).set("type", "triangle")
    if head:
        te = etree.SubElement(ln, qn("a:tailEnd"))
        te.set("type", "triangle")
        te.set("w", "med")
        te.set("len", "med")
    return c


def new_slide(title, layout=LAYOUT_TITLE_ONLY):
    s = prs.slides.add_slide(prs.slide_layouts[layout])
    s.shapes.title.text = title
    s.shapes._spTree.append(copy.deepcopy(_SLDNUM))
    return s


def text_slide(title, items, size=20):
    """Bulleted slide on the template's text layout; items are str or list of (text, opts)."""
    s = new_slide(title, LAYOUT_TEXT)
    body = s.placeholders[1]
    body.left, body.top, body.width, body.height = Inches(0.5), Inches(1.75), Inches(8.17), \
        Inches(4.45)
    tf = body.text_frame
    tf.word_wrap = True
    for i, item in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_before = Pt(10)
        _runs(p, item, size, None, None)
    return s


# ---------------------------------------------------------------- 1. Title
s = prs.slides.add_slide(prs.slide_layouts[LAYOUT_TITLE])
s.shapes.title.text = "VoltStore"
s.placeholders[1].text = "Інтернет-магазин електроніки"
text(s, 2.55, 6.45, 6.2, 0.4, ["[імена команди]"], size=16, color=DARK)

# ---------------------------------------------------------------- 2. Domain
s = new_slide("Предметна область")
roles = [
    ("Покупець", ["каталог і кошик", "замовлення й оплата", "доставка"]),
    ("Менеджер каталогу", ["категорії", "товари і ціни"]),
    ("Працівник складу", ["залишки і пакування", "передача кур'єру"]),
]
icons = ["\U0001F6D2", "\U0001F4CB", "\U0001F4E6"]
for i, (role, acts) in enumerate(roles):
    x = 0.5 + i * 2.77
    box(s, x, 1.95, 2.62, 2.45, None)
    box(s, x + 0.96, 1.75, 0.7, 0.7, None, fill=ORANGE, line=None, shape=MSO_SHAPE.OVAL)
    box(s, x + 0.96, 1.75, 0.7, 0.7, [[(icons[i], {"font": "Segoe UI Symbol", "color": WHITE})]],
        fill=None, line=None, size=22, shape=MSO_SHAPE.RECTANGLE, margin=0.0)
    text(s, x + 0.1, 2.6, 2.42, 0.4, [role], size=16, bold=True, align=PP_ALIGN.CENTER)
    text(s, x + 0.1, 3.1, 2.42, 1.2, acts, size=14, color=DARK, align=PP_ALIGN.CENTER,
         space_after=3)
text(s, 0.5, 4.75, 8.17, 0.4, ["Головний сценарій"], size=16, bold=True)
for i, st in enumerate(["Кошик", "Замовлення", "Оплата", "Доставка"]):
    x = 0.5 + i * 2.07
    box(s, x, 5.25, 2.12, 0.8, None, fill=ORANGE, line=None,
        shape=MSO_SHAPE.CHEVRON if i else MSO_SHAPE.PENTAGON)
    text(s, x + (0.15 if i == 0 else 0.4), 5.25, 1.5, 0.8, [st], size=15, bold=True, color=WHITE,
         align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)

# ---------------------------------------------------------------- 3–4. Architecture
text_slide("Архітектура системи", [
    "Єдиний вхід: Ingress → Frontend → API Gateway",
    "Gateway перевіряє JWT, обмежує частоту запитів і маршрутизує",
    "Вісім бізнес-сервісів, у кожного своя PostgreSQL",
    "Дані інших сервісів – через API або події в Kafka",
    "Payment і Notification ззовні недоступні",
])
s = new_slide("Архітектура системи")
box(s, 0.5, 1.75, 1.6, 0.5, ["Браузер"], fill=WHITE, line=DARK, shape=MSO_SHAPE.OVAL)
line(s, 2.1, 2.0, 2.6, 2.0)
box(s, 2.6, 1.75, 1.6, 0.5, ["Ingress"], fill=DARK, line=None, color=WHITE, bold=True)
line(s, 4.2, 2.0, 4.7, 2.0)
box(s, 4.7, 1.75, 2.6, 0.5, [[("Frontend", {"bold": True}), (" · React", {})]], fill=DARK,
    line=None, color=WHITE)
line(s, 6.0, 2.25, 6.0, 2.65)
text(s, 6.1, 2.27, 0.8, 0.35, ["/api"], size=12, color=DARK)
box(s, 0.5, 2.65, 8.17, 0.55,
    [[("API Gateway", {"bold": True}), ("   JWT · rate limit · маршрутизація", {})]],
    fill=DARK, line=None, color=WHITE, size=15)
line(s, 4.585, 3.2, 4.585, 3.5)
box(s, 0.5, 3.5, 8.17, 1.75, None, fill=None, line=BORDER, radius=0.06)
services = [["Auth", "Catalog", "Customer", "Inventory"],
            ["Order", "Payment", "Delivery", "Notification"]]
for row, names in enumerate(services):
    for col, n in enumerate(names):
        x = 0.62 + col * 1.99
        y = 3.62 + row * 0.82
        internal = n in ("Payment", "Notification")
        box(s, x, y, 1.9, 0.62, None, fill=ORANGE if n == "Order" else LIGHT,
            line=DARK if internal else BORDER, dash=internal, line_w=1.5 if internal else 1.25)
        text(s, x + 0.08, y + 0.15, 1.45, 0.35, [n], size=14, bold=True,
             color=WHITE if n == "Order" else TEXT)
        box(s, x + 1.55, y + 0.14, 0.24, 0.34, None, fill=BLUE, line=None, shape=MSO_SHAPE.CAN)
line(s, 4.585, 5.25, 4.585, 5.6, dash=True, tail_both=True)
box(s, 0.5, 5.6, 8.17, 0.45, [[("Apache Kafka", {"bold": True}), ("   події між сервісами", {})]],
    fill=YELLOW, line=None, size=15)
box(s, 0.5, 6.32, 0.17, 0.24, None, fill=BLUE, line=None, shape=MSO_SHAPE.CAN)
text(s, 0.75, 6.27, 3.6, 0.35, ["власна PostgreSQL у кожного сервісу"], size=12, color=DARK)
box(s, 4.45, 6.3, 0.36, 0.26, None, fill=None, line=DARK, dash=True, line_w=1.5, radius=0.2)
text(s, 4.9, 6.27, 3.9, 0.35, ["лише всередині системи"], size=12, color=DARK)

# ---------------------------------------------------------------- 5–6. Sync
text_slide("Синхронна взаємодія", [
    "Синхронно – лише там, де користувач чекає на відповідь",
    "Order бере ціни в Catalog, резервує товар в Inventory, списує кошти через Payment, "
    "керує ТТН у Delivery",
    "HTTP: декларативні клієнти RestClient + @HttpExchange",
    "Order → Inventory – gRPC: найчастіший і найчутливіший до затримок виклик",
    "Resilience4j: retry, circuit breaker, bulkhead, fallback",
])
s = new_slide("Синхронна взаємодія")
OY, OH = 1.95, 3.95
box(s, 1.1, OY, 1.9, OH, ["Order"], fill=ORANGE, line=None, color=WHITE, size=18, bold=True)
targets = [("Catalog", "актуальні ціни", False), ("Inventory", "резерв товару", True),
           ("Payment", "списання, повернення", False), ("Delivery", "ТТН, скасування", False)]
for i, (n, label, grpc) in enumerate(targets):
    y = OY + i * 1.1
    box(s, 5.9, y, 2.1, 0.65, [n], bold=True)
    cy = y + 0.325
    if grpc:
        line(s, 3.0, cy, 5.9, cy, color=BLUE, width=4.5)
        box(s, 5.1, cy - 0.4, 0.7, 0.3, ["gRPC"], fill=BLUE, line=None, color=WHITE,
            size=12, bold=True, margin=0.0)
    else:
        line(s, 3.0, cy, 5.9, cy, color=GREY, width=1.75)
    text(s, 3.15, cy - 0.4, 2.2, 0.3, [label], size=13, color=DARK)

# ---------------------------------------------------------------- 7–8. Async
text_slide("Асинхронна взаємодія: Kafka", [
    "Усе, що може статися пізніше, – через Kafka",
    "Чотири топіки: товари, залишки, замовлення, доставка",
    "Transactional outbox: подія і зміна стану в одній транзакції",
    "Idempotent consumer: повтор не дублює ефект",
    "Retry-топіки і DLT: збійне повідомлення не блокує решту",
])
s = new_slide("Асинхронна взаємодія: Kafka")
text(s, 0.5, 1.9, 1.55, 0.3, ["Продюсер"], size=12, color=GREY, align=PP_ALIGN.CENTER)
text(s, 2.35, 1.9, 2.75, 0.3, ["Топік"], size=12, color=GREY, align=PP_ALIGN.CENTER)
text(s, 5.4, 1.9, 3.27, 0.3, ["Споживачі"], size=12, color=GREY, align=PP_ALIGN.CENTER)
rows = [("Catalog", "catalog.product-events", ["ProductCreated · ProductUpdated"], [["Inventory"]]),
        ("Inventory", "inventory.stock-events", ["ProductStockReplenished", "ProductStockDepleted"],
         [["Catalog"]]),
        ("Order", "order.order-events", ["OrderPlaced · OrderPaid", "OrderShipped · OrderCancelled"],
         [["Customer", "Inventory"], ["Delivery", "Notification"]]),
        ("Delivery", "delivery.shipment-events", ["TTNGenerated · OrderDelivered"],
         [["Order", "Notification"]])]
y = 2.25
for prod, topic, evs, cons in rows:
    th = 0.32 + 0.24 * len(evs)
    h = max(th, 0.96 if len(cons) > 1 else 0.44)
    cy = y + h / 2
    is_order = prod == "Order"
    box(s, 0.5, cy - 0.25, 1.55, 0.5, [prod], fill=ORANGE if is_order else LIGHT,
        line=None if is_order else BORDER, color=WHITE if is_order else TEXT, bold=True)
    line(s, 2.05, cy, 2.35, cy)
    box(s, 2.35, cy - th / 2, 2.75, th,
        [[(topic, {"bold": True})]] + [[(e, {"size": 11, "color": DARK})] for e in evs],
        fill=YELLOW, line=None, size=13, margin=0.04)
    line(s, 5.1, cy, 5.4, cy)
    ch_y = cy - (0.96 if len(cons) > 1 else 0.44) / 2
    for ri, crow in enumerate(cons):
        for ci, cn in enumerate(crow):
            box(s, 5.4 + ci * 1.67, ch_y + ri * 0.52, 1.6, 0.44, [cn], fill=WHITE, line=BORDER,
                size=13)
    y += h + 0.22

# ---------------------------------------------------------------- 9–10. Order lifecycle
text_slide("Життєвий цикл замовлення", [
    "Очікує оплати → оплачене → очікує відправки → відправлене → доставлене",
    "Оформлення, оплату і відправку проводить Order за запитом користувача",
    "Після оплати стан змінюють події: TTNGenerated, OrderDelivered",
    "Без оплати за 15 хвилин замовлення прострочується, резерв повертається",
    "Скасувати можна до відправки",
])
s = new_slide("Життєвий цикл замовлення")
states = ["PENDING_PAYMENT", "PAID", "PENDING_SHIPPING", "SHIPPED", "DELIVERED"]
events = [None, "TTNGenerated", None, "OrderDelivered"]
MX, MW, H, GAP, Y0 = 3.3, 2.45, 0.55, 0.35, 1.85
for i, st in enumerate(states):
    y = Y0 + i * (H + GAP)
    final = st == "DELIVERED"
    box(s, MX, y, MW, H, [st], fill=BLUE if final else LIGHT, line=None if final else BORDER,
        color=WHITE if final else TEXT, size=13, bold=True)
    if i < 4:
        cx = MX + MW / 2
        line(s, cx, y + H, cx, y + H + GAP, color=DARK, width=1.75, dash=bool(events[i]))
        if events[i]:
            text(s, 0.5, y + H + 0.04, MX - 0.65, 0.28, [events[i]], size=12, color=DARK,
                 align=PP_ALIGN.RIGHT, bold=True)
pp_cy = Y0 + H / 2
box(s, 0.5, pp_cy - 0.25, 1.4, 0.5, ["EXPIRED"], fill=GREY, line=None, color=WHITE, size=13,
    bold=True)
line(s, MX, pp_cy, 1.9, pp_cy, color=GREY, width=1.5)
text(s, 1.95, pp_cy - 0.42, 1.35, 0.4, ["15 хв без оплати"], size=11, color=GREY,
     align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.BOTTOM)
CY = Y0 + (H + GAP) + H / 2  # level of PAID
box(s, 7.0, CY - 0.3, 1.67, 0.6, ["CANCELLED"], fill=GREY, line=None, color=WHITE, size=13,
    bold=True)
for i, dy in enumerate((-0.18, 0.0, 0.18)):
    sy = Y0 + i * (H + GAP) + H / 2
    line(s, MX + MW, sy, 7.0, CY + dy, color=GREY, width=1.25)
text(s, 7.0, CY + 0.38, 1.67, 0.3, ["скасування"], size=12, color=GREY, align=PP_ALIGN.CENTER)
line(s, 0.5, 5.6, 1.1, 5.6, color=DARK, head=False)
text(s, 1.2, 5.47, 2.0, 0.3, ["запит до Order"], size=12, color=DARK)
line(s, 0.5, 5.95, 1.1, 5.95, color=DARK, dash=True, head=False)
text(s, 1.2, 5.82, 2.0, 0.3, [[("подія з Kafka", {"bold": True})]], size=12, color=DARK)

# ---------------------------------------------------------------- 11–12. Compensations
text_slide("Компенсації і відновлення", [
    "Кожен крок – локальна транзакція свого сервісу",
    "Якщо наступний крок не вдався, попередній компенсується",
    "Скасування – сага, яку оркеструє Order: зняти резерв → скасувати ТТН → повернути кошти",
    "Кожен крок ідемпотентний; перерване скасування доробляє фонова задача",
    "Фонові задачі також знімають «осиротілі» резерви і доводять до кінця повернення коштів",
])
s = new_slide("Компенсації і відновлення")
items = [("CANCELLATION_REQUESTED", None), ("Зняти резерв", "Inventory"),
         ("Скасувати ТТН", "Delivery"), ("Повернути кошти", "Payment"), ("CANCELLED", None)]
BX, BW, BH, BG, BY = 0.9, 3.3, 0.55, 0.35, 1.85
for i, (label, svc) in enumerate(items):
    y = BY + i * (BH + BG)
    if svc:
        box(s, BX, y, BW, BH, [[(label, {"bold": True}), (" · " + svc, {"color": DARK})]],
            size=14)
    else:
        box(s, BX, y, BW, BH, [label], fill=GREY, line=None, color=WHITE, size=12, bold=True,
            margin=0.02)
    if i < 4:
        cx = BX + BW / 2
        line(s, cx, y + BH, cx, y + BH + BG)
s2y = BY + 2 * (BH + BG) + BH / 2
s3y = BY + 3 * (BH + BG) + BH / 2
JX, JW = 5.7, 2.97
box(s, JX, s2y - 0.35, JW, s3y - s2y + 0.7,
    [[("Фонова задача", {"bold": True})],
     [("повторює кроки, що лишилися", {"size": 12, "color": DARK})]], size=15)
line(s, BX + BW, s2y, JX, s2y, color=RED, width=2, dash=True)
text(s, BX + BW + 0.12, s2y - 0.55, 1.4, 0.5, ["сервіс", "недоступний"], size=11, color=RED)
line(s, JX, s3y, BX + BW, s3y, color=DARK, width=1.75)
text(s, BX + BW + 0.12, s3y + 0.05, 1.4, 0.5, ["доробити", "решту кроків"], size=11, color=DARK)

# ---------------------------------------------------------------- 13–14. Security
text_slide("Безпека: межа довіри", [
    "JWT перевіряє лише API Gateway",
    "Gateway видаляє заголовки X-User-* від клієнта і ставить свої",
    "Сервіси довіряють X-User-*, тому обійти gateway не можна",
    "У Kubernetes сервіси недоступні ззовні, а NetworkPolicy за замовчуванням забороняє "
    "вхідний трафік",
    "Між сервісами – спільний внутрішній токен; кожна база приймає лише свій сервіс",
])
s = new_slide("Безпека: межа довіри")
box(s, 1.45, 2.0, 7.22, 3.6, None, fill=None, line=DARK, dash=True, radius=0.04)
text(s, 1.6, 2.05, 6.9, 0.3,
     [[("Кластер Kubernetes · ", {"bold": True}), ("NetworkPolicy: default-deny", {})]],
     size=12, color=DARK)
chain = [("Клієнт", 0.5, 0.85, WHITE, DARK, TEXT), ("Ingress", 1.6, 1.0, DARK, None, WHITE),
         ("Frontend", 2.85, 1.1, DARK, None, WHITE), ("API Gateway", 4.2, 1.45, DARK, None, WHITE),
         ("Сервіс", 5.9, 1.45, LIGHT, BORDER, TEXT), ("БД", 7.6, 0.95, BLUE, None, WHITE)]
CY, CH = 2.6, 0.7
for i, (n, x, w, f, ln_, col) in enumerate(chain):
    if n == "Сервіс":
        paras = [[(n, {"bold": True})], [("довіряє X-User-*", {"size": 11, "color": DARK})]]
    else:
        paras = [[(n, {"bold": True})]]
    box(s, x, CY, w, CH, paras, fill=f, line=ln_, color=col, size=13, margin=0.03)
    if i < len(chain) - 1:
        line(s, x + w, CY + CH / 2, chain[i + 1][1], CY + CH / 2)
text(s, 3.6, CY + 0.85, 2.7, 0.9, ["перевіряє JWT", "видаляє X-User-* клієнта",
                                   "ставить свої X-User-*"],
     size=12, color=DARK, align=PP_ALIGN.CENTER)
RY = 5.05
line(s, 0.925, CY + CH, 0.925, RY, color=RED, width=2, head=False)
line(s, 0.925, RY, 6.625, RY, color=RED, width=2, head=False)
line(s, 6.625, RY, 6.625, CY + CH + 0.02, color=RED, width=2)
box(s, 1.27, RY - 0.18, 0.36, 0.36, ["✕"], fill=WHITE, line=RED, color=RED, size=14, bold=True,
    shape=MSO_SHAPE.OVAL, margin=0.0)
text(s, 1.8, RY - 0.38, 4.6, 0.3, ["в обхід gateway – заблоковано"], size=12, color=RED)

# ---------------------------------------------------------------- 15–16. Observability
text_slide("Спостережуваність і розгортання", [
    "Метрики: Micrometer → Prometheus; алерти через Alertmanager у Telegram",
    "Трейси: OpenTelemetry, один запит видно крізь HTTP, gRPC і Kafka",
    "Логи: JSON з тим самим traceId, що й у трейсі",
    "Grafana показує все разом: зі спану трейсу можна відкрити його логи",
    "Розгортання: ті самі образи в Docker Compose і Kubernetes (minikube)",
])
s = new_slide("Спостережуваність")
R = [2.0, 3.3, 4.6]
RH = 0.6
box(s, 0.5, R[0], 1.2, R[2] + RH - R[0], [[("Сервіси", {"bold": True})]], fill=LIGHT)
flows = [("трейси", "OTel Collector", "Tempo"), ("логи", "OTel agent", "Loki"),
         ("метрики", "Prometheus", "Alertmanager")]
for i, (sig, b, cbox) in enumerate(flows):
    cy = R[i] + RH / 2
    line(s, 1.7, cy, 2.4, cy)
    text(s, 1.7, cy - 0.3, 0.7, 0.28, [sig], size=11, color=DARK, align=PP_ALIGN.CENTER)
    box(s, 2.4, R[i], 1.6, RH, [b], size=13, bold=True)
    line(s, 4.0, cy, 4.3, cy)
    box(s, 4.3, R[i], 1.65, RH, [cbox], size=13, bold=True, margin=0.03)
line(s, 5.95, R[2] + RH / 2, 6.25, R[2] + RH / 2)
box(s, 6.25, R[2], 1.3, RH, ["Telegram"], fill=WHITE, line=DARK, size=13)
box(s, 6.25, R[0], 2.42, R[1] + RH - R[0], [[("Grafana", {"bold": True, "size": 16})]],
    fill=ORANGE, line=None, color=WHITE)
line(s, 5.95, R[0] + RH / 2, 6.25, R[0] + RH / 2)
line(s, 5.95, R[1] + RH / 2, 6.25, R[1] + RH / 2)
line(s, 4.0, R[2] + 0.02, 6.25, R[1] + RH - 0.02, color=DARK, width=1.5)

# ---------------------------------------------------------------- 17. Stack
s = new_slide("Стек технологій")
stack = [("Платформа", "Java 25, Spring Boot 4.1"),
         ("Дані", "PostgreSQL, Spring Data JPA, Flyway"),
         ("Вхід і безпека", "Spring Cloud Gateway, JWT"),
         ("Синхронні виклики", "RestClient + @HttpExchange, gRPC"),
         ("Повідомлення", "Apache Kafka, Spring for Apache Kafka"),
         ("Стійкість", "Resilience4j"),
         ("Спостережуваність", "Micrometer, Prometheus, OpenTelemetry, Grafana"),
         ("Тестування", "JUnit 5, Testcontainers, WireMock, Pact, k6, Toxiproxy"),
         ("Розгортання і UI", "Docker, Kubernetes, React + TypeScript")]
for i, (cat, tech) in enumerate(stack):
    x = 0.5 + (i % 3) * 2.77
    y = 1.75 + (i // 3) * 1.5
    box(s, x, y, 2.62, 1.38, [[(cat, {"bold": True, "size": 15, "color": TEXT})],
                              [(tech, {"color": DARK})]],
        size=14, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, margin=0.14)

# ---------------------------------------------------------------- 18. Decisions
decisions = [
    ("Своя база в кожного сервісу", "дані інших сервісів через API або локальну копію з подій"),
    ("Order координує гроші й резерви", "збій кроку компенсується, доставка йде подіями"),
    ("Синхронно – лише коли користувач чекає", "решта через Kafka"),
    ("Transactional outbox", "подія не загубиться і не з'явиться без збереженої зміни"),
    ("JWT перевіряє лише gateway", "до сервісів не потрапити в обхід нього"),
]
text_slide("Ключові архітектурні рішення",
           [[(h, {"bold": True}), (" – " + d, {})] for h, d in decisions], size=18)

# ---------------------------------------------------------------- 19. Demo
s = prs.slides.add_slide(prs.slide_layouts[LAYOUT_SECTION])
s.shapes.title.text = "Демонстрація"
body = s.placeholders[1]._element
body.getparent().remove(body)

# ---------------------------------------------------------------- remove the sample's slides
lst = prs.slides._sldIdLst
for sld in _old_slide_ids:
    prs.part.drop_rel(sld.rId)
    lst.remove(sld)

prs.save(str(OUT))
print("saved", OUT)
