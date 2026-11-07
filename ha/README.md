# Phone Guard: Home Assistant side

- `custom_components/phoneguard/` the integration: `protocol.py` (signed messages), `schedule.py`
  (timetable to controller config), `hub.py` (pairing, sync, parent actions; no HA imports so it is
  unit tested), `__init__.py` (webhook, services, state publishing, nudge).
- `www/phoneguard-card/phoneguard-card.js` the parent card. Add as a resource, then
  `type: custom:phoneguard-card` with `entity: sensor.phoneguard_<child>`.
- `tools/devserver.py` runs the same hub as a plain HTTP server so the phone and rig can be tested
  without touching Home Assistant: `python3 tools/devserver.py /tmp/state.json 8099 http://127.0.0.1:8099/hook`
  then `adb reverse tcp:8099 tcp:8099`. `tools/card-test.html` shows the card with mock data
  (serve this folder over HTTP).
- Tests: `python3 -m pytest -q tests`.

Services (administrators only): `create_pairing`, `confirm_pairing`, `boost`, `cancel_boost`,
`set_holiday`, `set_app_mode`, `approve_app`, `dismiss_app`, `set_child`, `nudge`, `release`, `forget`.
