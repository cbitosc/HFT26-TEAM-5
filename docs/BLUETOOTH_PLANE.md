# What the Bluetooth plane does since 2026-10-08

**Status:** describes `main` at `713b29d6` (2026-10-08), the commit that turned the Bluetooth features below on in
release builds. Before it they ran on debug builds only. The decisions behind them:

| Feature | Build flag | ADR |
|---|---|---|
| Android phones find and dial iPhones | `BLE_GATT_PEERS` | 2026-09.kwq2 |
| Room traffic rides advertising pages beside the links | `BLE_SIDE_PLANE` | 2026-09.sjaa, 2026-09.u8qj |
| Links reach farther on the Coded PHY | `BLE_CODED_PHY` | 2026-10.yvn6 |
| A link feeding a file asks for a shorter interval | `BLE_LINK_BOOST` | 2026-10.2da4 |
| A file is clocked by its receiver's reports | `BLE_FEED_WINDOW` | 2026-10.5buf |
| A phone asks which neighbour holds a blob first | `BLOB_QUERY` | 2026-10.6e5t |

Each flag defaults to on in debug and release, and `-P<flag>=false` builds that feature dark (`app/build.gradle.kts`).
The defaults live in source, so F-Droid's rebuild, which passes no `-P`, gets the same flags. This page explains how
the features behave together. The ADRs hold the evidence and the rejected options, and
[`PICTURE_FETCH_BLUETOOTH.md`](PICTURE_FETCH_BLUETOOTH.md) covers the picture fetch in depth.

## Where each feature acts

Bluetooth carries three kinds of work: finding peers, delivering small frames, and moving files. Each feature
improves one of them.

```mermaid
flowchart LR
    subgraph Find["Finding peers"]
        P["Presence advert, 1M"]
        G["GATT payload read<br/>BLE_GATT_PEERS"]
        C["Coded presence advert<br/>BLE_CODED_PHY"]
    end
    subgraph Deliver["Delivering small frames"]
        L["L2CAP link copy"]
        S["Side-channel page<br/>BLE_SIDE_PLANE"]
    end
    subgraph Move["Moving files"]
        Q["Holder query<br/>BLOB_QUERY"]
        B["Link boost<br/>BLE_LINK_BOOST"]
        W["Feed window<br/>BLE_FEED_WINDOW"]
        PH["Coded pace<br/>BLE_CODED_PHY"]
    end
    P --> L
    G --> L
    C --> L
    L --> Q
    Q --> B
    B --> W
    PH --> W
```

## Android phones find iPhones (`BLE_GATT_PEERS`)

An Android phone now sights a foreground iPhone and links to it. iOS cannot put service data in an advert, so a
foreground iPhone advertises only the `0xFE30` UUID. It serves the same 24-byte payload an Android advert carries from
a GATT characteristic instead (`DoorbellPolicy.PAYLOAD_UUID`).

The presence scan matches that UUID as a second filter. A UUID-only advert sights the iPhone from a cached payload for
its address, or starts a read of the payload. `GattPayloads` paces the reads the same way the iOS port does: one at a
time, 12 s each, 30 s after a failure and 10 min after a stranger. It also keeps at most 64 addresses, because an
iPhone's address rotates.

```mermaid
sequenceDiagram
    participant A as Android phone
    participant I as Foreground iPhone
    I-->>A: advert carrying only the 0xFE30 UUID
    alt payload cached for this address
        A->>A: sight the iPhone at this advert's RSSI
    else no payload yet
        A->>I: connectGatt, discover, read payload (12 s budget)
        I-->>A: 24-byte payload with node id, caps, PSM
        A->>A: cache it, sight the iPhone
    end
    Note over A,I: The larger node id dials, as between Android phones
    A-->>I: advert flag FLAG_DIALS_GATT_PEERS tells a lower-id iPhone to wait to be dialed
```

A cached payload is forgotten when a dial to the address fails before its channel opens, when a HELLO on that link
names another node, or after 60 s of scanning without hearing it while no link is up. The dial rule is unchanged: the
larger id dials. A backgrounded iPhone cannot be sighted this way.

## Room traffic reaches unlinked phones (`BLE_SIDE_PLANE`)

Nearby-room posts, reactions, receipts, group updates and profiles now reach a phone that can hear this one but holds
no link to it. They also stay prompt while a file streams on a link. These frames ride **advertising pages**:
non-connectable extended adverts under their own service UUID (`0xFE38`), which the controller schedules apart from
the links. A page carries one `FastFrameCodec` unit of at most 236 B. A direct message never rides a page, because a
page is a broadcast.

Sending keeps the link copy and adds the page, so a linked peer gets the frame as before.

```mermaid
flowchart TD
    F["A floodable frame to send"] --> DM{"Is it a DM?"}
    DM -- yes --> LINK["Link copy only, fastSend"]
    DM -- no --> BOTH["Link copy to every linked peer"]
    BOTH --> CAP{"Any peer advertising FLAG_SIDE_CHANNEL?"}
    CAP -- no --> DONE["Done"]
    CAP -- yes --> PAGE["Queue it on the carousel"]
    PAGE --> SLOT["Two advertising sets air pages<br/>12 s dwell from first air"]
```

`SideCarousel` rotates its queue (32 frames, 30 s freshness) through two advertising sets. Each page dwells 12 s from
first air, one screen-off scan interval. Frames with fewer parts go first, a started frame finishes first, and typing
cues coalesce per sender.

Listening costs battery, so `SideScanPolicy` runs the page scan only while a page could carry something the links
will not.

```mermaid
stateDiagram-v2
    [*] --> Off
    Off --> Listening: a side-capable peer is sighted but unlinked
    Off --> Listening: a file is streaming on one of our links
    Listening --> Off: every capable peer is linked and nothing streams
```

A phone sets `FLAG_SIDE_CHANNEL` in its presence advert's flags byte only after its controller passes the
extended-advertising probe. A phone without extended advertising keeps using its links alone.

**Known gap, #143.** Around every connection the Bluetooth stack pauses and resumes all advertising sets in one
command, and a refused resume reports nothing. The presence advert re-asserts itself after each connection (ADR
2026-10.9utz). The two page sets retry only a failed start, so after a silent refusal they can stay off the air until
the next bring-up.

## Links reach farther on the Coded PHY (`BLE_CODED_PHY`)

Two phones now find each other and stay linked from farther apart, on controllers that support the LE Coded PHY (S=8,
"long range"). Coded spends speed to gain distance: a link on it carries files at about 1 KiB/s, so the plane steps a
link down only when the signal calls for it.

**Finding.** A second presence set airs the same payload on Coded, and the presence scan listens on every PHY. While
the phone has no link, or an unlinked peer is heard only on Coded, every other scan window is Coded-only. A Coded
sighting is scored at +12 dB on the 1M scale (`CodedPhyPolicy.effectiveRssi`), so every −90 dBm floor means what it
did before. The legacy 1M advert stays for scanners that cannot hear extended adverts, iPhones among them.

**Dialing.** A peer heard on Coded alone for more than 8 s of 1M listening is dialed at its Coded address, with a 25 s
watchdog instead of 12 s. While Coded runs, a dialer heard on Coded alone is admitted whatever the id order, so a far
pair does not wait on each other.

**Stepping.** The larger id reads the link's RSSI through a GATT client on the same connection (`BlePhyControl`) and
asks `PhyStepper` whether to step.

```mermaid
stateDiagram-v2
    [*] --> OneM: link up
    OneM --> Coded: link RSSI weak, step down to Coded S=8
    Coded --> OneM: RSSI strong again, ask for 1M or 2M, controller picks
    OneM --> GaveUp: request unanswered in 3 s, or another PHY granted
    Coded --> GaveUp: request unanswered in 3 s, or another PHY granted
    GaveUp --> [*]: link stays on its PHY
    Coded --> Dropped: link ends at range
    OneM --> Dropped: link ends at the step-down edge
    Dropped --> [*]
```

**Recovering a link lost at range.** When a link with a PHY handle drops at range, both sides work to get it back for
3 minutes.

```mermaid
sequenceDiagram
    participant S as Smaller id, dialed side
    participant L as Larger id, dialing side
    Note over S,L: Link drops on Coded, or at the step-down edge
    S->>S: after 2.5 s, Coded advert every 250 ms for 3 min
    L->>L: RangeHunt holds the peer: 12 s scan gap, every other window Coded-only
    L->>L: hunts up to 30 min while Knit is open on a lit screen
    L->>S: dial at the Coded address once heard
    S-->>L: link up, fast advert and hunt end
```

**Control.** A release build always runs the AUTO mode. The Diagnostics row that pins Off, Auto, Coded or 1M, and
`…debug.PHY`, are debug-only walk-test tools; a release build ignores any mode a debug install left stored.
Diagnostics shows each link's PHY as a chip in every build. iPhones have no Coded PHY, and a phone whose controller
lacks it (the Pixel 3) runs the legacy plane.

## Files move faster on a lit screen (`BLE_LINK_BOOST`)

While either phone's screen is on, a photo between them can feed at 18 KiB/s instead of the 4 KiB/s base. A
Bluetooth link drains the host's share of the controller's buffers once per connection event, so its speed follows
the connection interval: about 9 KB/s at 45 ms, 22.5–29 KB/s at 15 ms. On 2026-10-06 a 410 KB photo between two lit
Pixels took 23.8 s.

While a file moves either way, a side whose screen is on asks for `CONNECTION_PRIORITY_HIGH` (and a larger ATT MTU
once) through a GATT client on the link. A dark side only listens, so a sender in a pocket still earns the interval a
watching receiver asked for. A link earns the larger budget only when the controller *reports* an interval of 15 ms or
less.

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Asked: a file starts and this screen is lit, ask HIGH
    Idle --> Listening: a file starts and this screen is dark, listen only
    Asked --> Earned: link reports an interval of 15 ms or less
    Listening --> Earned: the peer's ask lands, 15 ms or less reported
    Earned --> Unearned: a slower interval is reported
    Unearned --> Earned: 15 ms or less again
    Asked --> Released: 15 s idle, screen off, A2DP starts or link reads Coded
    Earned --> Released: 15 s idle, screen off, A2DP starts or link reads Coded
    Unearned --> Released: 15 s idle, screen off, A2DP starts or link reads Coded
    Released --> Idle
```

An earned link feeds at 18 KiB/s, or 12 KiB/s once the phone holds more than four LE connections; every other link
feeds at the 4 KiB/s base, split among the links feeding at once. A peer that reports its progress gets the feed
window below instead of these budgets, and the boost's shorter interval raises the drain that window follows. An
iPhone link is never boosted, because its doorbell owns the link parameters (ADR 2026-09.dqvb).

## A file is clocked by its receiver (`BLE_FEED_WINDOW`)

A file now moves as fast as its link drains, and a chat message sent mid-photo waits behind only a short queue. The
Bluetooth stack holds hundreds of KB before a write blocks, so the sender cannot see the drain from its own side.
Instead the receiver reports what it holds (`blobprog`), and the sender keeps about one round trip plus 250 ms of
data queued ahead of it.

```mermaid
flowchart TD
    START["Next chunk of a file on a Bluetooth link"] --> PEER{"Peer carries CAP_BLOB_HAVE<br/>and no doorbell?"}
    PEER -- "no: iPhone or older build" --> PACE["Paced: share of the budget<br/>Coded 1 KiB/s"]
    PEER -- yes --> SILENT{"Receiver silent 3 s?"}
    SILENT -- yes --> PACE
    SILENT -- no --> WIN{"In flight below drain x RTT + 250 ms?"}
    WIN -- yes --> WRITE["Write the chunk"]
    WIN -- no --> WAIT["Wait for a report or a queued frame"]
    WAIT --> WIN
```

```mermaid
sequenceDiagram
    participant S as Sender
    participant R as Receiver
    S->>R: chunks, up to the window
    R-->>S: blobprog, bytes held and ms since header
    Note over R: every chunk of 2 KiB or more, at most every 500 ms
    S->>S: drain = largest of last 12 samples, RTT = smallest in 10 s
    S->>R: more chunks as the window opens
    Note over S,R: Receiver silent for 3 s, back to the paced budget
```

The window holds from two of the link's smallest chunks (2 KiB on 1M/2M, 1 KiB on Coded) to 64 KiB. A PHY step
forgets the round trip, and a step to or from Coded forgets the drain too, so the window probes again. Every build
since `blobprog` reports progress, so the window turns on in the same release as the reports.

## A phone asks which neighbour holds a picture (`BLOB_QUERY`)

A picture now comes from the neighbour best placed to send it rather than the first one that seemed likely. Before it
asks for a blob, a phone sends `blobhave` to each neighbour whose link carries `CAP_BLOB_HAVE` and asks the best
holder by plane and load. The asking is what the flag gates. Every build answers a query, so a mixed fleet keeps
working, and neighbours that cannot answer (the iOS port, older builds) get ADR 2026-10.f7gg's round of asks.
[`PICTURE_FETCH_BLUETOOTH.md`](PICTURE_FETCH_BLUETOOTH.md) walks through the choice, the parked asks and the bounds.

## Trials still owed

The flags turned on ahead of these device trials, which the roadmap tracks:

- a battery night on a settled clique, all flags on against all off;
- a walk-apart under Off, Auto and Coded, measuring link-drop distance;
- the iPhone gate in both id orders, measuring first-link time;
- the feed window and link boost cells: a text behind a photo, a dark receiver, a mixed v2.8.0 fleet, fan-out, more
  than four LE links, the Moto G;
- the holder query with two holders on different links;
- a minified `staging` build feeding a photo to a debug phone, since none of this code had run under R8 before;
- advertising-set churn across eight link cycles on the Pixel 7, the Moto G and the Pixel 3 (#143).

## Where it lives

| Piece | Code |
|---|---|
| GATT payload reads | `BleGattPayloadReader`, `GattPayloads`, `BleScanner` (second filter), `BluetoothMeshTransport.onScanResult` |
| Side channel | `BleSideChannel`, `SideCarousel`, `SideScanPolicy`, `SideCapableTracker`, `BleFastRoutePolicy` |
| Coded PHY | `CodedPhyPolicy`, `PhyStepper`, `BlePhyControl`, `CodedAdvertPace`, `RangeHunt`, `BlePresenceTracker` |
| Link boost | `BleLinkBoost`, `LinkBoostPolicy`, `BleLinkTuner`, `LinkParamsCallback` |
| Feed window | `FeedWindow`, `RxReports`, `FramedLink.feedChunks`, `BluetoothMeshTransport.paceFor` |
| Holder query | `BlobExchange.want` / `onHaveQuery` / `onHaveAnswer` / `decide` |
| Flags | `app/build.gradle.kts`, the `BleSideChannel?` and mode flow `di/MeshModule.kt` hands the transport |
| Tests | `GattPayloadsTest`, `SideChannelLabTest`, `CodedPhyPolicyTest`, `BleFeedPolicyTest`, `BlobHaveLabTest` |
