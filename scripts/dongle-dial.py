#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["bumble[android]==0.0.235"]
# ///
"""Can the two nRF52840 dongles connect at all? Bumble's own host on both, no Android stack and no bridge.

    uv run scripts/dongle-dial.py [TRIES]        # both emulator bridges down first: this takes both dongles

One dongle advertises connectable (legacy ADV_IND, a static random address), the other dials it TRIES times (8), then
the roles swap. Each dial prints its outcome and time, and each direction its tally. On 2026-10-05, with the BLE pair
failing to link whenever one side dialed, this connected 6 of 6 both ways in about 1 s each: the dongles were fine,
and the fault lay in what the Android stack's dial asks of the controller through the bridge
(.agents/context/testing.md, the pair's section). A direction at 0 here is the dongle; replug it.

Both dongles go back to the host's btusb on the way out, as hci-bridge.py's `give_back` does.
"""

import asyncio
import sys
import time

DONGLES = {"A": "usb:2fe3:000b/C849E41B33D5B711", "B": "usb:2fe3:000b/FA157AEC5AD9FD9D"}
STEP_S = 15
DIAL_S = 12


async def direction(advertiser: str, dialer: str, tries: int) -> int:
    from bumble.core import BT_LE_TRANSPORT
    from bumble.device import Device
    from bumble.hci import Address
    from bumble.transport import open_transport

    label = f"{dialer} dials {advertiser}"
    target = Address("F1:00:00:00:00:A1")
    async with (
        await open_transport(DONGLES[advertiser]) as (adv_source, adv_sink),
        await open_transport(DONGLES[dialer]) as (dial_source, dial_sink),
    ):
        adv = Device.with_hci("adv", target, adv_source, adv_sink)
        dial = Device.with_hci("dial", Address("F1:00:00:00:00:B2"), dial_source, dial_sink)
        await asyncio.wait_for(adv.power_on(), STEP_S)
        await asyncio.wait_for(dial.power_on(), STEP_S)
        ok = 0
        for i in range(tries):
            await asyncio.wait_for(adv.start_advertising(auto_restart=False), STEP_S)
            started = time.monotonic()
            try:
                connection = await asyncio.wait_for(dial.connect(target, transport=BT_LE_TRANSPORT), DIAL_S)
                ok += 1
                print(f"{label} {i}: connected in {time.monotonic() - started:.2f} s", flush=True)
                await connection.disconnect()
            except Exception as e:  # noqa: BLE001 - a failed dial is the measurement, not an error
                print(f"{label} {i}: no connection in {time.monotonic() - started:.1f} s ({type(e).__name__})", flush=True)
                try:
                    await dial.cancel_connection()
                except Exception as cancel:  # noqa: BLE001 - nothing pending to cancel is fine
                    print(f"{label} {i}: cancel: {type(cancel).__name__}", flush=True)
            await adv.stop_advertising()
            await asyncio.sleep(1)
        print(f"{label}: {ok}/{tries}", flush=True)
        return ok


def give_back() -> None:
    import usb1

    with usb1.USBContext() as context:
        for device in context.getDeviceIterator(skip_on_error=True):
            if (device.getVendorID(), device.getProductID()) != (0x2FE3, 0x000B):
                continue
            handle = device.open()
            for iface in range(device[0].getNumInterfaces()):
                try:
                    if not handle.kernelDriverActive(iface):
                        handle.attachKernelDriver(iface)
                except usb1.USBError as e:
                    print(f"could not give interface {iface} back to the kernel: {e}")
            handle.close()


async def main(tries: int) -> int:
    ab = await direction("A", "B", tries)
    ba = await direction("B", "A", tries)
    return 0 if ab and ba else 1


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main(int(sys.argv[1]) if len(sys.argv) > 1 else 8)))
    finally:
        give_back()
