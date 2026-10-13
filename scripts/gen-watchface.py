#!/usr/bin/env python3
"""Generate the Knit watch faces' `res/raw/watchface.xml` files (Watch Face Format v4).

    python3 scripts/gen-watchface.py          # rewrite both faces
    python3 scripts/gen-watchface.py --check  # fail if a committed face is stale

Two faces, one APK each (a WFF APK holds one face): the stitch face in `watchface/` and the monogram in
`watchface-monogram/`. They share the round user slots below.

The stitch face is "running stitch, three slots" (ADR 2026-10.3y2s): a bezel ring of two rounds of DASHES short dashes,
the time and date, and three circular complication slots. A WFF face runs no code, so the ring is drawn from one
text complication, `RingComplicationService`, whose text is `RingCode` (`wear/.../RingCode.kt`): character 0 the
reading's state, then DASHES plane codes for the outer round and DASHES for the inner. Every dash here reads its
own character with `subText` and picks its colour; which link lands on which dash (rounds of three, equal runs)
is decided in Kotlin, so this side stays a dumb printer. The layout of that code is the face's only contract
with `:wear`, pinned on that side by `RingCodeTest`. Change the two together.

Ninety-six dashes, each with its own colour expression, are why this file is generated: hand-edited, one typo
darkens one dash and nobody notices. Edit the geometry and palette below, never the XML.

The monogram face is analog: the launcher icon's "K" (ADR 2026-10.3y2s's amendment). The spine and the node are
printed on the dial, muted; the hands are the K's two arms, pivoting on the node's right corner where the icon's
arms meet. At 1:22 (and 13:22) the arms point where the icon's do, and for that minute the K lights to the icon's
own colours and the hands snap to its exact angles: the face is the app icon. The geometry is the icon path's own
(`app/src/main/res/drawable/ic_launcher_monochrome.xml`), its cap centres measured once into the constants below.
"""

import os
import sys
from xml.sax.saxutils import escape

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "watchface/src/main/res/raw/watchface.xml")
MONOGRAM_OUT = os.path.join(ROOT, "watchface-monogram/src/main/res/raw/watchface.xml")

SIZE = 450
C = SIZE / 2

# --- the ring ---------------------------------------------------------------------------------------------------
DASHES = 48  # RingCode.DASHES, per round
# (centre-line radius, stroke, always-on stroke, first code index) per round, outer first. The inner round is a
# little thinner, so a fourth link reads as a new row of stitching rather than a second gauge; it clears the
# three slots (the bottom ones reach 174 from the centre).
ROUNDS = [(204, 7, 4, 1), (189, 5, 3, 1 + DASHES)]
DASH_FILL = 0.55  # share of each dash's step that is drawn
UNLIT = "#3A1B16"  # the unworked fabric under the ring: the launcher icon's maroon
AMBIENT_LIT = (122, 122, 122)  # #7A7A7A
AGED_ALPHA = 90  # a reading past its live window draws at about a third

RING_PROVIDER = "app.getknit.knit/app.getknit.knit.wear.RingComplicationService"
CARRYING_PROVIDER = "app.getknit.knit/app.getknit.knit.wear.CarryingComplicationService"

# RingCode's plane characters -> colour (r, g, b). The short-range planes are the launcher icon's two strokes, so
# the everyday ring is Knit's own coral; the long-range planes take the coral's analogous neighbours on the colour
# wheel, amber one side and raspberry the other, so a far peer stands out without leaving the family.
PLANES = {
    "1": (0xE3, 0x5D, 0x4B),  # Bluetooth: the icon's stroke coral
    "2": (0xF2, 0x91, 0x8D),  # Wi-Fi Aware: the icon's stroke highlight
    "3": (0xF0, 0xA0, 0x3C),  # LoRa: amber, the coral turned toward orange
    "4": (0xD2, 0x50, 0x8A),  # internet relay: raspberry, the coral turned toward magenta
}
AGED = "A"

# --- the centre -------------------------------------------------------------------------------------------------
INK = "#E9E1DE"
INK_SOFT = "#B9AFAB"
INK_AMBIENT = "#9A9A9A"

# --- the three slots --------------------------------------------------------------------------------------------
# As large as the inner round allows: the top slot's edge sits at 181 from the centre, the inner round's dashes
# start at 186.5. The first trial's r=40 with 18 px values read small beside the system faces.
SLOT_R = 48
VALUE_SIZE = 24
TITLE_SIZE = 17
ICON = 28
SLOT_FILL = "#2A1714"  # the maroon, a shade darker so a slot sits back from the stitches
ACCENT = "#FFB5A0"  # coral, the brand colour; keeps the slots apart from the plane colours
CORAL = "#E35D4B"  # the logo's own stroke colour: the node, the colon, the needle
TRACK = "#3A3433"
SLOT_TYPES = "SHORT_TEXT RANGED_VALUE GOAL_PROGRESS SMALL_IMAGE MONOCHROMATIC_IMAGE EMPTY"
SLOTS = [
    # (slotId, name, centre x, centre y, primary provider or None, primary type, system fallback, its type)
    ("1", "Top", 225, 92, CARRYING_PROVIDER, "SHORT_TEXT", "EMPTY", "EMPTY"),
    ("2", "Bottom left", 159, 322, None, None, "HEART_RATE", "SHORT_TEXT"),
    ("3", "Bottom right", 291, 322, None, None, "STEP_COUNT", "SHORT_TEXT"),
]


def q(s):
    """An attribute value, escaped."""
    return escape(str(s), {'"': "&quot;"})


def code_at(i):
    return f'subText([COMPLICATION.TEXT], {i}, {i + 1})'


def dash_angles(k):
    step = 360 / DASHES
    start = k * step
    return round(start, 3), round(start + step * DASH_FILL, 3)


def lit_colour(i):
    """The colour expression for the dash at code index i: its plane's colour, dimmed if aged, else transparent."""
    c = code_at(i)
    alpha = f'({code_at(0)} == "{AGED}" ? {AGED_ALPHA} : 255)'
    expr = "colorArgb(0, 0, 0, 0)"
    for ch, (r, g, b) in reversed(list(PLANES.items())):
        expr = f'({c} == "{ch}" ? colorArgb({alpha}, {r}, {g}, {b}) : {expr})'
    return expr


def ambient_colour(i):
    r, g, b = AMBIENT_LIT
    c = code_at(i)
    lit = " || ".join(f'{c} == "{ch}"' for ch in PLANES)
    return f"({lit}) ? colorRgb({r}, {g}, {b}) : colorArgb(0, 0, 0, 0)"


def colour_of(hex_rgb):
    """A #RRGGBB literal as an expression."""
    return f"colorRgb({int(hex_rgb[1:3], 16)}, {int(hex_rgb[3:5], 16)}, {int(hex_rgb[5:7], 16)})"


def arc(k, radius, thickness, colour=None, transform=None, indent=6):
    a0, a1 = dash_angles(k)
    pad = " " * indent
    inner = f'{pad}  <Stroke color="{q(colour or "#00000000")}" thickness="{thickness}" cap="ROUND"'
    if transform:
        inner += f'>\n{pad}    <Transform target="color" value="{q(transform)}"/>\n{pad}  </Stroke>'
    else:
        inner += "/>"
    return (
        f'{pad}<Arc centerX="{C}" centerY="{C}" width="{2 * radius}" height="{2 * radius}" '
        f'startAngle="{a0}" endAngle="{a1}">\n{inner}\n{pad}</Arc>'
    )


def fabric():
    """The unlit outer round under everything: drawn with no Knit at all, so the face never looks broken."""
    r, w, _, _ = ROUNDS[0]
    arcs = "\n".join(arc(k, r, w, UNLIT, indent=6) for k in range(1, DASHES))
    return (
        f'    <PartDraw name="fabric" x="0" y="0" width="{SIZE}" height="{SIZE}">\n{arcs}\n'
        f'      <Variant mode="AMBIENT" target="alpha" value="0"/>\n    </PartDraw>'
    )


def node():
    """The logo's diamond node at 12 o'clock, over both rounds' cast-on point: where the knitting starts."""
    # A part's box is whole pixels; the shapes inside it need not be.
    side, hole, box = 18, 7, 28
    cy = C - (ROUNDS[0][0] + ROUNDS[1][0]) / 2
    x, y = round(C - box / 2), round(cy - box / 2)
    o, i = (box - side) / 2, (box - hole) / 2
    return (
        f'    <PartDraw name="node" x="{x}" y="{y}" width="{box}" height="{box}" angle="45">\n'
        f'      <Rectangle x="{o}" y="{o}" width="{side}" height="{side}"><Fill color="{CORAL}"/></Rectangle>\n'
        f'      <Rectangle x="{i}" y="{i}" width="{hole}" height="{hole}"><Fill color="#FF000000"/></Rectangle>\n'
        f'      <Variant mode="AMBIENT" target="alpha" value="160"/>\n    </PartDraw>'
    )


def needle():
    """A coral dot riding just outside the ring with the seconds, as if the ring were being knitted."""
    r, d = 216, 7
    return (
        f'    <PartDraw name="needle" x="0" y="0" width="{SIZE}" height="{SIZE}">\n'
        f'      <Ellipse x="{C - d / 2}" y="{C - r - d / 2}" width="{d}" height="{d}"><Fill color="{CORAL}"/></Ellipse>\n'
        f'      <Transform target="angle" value="[SECOND] * 6"/>\n'
        f'      <Variant mode="AMBIENT" target="alpha" value="0"/>\n    </PartDraw>'
    )


def ring_slot():
    # Dash 0 of each round is the cast-on point under the node, never drawn. The inner round's fabric shows only
    # once a fourth link exists — its first run's first dash, dash 1, is lit exactly then.
    r, w, _, at = ROUNDS[1]
    shown = f'{code_at(at + 1)} != "0"'
    inner = "\n".join(arc(k, r, w, transform=f"{shown} ? {colour_of(UNLIT)} : colorArgb(0, 0, 0, 0)", indent=10) for k in range(1, DASHES))
    lit = "\n".join(arc(k, r, w, transform=lit_colour(at + k), indent=10) for r, w, _, at in ROUNDS for k in range(1, DASHES))
    dim = "\n".join(arc(k, r, aw, transform=ambient_colour(at + k), indent=10) for r, _, aw, at in ROUNDS for k in range(1, DASHES))
    # The bounds are the whole dial, not a BoundingArc over the bezel: the runtime fits a slot's content to its
    # bounding shape, and an arc's squashed all 48 dashes into one dot at 12 o'clock (device trial, 2026-10-13).
    # The user slots come later in the scene, so their taps still win over the ring's.
    return f"""    <ComplicationSlot name="ring" slotId="0" x="0" y="0" width="{SIZE}" height="{SIZE}" supportedTypes="LONG_TEXT EMPTY" isCustomizable="FALSE">
      <DefaultProviderPolicy primaryProvider="{RING_PROVIDER}" primaryProviderType="LONG_TEXT" defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>
      <BoundingOval x="0" y="0" width="{SIZE}" height="{SIZE}"/>
      <Complication type="LONG_TEXT">
        <PartDraw name="inner_fabric" x="0" y="0" width="{SIZE}" height="{SIZE}">
{inner}
          <Variant mode="AMBIENT" target="alpha" value="0"/>
        </PartDraw>
        <PartDraw name="stitches" x="0" y="0" width="{SIZE}" height="{SIZE}">
{lit}
          <Variant mode="AMBIENT" target="alpha" value="0"/>
        </PartDraw>
        <PartDraw name="stitches_ambient" x="0" y="0" width="{SIZE}" height="{SIZE}" alpha="0">
{dim}
          <Variant mode="AMBIENT" target="alpha" value="255"/>
        </PartDraw>
      </Complication>
      <Complication type="EMPTY"/>
    </ComplicationSlot>"""


TIME_Y, TIME_H, TIME_SIZE = 150, 100, 88
COLON_GAP = 24  # the room the colon takes between hours and minutes
COLON_DOTS = (188, 222)  # centre lines of the two dots, on the digits' x-height band
COLON_D = 9


def time_texts(weight, colour, ambient):
    shown, hidden = ("0", "255") if ambient else ("255", "0")
    half = round(C - COLON_GAP / 2)
    out = []
    for fmt, x, align in (("hh", 0, "END"), ("mm", SIZE - half, "START")):
        out.append(
            f'      <TimeText format="{fmt}" hourFormat="SYNC_TO_DEVICE" align="{align}" x="{x}" y="{TIME_Y}" width="{half}" height="{TIME_H}" alpha="{shown}">\n'
            f'        <Variant mode="AMBIENT" target="alpha" value="{hidden}"/>\n'
            f'        <Font family="SYNC_TO_DEVICE" size="{TIME_SIZE}" weight="{weight}" color="{colour}"/>\n'
            f"      </TimeText>"
        )
    return "\n".join(out)


def colon(colour, ambient):
    shown, hidden = ("0", "255") if ambient else ("255", "0")
    dots = "".join(
        f'<Ellipse x="{C - COLON_D / 2}" y="{cy - COLON_D / 2}" width="{COLON_D}" height="{COLON_D}"><Fill color="{colour}"/></Ellipse>'
        for cy in COLON_DOTS
    )
    return (
        f'    <PartDraw name="colon" x="0" y="0" width="{SIZE}" height="{SIZE}" alpha="{shown}">{dots}'
        f'<Variant mode="AMBIENT" target="alpha" value="{hidden}"/></PartDraw>'
    )


def clock():
    return f"""    <DigitalClock x="0" y="0" width="{SIZE}" height="{SIZE}">
{time_texts("LIGHT", INK, ambient=False)}
{time_texts("THIN", INK_AMBIENT, ambient=True)}
    </DigitalClock>
{colon(CORAL, ambient=False)}
{colon(INK_AMBIENT, ambient=True)}
    <PartText name="date" x="0" y="244" width="{SIZE}" height="28">
      <Text align="CENTER">
        <Font family="SYNC_TO_DEVICE" size="18" weight="MEDIUM" color="{INK_SOFT}">
          <Template>%s %s<Parameter expression="[DAY_OF_WEEK_S]"/><Parameter expression="[DAY]"/></Template>
        </Font>
      </Text>
    </PartText>"""


class SlotStyle:
    """A round user slot's look: its size, the type and the colours it draws in, scaled to its diameter."""

    def __init__(self, r, value, title, icon, image, mono, fill, accent, track, ink, ring=None):
        self.r, self.d = r, 2 * r
        self.ring = ring
        self.value, self.title, self.icon, self.image, self.mono = value, title, icon, image, mono
        self.fill, self.accent, self.track, self.ink = fill, accent, track, ink
        # The rows, from the stitch face's tuned 96 px slot, scaled to this one.
        k = self.d / 96
        self.icon_y, self.title_x, self.title_y, self.title_h = round(14 * k), round(10 * k), round(17 * k), round(26 * k)
        self.value_y, self.value_y_titled, self.value_h = round(44 * k), round(42 * k), round(34 * k)


STITCH_SLOT = SlotStyle(SLOT_R, VALUE_SIZE, TITLE_SIZE, ICON, 54, 40, SLOT_FILL, ACCENT, TRACK, INK)


def text_part(x, y, w, h, expr, size, colour, weight="SEMI_BOLD"):
    return (
        f'<PartText x="{x}" y="{y}" width="{w}" height="{h}">'
        f'<Text align="CENTER" ellipsis="TRUE"><Font family="SYNC_TO_DEVICE" size="{size}" weight="{weight}" color="{colour}">'
        f'<Template>%s<Parameter expression="{expr}"/></Template></Font></Text></PartText>'
    )


def icon_part(x, y, s, st):
    return (
        f'<PartImage x="{x}" y="{y}" width="{s}" height="{s}" tintColor="{st.accent}">'
        f'<Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/></PartImage>'
    )


def disc(st):
    d = st.d
    ring = f'<Stroke color="{st.ring}" thickness="1"/>' if st.ring else ""
    return (
        f'<PartDraw x="0" y="0" width="{d}" height="{d}"><Ellipse x="0" y="0" width="{d}" height="{d}">'
        f'<Fill color="{st.fill}"/>{ring}</Ellipse><Variant mode="AMBIENT" target="alpha" value="0"/></PartDraw>'
    )


def icon_and_text(st):
    """An icon over a value when there is an icon, else the title over the value."""
    d, h = st.d, st.value_h
    return (
        "<Condition><Expressions>"
        '<Expression name="icon">[COMPLICATION.MONOCHROMATIC_IMAGE] != null</Expression>'
        '<Expression name="title">[COMPLICATION.TITLE] != null</Expression>'
        "</Expressions>"
        f'<Compare expression="icon">{icon_part((d - st.icon) // 2, st.icon_y, st.icon, st)}'
        f"{text_part(6, st.value_y, d - 12, h, '[COMPLICATION.TEXT]', st.value, st.ink)}</Compare>"
        f"<Compare expression=\"title\">{text_part(st.title_x, st.title_y, d - 2 * st.title_x, st.title_h, '[COMPLICATION.TITLE]', st.title, st.accent, 'MEDIUM')}"
        f"{text_part(6, st.value_y_titled, d - 12, h, '[COMPLICATION.TEXT]', st.value, st.ink)}</Compare>"
        f"<Default>{text_part(6, (d - h) // 2, d - 12, h, '[COMPLICATION.TEXT]', st.value, st.ink)}</Default>"
        "</Condition>"
    )


def gauge(st, value, lo, hi):
    """A 270° arc on the slot's edge: the track, and the value over it."""
    d = st.d
    r = d / 2
    frac = f"clamp((({value}) - ({lo})) / (({hi}) - ({lo})), 0, 1)"
    return (
        f'<PartDraw x="0" y="0" width="{d}" height="{d}">'
        f'<Arc centerX="{r}" centerY="{r}" width="{d - 8}" height="{d - 8}" startAngle="-135" endAngle="135">'
        f'<Stroke color="{st.track}" thickness="5" cap="ROUND"/></Arc>'
        f'<Arc centerX="{r}" centerY="{r}" width="{d - 8}" height="{d - 8}" startAngle="-135" endAngle="-135">'
        f'<Stroke color="{st.accent}" thickness="5" cap="ROUND"/>'
        f'<Transform target="endAngle" value="{q(f"-135 + 270 * {frac}")}"/></Arc>'
        "</PartDraw>"
    )


def user_slot(st, slot_id, name, cx, cy, provider, ptype, system, stype):
    d = st.d
    x, y = cx - st.r, cy - st.r
    policy = f'defaultSystemProvider="{system}" defaultSystemProviderType="{stype}"'
    if provider:
        policy = f'primaryProvider="{provider}" primaryProviderType="{ptype}" ' + policy
    ranged = gauge(st, "[COMPLICATION.RANGED_VALUE_VALUE]", "[COMPLICATION.RANGED_VALUE_MIN]", "[COMPLICATION.RANGED_VALUE_MAX]")
    goal = gauge(st, "[COMPLICATION.GOAL_PROGRESS_VALUE]", "0", "[COMPLICATION.GOAL_PROGRESS_TARGET_VALUE]")
    image = (
        f'<PartImage x="{(d - st.image) // 2}" y="{(d - st.image) // 2}" width="{st.image}" height="{st.image}">'
        f'<Image resource="[COMPLICATION.SMALL_IMAGE]"/></PartImage>'
    )
    mono = icon_part((d - st.mono) // 2, (d - st.mono) // 2, st.mono, st)
    return f"""    <ComplicationSlot name="{q(name)}" slotId="{slot_id}" displayName="{q(name)}" x="{x}" y="{y}" width="{d}" height="{d}" supportedTypes="{SLOT_TYPES}">
      <DefaultProviderPolicy {policy}/>
      <BoundingOval x="0" y="0" width="{d}" height="{d}" outlinePadding="2"/>
      <Complication type="SHORT_TEXT">{disc(st)}{icon_and_text(st)}</Complication>
      <Complication type="RANGED_VALUE">{disc(st)}{ranged}{icon_and_text(st)}</Complication>
      <Complication type="GOAL_PROGRESS">{disc(st)}{goal}{icon_and_text(st)}</Complication>
      <Complication type="SMALL_IMAGE">{disc(st)}{image}</Complication>
      <Complication type="MONOCHROMATIC_IMAGE">{disc(st)}{mono}</Complication>
      <Complication type="EMPTY"/>
    </ComplicationSlot>"""


def stitch_face():
    parts = [fabric(), ring_slot(), node(), needle(), clock()] + [user_slot(STITCH_SLOT, *s) for s in SLOTS]
    body = "\n".join(parts)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by scripts/gen-watchface.py — do not edit; change the script and re-run it. -->
<WatchFace width="{SIZE}" height="{SIZE}">
  <Metadata key="CLOCK_TYPE" value="DIGITAL"/>
  <Metadata key="PREVIEW_TIME" value="10:08:32"/>
  <Scene backgroundColor="#FF000000">
{body}
  </Scene>
</WatchFace>
"""


# --- the monogram ------------------------------------------------------------------------------------------------
# An oxblood dial — the icon's maroon (the stitch face's fabric) in a radial glow, lighter under the K and falling
# to near-black at the bezel; a flat slate read as a placeholder on the wrist. The icon's stroke and dots, and the
# K at rest in the stroke mixed about halfway into the dial, so the printed K sits back and only the hands are
# bright.
DIAL_GLOW = ["#4A221C", "#2A120F", "#0E0605"]
DIAL_STOPS = [0, 0.65, 1]
K_STROKE = "#F4928E"
K_DOT = "#E55E4C"
K_REST = "#9A5650"
K_REST_NODE = "#B04D41"
HAND_SHADOW = "#99000000"  # a soft dark keyline, so a hand crossing the printed K stays its own shape
HOUR_BODY = "#E55E4C"  # the hour hand is the deep coral, the minute hand the light: told apart at a glance
HOUR_TIP = "#B8402F"
PIVOT = "#C24A3B"
MONO_AMBIENT_K = "#4A4A4A"
MONO_AMBIENT_HAND = "#A0A0A0"

# The icon in its own units (the monochrome path's viewport), measured from its cap centres. The scene puts the
# arms' pivot — the node's right corner — at the dial's centre and scales by K_SCALE.
K_SCALE = 0.6
K_PIVOT = (229.9, 261.5)
K_STROKE_W = 40
K_SPINE = [(90.9, 118), (182, 209.9), (313.1, 338), (401.7, 427.8)]  # y spans of the spine's four dashes
K_SPINE_X = 177.3
K_NODE_HALF = 52.6  # the node's half-diagonal; the solid diamond replaces the icon's ring and its four dots
K_ARM = [(85, 148), (206.5, 234.6)]  # an arm's two dashes, as distances from the pivot (its nub is dropped)
K_ARM_TIP = 234.6
UP_ARM, DOWN_ARM = 43.36, 135.35  # the icon's arm angles, clockwise from 12

LIT = "([HOUR_0_11] == 1 && [MINUTE] == 22)"
HOUR_ANGLE = f"{LIT} ? {UP_ARM} : [HOUR_0_11] * 30 + [MINUTE] * 0.5"
MINUTE_ANGLE = f"{LIT} ? {DOWN_ARM} : [MINUTE] * 6 + [SECOND] * 0.1"
NEARBY_PROVIDER = "app.getknit.knit/app.getknit.knit.wear.NearbyComplicationService"
MONO_SLOT = SlotStyle(42, 21, 15, 24, 48, 36, "#210C09", K_STROKE, "#3A1B16", "#F2E6E3", ring="#6B3E37")
MONO_SLOTS = [
    # 138 from the centre at 110° and 250°: in off the track by a slot's radius, clear of the spine, and far enough
    # round from the 1:22 arm (135°) that it passes the right one by a hand's width.
    ("1", "Left", 95, 272, NEARBY_PROVIDER, "RANGED_VALUE", "EMPTY", "EMPTY"),
    ("2", "Right", 355, 272, None, None, "STEP_COUNT", "SHORT_TEXT"),
]


def k_at(x, y):
    """A point in the icon's units, in the scene."""
    return round(C + (x - K_PIVOT[0]) * K_SCALE, 2), round(C + (y - K_PIVOT[1]) * K_SCALE, 2)


def k_len(v):
    return round(v * K_SCALE, 2)


def stroke_line(x1, y1, x2, y2, w, colour):
    return (
        f'<Line startX="{x1}" startY="{y1}" endX="{x2}" endY="{y2}">'
        f'<Stroke color="{colour}" thickness="{w}" cap="ROUND"/></Line>'
    )


def round_dot(cx, cy, d, colour):
    return f'<Ellipse x="{round(cx - d / 2, 2)}" y="{round(cy - d / 2, 2)}" width="{d}" height="{d}"><Fill color="{colour}"/></Ellipse>'


def whole(name, body, alpha=None, ambient=None, transform=None):
    """A part over the whole dial (so a rotation turns about the dial's centre)."""
    a = f' alpha="{alpha}"' if alpha is not None else ""
    t = f'\n      <Transform target="angle" value="{q(transform)}"/>' if transform else ""
    v = f'\n      <Variant mode="AMBIENT" target="alpha" value="{ambient}"/>' if ambient is not None else ""
    return f'    <PartDraw name="{name}" x="0" y="0" width="{SIZE}" height="{SIZE}"{a}>\n      {body}{t}{v}\n    </PartDraw>'


def dial():
    glow = f'<RadialGradient centerX="{C}" centerY="{C}" radius="{C}" colors="{" ".join(DIAL_GLOW)}" positions="{" ".join(map(str, DIAL_STOPS))}"/>'
    return whole("dial", f'<Ellipse x="0" y="0" width="{SIZE}" height="{SIZE}"><Fill color="{DIAL_GLOW[1]}">{glow}</Fill></Ellipse>', ambient=0)


def minute_track():
    """Sixty stitches round the edge, the hours heavier; 12 is the cast-on node's, so it stays bare."""
    def tick(i, w, colour):
        a = i * 6
        return f'<Arc centerX="{C}" centerY="{C}" width="420" height="420" startAngle="{a - 1.6}" endAngle="{a + 1.6}"><Stroke color="{colour}" thickness="{w}" cap="ROUND"/></Arc>'
    active = "".join(tick(i, 6, "#8A8A9C") if i % 5 == 0 else tick(i, 4, "#5C5C70") for i in range(1, 60))
    ambient = "".join(tick(i, 4, "#5A5A5A") for i in range(5, 60, 5))
    return whole("track", active, ambient=0) + "\n" + whole("track_ambient", ambient, alpha=0, ambient=255)


def cast_on():
    """The node at 12, as on the stitch face."""
    side, box = 10, 16
    x, y = round(C - box / 2), 15 - box // 2
    o = (box - side) / 2
    return (
        f'    <PartDraw name="cast_on" x="{x}" y="{y}" width="{box}" height="{box}" angle="45">\n'
        f'      <Rectangle x="{o}" y="{o}" width="{side}" height="{side}"><Fill color="{K_DOT}"/></Rectangle>\n'
        f'      <Variant mode="AMBIENT" target="alpha" value="160"/>\n    </PartDraw>'
    )


def spine(body, dot):
    x = k_at(K_SPINE_X, 0)[0]
    w = k_len(K_STROKE_W)
    out = "".join(stroke_line(x, k_at(0, a)[1], x, k_at(0, b)[1], w, body) for a, b in K_SPINE)
    if dot:
        out += round_dot(x, k_at(0, K_SPINE[0][0])[1], w, dot) + round_dot(x, k_at(0, K_SPINE[-1][1])[1], w, dot)
    return out


def k_node(name, colour, alpha, ambient, swaps=True):
    """The solid diamond: the icon's ring and four dots in one shape, its right corner on the pivot."""
    cx, cy = k_at(K_SPINE_X, K_PIVOT[1])
    # A rounded square turned 45°, its corner tips at the icon's half-diagonal plus a soft corner.
    side, radius, box = 50, 3, 72
    x, y = round(cx - box / 2), round(cy - box / 2)
    o = (box - side) / 2
    return (
        f'    <PartDraw name="{name}" x="{x}" y="{y}" width="{box}" height="{box}" angle="45" alpha="{alpha}">\n'
        f'      <RoundRectangle x="{o}" y="{o}" width="{side}" height="{side}" cornerRadiusX="{radius}" cornerRadiusY="{radius}"><Fill color="{colour}"/></RoundRectangle>\n'
        + (f'      <Transform target="alpha" value="{q(f"{LIT} ? {255 - alpha} : {alpha}")}"/>\n' if swaps else "")
        + f'      <Variant mode="AMBIENT" target="alpha" value="{ambient}"/>\n    </PartDraw>'
    )


def k_static():
    """The printed K, twice — at rest and lit — crossfaded on the 1:22 minute, and a grey one for always-on."""
    def lit_swap(name, body, rest):
        alpha = 255 if rest else 0
        return (
            f'    <PartDraw name="{name}" x="0" y="0" width="{SIZE}" height="{SIZE}" alpha="{alpha}">\n      {body}\n'
            f'      <Transform target="alpha" value="{q(f"{LIT} ? {255 - alpha} : {alpha}")}"/>\n'
            f'      <Variant mode="AMBIENT" target="alpha" value="0"/>\n    </PartDraw>'
        )
    return "\n".join([
        lit_swap("spine_rest", spine(K_REST, K_REST_NODE), rest=True),
        lit_swap("spine_lit", spine(K_STROKE, K_DOT), rest=False),
        k_node("node_rest", K_REST_NODE, 255, 0),
        k_node("node_lit", K_DOT, 0, 0),
        whole("spine_ambient", spine(MONO_AMBIENT_K, None), alpha=0, ambient=255),
        k_node("node_ambient", MONO_AMBIENT_K, 0, 255, swaps=False),
    ])


def arm(body, tip, keyline):
    """One hand, drawn pointing at 12 from the dial's centre: the arm's two dashes and its dot."""
    w = k_len(K_STROKE_W)
    segs = [(C, C - k_len(a), C, C - k_len(b)) for a, b in K_ARM]
    tip_y = C - k_len(K_ARM_TIP)
    out = "".join(stroke_line(*s, w + 8, keyline) for s in segs) + round_dot(C, tip_y, w + 8, keyline)
    return out + "".join(stroke_line(*s, w, body) for s in segs) + round_dot(C, tip_y, w, tip)


def thread(colour):
    """The minute hand's thread, from its tip on to the track: the other way, after colour, to tell it."""
    return stroke_line(C, round(C - k_len(K_ARM_TIP) - 16, 2), C, C - 198, 3, colour)


def hands():
    lit_hour = (
        f'    <PartDraw name="hour_lit" x="0" y="0" width="{SIZE}" height="{SIZE}" alpha="0">\n      {arm(K_STROKE, K_DOT, HAND_SHADOW)}\n'
        f'      <Transform target="angle" value="{q(HOUR_ANGLE)}"/>\n'
        f'      <Transform target="alpha" value="{q(f"{LIT} ? 255 : 0")}"/>\n'
        f'      <Variant mode="AMBIENT" target="alpha" value="0"/>\n    </PartDraw>'
    )
    hour = (
        f'    <PartDraw name="hour" x="0" y="0" width="{SIZE}" height="{SIZE}">\n      {arm(HOUR_BODY, HOUR_TIP, HAND_SHADOW)}\n'
        f'      <Transform target="angle" value="{q(HOUR_ANGLE)}"/>\n'
        f'      <Transform target="alpha" value="{q(f"{LIT} ? 0 : 255")}"/>\n'
        f'      <Variant mode="AMBIENT" target="alpha" value="0"/>\n    </PartDraw>'
    )
    return "\n".join([
        hour,
        lit_hour,
        whole("minute", thread(K_STROKE) + arm(K_STROKE, K_DOT, HAND_SHADOW), ambient=0, transform=MINUTE_ANGLE),
        whole("hour_ambient", arm(MONO_AMBIENT_HAND, MONO_AMBIENT_HAND, "#FF000000"), alpha=0, ambient=255, transform=HOUR_ANGLE),
        whole("minute_ambient", thread(MONO_AMBIENT_HAND) + arm(MONO_AMBIENT_HAND, MONO_AMBIENT_HAND, "#FF000000"), alpha=0, ambient=255, transform=MINUTE_ANGLE),
        whole("pivot", round_dot(C, C, 17, PIVOT), ambient=0),
        whole("seconds", round_dot(C, C - 200, 8, K_DOT), ambient=0, transform="[SECOND] * 6"),
    ])


def monogram_face():
    parts = [dial(), minute_track(), cast_on()] + [user_slot(MONO_SLOT, *s) for s in MONO_SLOTS] + [k_static(), hands()]
    body = "\n".join(parts)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by scripts/gen-watchface.py — do not edit; change the script and re-run it. -->
<WatchFace width="{SIZE}" height="{SIZE}">
  <Metadata key="CLOCK_TYPE" value="ANALOG"/>
  <Metadata key="PREVIEW_TIME" value="01:22:30"/>
  <Scene backgroundColor="#FF000000">
{body}
  </Scene>
</WatchFace>
"""


FACES = [(OUT, stitch_face), (MONOGRAM_OUT, monogram_face)]


def main():
    check = "--check" in sys.argv[1:]
    stale = []
    for path, build in FACES:
        out = build()
        current = None
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                current = f.read()
        if check:
            if current != out:
                stale.append(os.path.relpath(path, ROOT))
            continue
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(out)
        print(f"wrote {os.path.relpath(path, ROOT)} ({len(out) // 1024} KiB)")
    if stale:
        print(f"{', '.join(stale)} stale: run python3 scripts/gen-watchface.py", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
