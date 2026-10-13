#!/usr/bin/env python3
"""Generate the Knit watch face's `watchface/src/main/res/raw/watchface.xml` (Watch Face Format v4).

    python3 scripts/gen-watchface.py          # rewrite the face
    python3 scripts/gen-watchface.py --check  # fail if the committed face is stale

The face is "running stitch, three slots" (ADR 2026-10.3y2s): a bezel ring of two rounds of DASHES short dashes,
the time and date, and three circular complication slots. A WFF face runs no code, so the ring is drawn from one
text complication, `RingComplicationService`, whose text is `RingCode` (`wear/.../RingCode.kt`): character 0 the
reading's state, then DASHES plane codes for the outer round and DASHES for the inner. Every dash here reads its
own character with `subText` and picks its colour; which link lands on which dash (rounds of three, equal runs)
is decided in Kotlin, so this side stays a dumb printer. The layout of that code is the face's only contract
with `:wear`, pinned on that side by `RingCodeTest`. Change the two together.

Ninety-six dashes, each with its own colour expression, are why this file is generated: hand-edited, one typo
darkens one dash and nobody notices. Edit the geometry and palette below, never the XML.
"""

import os
import sys
from xml.sax.saxutils import escape

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "watchface/src/main/res/raw/watchface.xml")

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


def text_part(x, y, w, h, expr, size, colour, weight="SEMI_BOLD"):
    return (
        f'<PartText x="{x}" y="{y}" width="{w}" height="{h}">'
        f'<Text align="CENTER" ellipsis="TRUE"><Font family="SYNC_TO_DEVICE" size="{size}" weight="{weight}" color="{colour}">'
        f'<Template>%s<Parameter expression="{expr}"/></Template></Font></Text></PartText>'
    )


def icon_part(x, y, s):
    return (
        f'<PartImage x="{x}" y="{y}" width="{s}" height="{s}" tintColor="{ACCENT}">'
        f'<Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/></PartImage>'
    )


def disc(d):
    return (
        f'<PartDraw x="0" y="0" width="{d}" height="{d}"><Ellipse x="0" y="0" width="{d}" height="{d}">'
        f'<Fill color="{SLOT_FILL}"/></Ellipse><Variant mode="AMBIENT" target="alpha" value="0"/></PartDraw>'
    )


def icon_and_text(d):
    """An icon over a value when there is an icon, else the title over the value."""
    return (
        "<Condition><Expressions>"
        '<Expression name="icon">[COMPLICATION.MONOCHROMATIC_IMAGE] != null</Expression>'
        '<Expression name="title">[COMPLICATION.TITLE] != null</Expression>'
        "</Expressions>"
        f'<Compare expression="icon">{icon_part((d - ICON) // 2, 14, ICON)}'
        f"{text_part(6, 44, d - 12, 34, '[COMPLICATION.TEXT]', VALUE_SIZE, INK)}</Compare>"
        f"<Compare expression=\"title\">{text_part(10, 17, d - 20, 26, '[COMPLICATION.TITLE]', TITLE_SIZE, ACCENT, 'MEDIUM')}"
        f"{text_part(6, 42, d - 12, 34, '[COMPLICATION.TEXT]', VALUE_SIZE, INK)}</Compare>"
        f"<Default>{text_part(6, (d - 34) // 2, d - 12, 34, '[COMPLICATION.TEXT]', VALUE_SIZE, INK)}</Default>"
        "</Condition>"
    )


def gauge(d, value, lo, hi):
    """A 270° arc on the slot's edge: the track, and the value over it."""
    r = d / 2
    frac = f"clamp((({value}) - ({lo})) / (({hi}) - ({lo})), 0, 1)"
    return (
        f'<PartDraw x="0" y="0" width="{d}" height="{d}">'
        f'<Arc centerX="{r}" centerY="{r}" width="{d - 8}" height="{d - 8}" startAngle="-135" endAngle="135">'
        f'<Stroke color="{TRACK}" thickness="5" cap="ROUND"/></Arc>'
        f'<Arc centerX="{r}" centerY="{r}" width="{d - 8}" height="{d - 8}" startAngle="-135" endAngle="-135">'
        f'<Stroke color="{ACCENT}" thickness="5" cap="ROUND"/>'
        f'<Transform target="endAngle" value="{q(f"-135 + 270 * {frac}")}"/></Arc>'
        "</PartDraw>"
    )


def user_slot(slot_id, name, cx, cy, provider, ptype, system, stype):
    d = 2 * SLOT_R
    x, y = cx - SLOT_R, cy - SLOT_R
    policy = f'defaultSystemProvider="{system}" defaultSystemProviderType="{stype}"'
    if provider:
        policy = f'primaryProvider="{provider}" primaryProviderType="{ptype}" ' + policy
    ranged = gauge(d, "[COMPLICATION.RANGED_VALUE_VALUE]", "[COMPLICATION.RANGED_VALUE_MIN]", "[COMPLICATION.RANGED_VALUE_MAX]")
    goal = gauge(d, "[COMPLICATION.GOAL_PROGRESS_VALUE]", "0", "[COMPLICATION.GOAL_PROGRESS_TARGET_VALUE]")
    image = (
        f'<PartImage x="{(d - 54) // 2}" y="{(d - 54) // 2}" width="54" height="54">'
        f'<Image resource="[COMPLICATION.SMALL_IMAGE]"/></PartImage>'
    )
    mono = icon_part((d - 40) // 2, (d - 40) // 2, 40)
    return f"""    <ComplicationSlot name="{q(name)}" slotId="{slot_id}" displayName="{q(name)}" x="{x}" y="{y}" width="{d}" height="{d}" supportedTypes="{SLOT_TYPES}">
      <DefaultProviderPolicy {policy}/>
      <BoundingOval x="0" y="0" width="{d}" height="{d}" outlinePadding="2"/>
      <Complication type="SHORT_TEXT">{disc(d)}{icon_and_text(d)}</Complication>
      <Complication type="RANGED_VALUE">{disc(d)}{ranged}{icon_and_text(d)}</Complication>
      <Complication type="GOAL_PROGRESS">{disc(d)}{goal}{icon_and_text(d)}</Complication>
      <Complication type="SMALL_IMAGE">{disc(d)}{image}</Complication>
      <Complication type="MONOCHROMATIC_IMAGE">{disc(d)}{mono}</Complication>
      <Complication type="EMPTY"/>
    </ComplicationSlot>"""


def face():
    parts = [fabric(), ring_slot(), node(), needle(), clock()] + [user_slot(*s) for s in SLOTS]
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


def main():
    check = "--check" in sys.argv[1:]
    out = face()
    current = None
    if os.path.exists(OUT):
        with open(OUT, encoding="utf-8") as f:
            current = f.read()
    if check:
        if current != out:
            print(f"{os.path.relpath(OUT, ROOT)} is stale: run python3 scripts/gen-watchface.py", file=sys.stderr)
            sys.exit(1)
        return
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(out)
    print(f"wrote {os.path.relpath(OUT, ROOT)} ({len(out) // 1024} KiB)")


if __name__ == "__main__":
    main()
