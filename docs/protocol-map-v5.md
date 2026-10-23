# SLX Hybrid X3 — Protocol Map v5 (pin label hunt)

Addendum to v4, 2026-10-22 evening. Question: what makes the device
display FP / CP / BP next to the yardage? The app sends the selected
pin's yardage and the number changes, but the label stays "CP".

## Refuted: the distance record's fourth byte

- Every stock `0x01` record carries payload byte 4 = `0x02`
  ([rec][u16 BE yards×10][02][flag]).
- v0.32 sent 1 for Front Pin / 3 for Back Pin in that byte.
  Field result (user, 2026-10-22): **yardages "started acting
  funny", CP label never changed.** Hypothesis refuted; reverted
  in v0.33 (byte constant 0x02 again, records byte-identical to
  the known-good v0.31).
- Working model now: byte 4 likely says which green target the
  record's VALUE belongs to (the stock session's records were all
  center values, hence all 0x02). Sending only front-valued
  records starved the device's center display — consistent with
  the funny yardages. The label itself is set elsewhere.

## Also decoded: the setup-time 0x0a commands

From the original btsnoop (bug report 2026-10-22 11:34), all four
phone→device type `0x0a` frames, payload 3 bytes [A][B][04]:

- t=7.28  `02 04 04`
- t=10.82 `01 04 04`
- t=19.93 `01 03 04`
- t=22.91 `02 03 04`

All four (A ∈ {1,2}) × (B ∈ {3,4}) combos, sent during round setup
only — the stock app syncing two selector fields (v3's guess:
pin/green position). Two binary selectors don't map obviously
onto three pin states, and they were never re-sent mid-round in
that capture because the pin was only touched during setup.

## What's needed to settle it

A fresh btsnoop from the STOCK app with the pin changed **during**
a round (HCI snoop on → SLX Connect → start any round → change
pin Front → Center → Back, a few seconds apart → bug report).
The frames at those moments will show the label command directly:
a re-sent `0x0a`, a `0x5f` byte move, or something new. No more
guess-builds on this one.
