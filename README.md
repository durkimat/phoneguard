# Phone Guard

Control a child's Android phone from Home Assistant: time-based modes, per-mode app allowlists
and a PIN-protected local override, without Google Family Link.

**Status: design only.** There is no code yet. See [SPEC.md](SPEC.md) for the draft design.

Planned parts:

- A controller app for Android (device owner), which enforces the current mode on the phone.
- A Home Assistant custom integration, which owns the timetable and exposes each phone as a device.

Licence: Apache-2.0.
