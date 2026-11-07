"""Phone Guard: parent control for a supervised Android phone (see the project spec)."""
from __future__ import annotations

import json
import logging
import time
from datetime import timedelta

import voluptuous as vol
from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.helpers.storage import Store

from .const import DOMAIN, NUDGE_ACTION, NUDGE_PACKAGE, STORAGE_KEY, STORAGE_VERSION
from .hub import Hub

_LOGGER = logging.getLogger(__name__)
MAX_BODY = 256 * 1024


class Manager:
    def __init__(self, hass: HomeAssistant, store: Store, data: dict):
        self.hass = hass
        self.store = store
        self.hub = Hub(data)
        self.webhook_id = data.setdefault("webhook_id", webhook.async_generate_id())
        self.qr: dict[str, dict] = {}  # transient: the pairing QR shown on the card
        self._last_nudge: dict[str, float] = {}

    # ---- persistence and published state ----------------------------------------------------

    def save(self) -> None:
        self.store.async_delay_save(lambda: self.hub.data, 1)

    def publish(self) -> None:
        for cid in self.hub.data["children"]:
            view = self.hub.view(cid)
            if cid in self.qr and "pairing" in view:
                view["pairing"].update(self.qr[cid])
            else:
                self.qr.pop(cid, None)
            self.hass.states.async_set(
                f"sensor.phoneguard_{cid}", view["mode_now"],
                {"friendly_name": f"Phone Guard {view['name']}", "icon": "mdi:cellphone-lock",
                 # nested attributes lose false/0/null, so the card reads one JSON string
                 "data": json.dumps(view)},
            )

    def changed(self, nudge_child: str | None = None) -> None:
        self.save()
        self.publish()
        if nudge_child:
            self.hass.async_create_task(self.nudge(nudge_child))

    # ---- talking to the phone ----------------------------------------------------------------

    async def url(self) -> str:
        try:
            from homeassistant.components import cloud
            return await cloud.async_get_or_create_cloudhook(self.hass, self.webhook_id)
        except Exception as err:  # not logged in, not connected, no subscription
            _LOGGER.info("No cloudhook (%s); using the normal webhook URL", err)
            return webhook.async_generate_url(self.hass, self.webhook_id)

    async def nudge(self, cid: str) -> None:
        svc = self.hub.data["children"][cid].get("notify", "")
        if not svc.startswith("notify.") or time.monotonic() - self._last_nudge.get(cid, 0) < 5:
            return
        self._last_nudge[cid] = time.monotonic()
        try:
            await self.hass.services.async_call(
                "notify", svc.split(".", 1)[1],
                {"message": "command_broadcast_intent",
                 "data": {"intent_action": NUDGE_ACTION, "intent_package_name": NUDGE_PACKAGE}},
                blocking=False,
            )
        except Exception as err:
            _LOGGER.warning("Nudge to %s failed: %s", svc, err)

    async def handle_webhook(self, hass: HomeAssistant, webhook_id: str, request: web.Request) -> web.Response:
        if (request.content_length or 0) > MAX_BODY:
            return web.Response(status=413)
        try:
            env = json.loads(await request.text())
        except ValueError:
            return web.Response(status=400)
        reply = self.hub.handle(env)
        if "error" in reply:
            _LOGGER.debug("Rejected request: %s", reply["error"])
        self.changed()
        return web.json_response(reply)


CHILD = vol.Schema({vol.Required("child"): cv.string}, extra=vol.ALLOW_EXTRA)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
    data = await store.async_load() or {}
    mgr = Manager(hass, store, data)
    hass.data[DOMAIN] = mgr
    mgr.save()
    webhook.async_register(hass, DOMAIN, "Phone Guard", mgr.webhook_id, mgr.handle_webhook, local_only=False, allowed_methods=["POST"])

    async def admin(call: ServiceCall) -> None:
        uid = call.context.user_id
        if uid is not None:
            user = await hass.auth.async_get_user(uid)
            if user is None or not user.is_admin:
                raise HomeAssistantError("Only a parent (administrator) can do this")

    def child_of(call: ServiceCall) -> str:
        cid = call.data["child"]
        if cid not in mgr.hub.data["children"]:
            raise HomeAssistantError(f"Unknown child {cid!r}")
        return cid

    async def create_pairing(call: ServiceCall) -> ServiceResponse:
        await admin(call)
        cid = call.data["child"]
        mgr.hub.ensure_child(cid, call.data.get("name"))
        info = mgr.hub.create_pairing(cid, await mgr.url())
        card = {"code": info["code"], "qr_text": info["qr"]}
        try:
            import segno
            from io import BytesIO
            buf = BytesIO()
            segno.make(info["qr"], error="m").save(buf, kind="svg", scale=4, border=2, xmldecl=False, svgns=True)
            card["qr_svg"] = buf.getvalue().decode()
        except Exception as err:  # the card falls back to showing the text
            _LOGGER.warning("Could not draw the QR code: %s", err)
        mgr.qr[cid] = card
        mgr.changed()
        return {"qr": info["qr"], "expires": info["expires"]}

    async def confirm_pairing(call: ServiceCall) -> None:
        await admin(call)
        if not mgr.hub.confirm_pairing(child_of(call), call.data["sas"]):
            raise HomeAssistantError("That number does not match the phone, or nothing is waiting to be confirmed")
        mgr.changed(call.data["child"])

    async def boost(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call)
        mgr.hub.boost(cid, call.data.get("mode", "day"), call.data.get("minutes", 15), call.data.get("extend", True))
        mgr.changed(cid)

    async def cancel_boost(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call); mgr.hub.cancel_boost(cid); mgr.changed(cid)

    async def set_holiday(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call)
        mgr.hub.set_holiday(cid, call.data.get("from"), call.data.get("until"))
        mgr.changed(cid)

    async def set_app_mode(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call)
        mgr.hub.set_app_mode(cid, call.data["package"], call.data["mode"], call.data["allowed"])
        mgr.changed(cid)

    async def approve_app(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call); mgr.hub.approve_app(cid, call.data["package"], list(call.data["modes"])); mgr.changed(cid)

    async def dismiss_app(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call); mgr.hub.dismiss_app(cid, call.data["package"]); mgr.changed(cid)

    async def set_child(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call)
        try:
            mgr.hub.update_child(cid, dict(call.data["config"]))
        except Exception as err:
            raise HomeAssistantError(f"Rejected: {err}") from err
        mgr.changed(cid)

    async def nudge(call: ServiceCall) -> None:
        await admin(call); await mgr.nudge(child_of(call))

    async def release(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call)
        if not mgr.hub.release(cid):
            raise HomeAssistantError("No paired phone for that child")
        mgr.changed(cid)

    async def forget(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call); mgr.hub.forget(cid); mgr.qr.pop(cid, None); mgr.changed()

    reg = hass.services.async_register
    reg(DOMAIN, "create_pairing", create_pairing, vol.Schema({vol.Required("child"): cv.slug, vol.Optional("name"): cv.string}), supports_response=SupportsResponse.OPTIONAL)
    reg(DOMAIN, "confirm_pairing", confirm_pairing, vol.Schema({vol.Required("child"): cv.slug, vol.Required("sas"): cv.string}))
    reg(DOMAIN, "boost", boost, vol.Schema({vol.Required("child"): cv.slug, vol.Optional("mode", default="day"): cv.string, vol.Optional("minutes", default=15): vol.All(int, vol.Range(min=1, max=180)), vol.Optional("extend", default=True): cv.boolean}))
    reg(DOMAIN, "cancel_boost", cancel_boost, vol.Schema({vol.Required("child"): cv.slug}))
    reg(DOMAIN, "set_holiday", set_holiday, vol.Schema({vol.Required("child"): cv.slug, vol.Optional("from"): cv.string, vol.Optional("until"): cv.string}))
    reg(DOMAIN, "set_app_mode", set_app_mode, vol.Schema({vol.Required("child"): cv.slug, vol.Required("package"): cv.string, vol.Required("mode"): cv.string, vol.Required("allowed"): cv.boolean}))
    reg(DOMAIN, "approve_app", approve_app, vol.Schema({vol.Required("child"): cv.slug, vol.Required("package"): cv.string, vol.Required("modes"): [cv.string]}))
    reg(DOMAIN, "dismiss_app", dismiss_app, vol.Schema({vol.Required("child"): cv.slug, vol.Required("package"): cv.string}))
    reg(DOMAIN, "set_child", set_child, vol.Schema({vol.Required("child"): cv.slug, vol.Required("config"): dict}))
    reg(DOMAIN, "nudge", nudge, vol.Schema({vol.Required("child"): cv.slug}))
    reg(DOMAIN, "release", release, vol.Schema({vol.Required("child"): cv.slug}))
    reg(DOMAIN, "forget", forget, vol.Schema({vol.Required("child"): cv.slug}))

    # keep the published mode and boost current as time passes
    entry.async_on_unload(async_track_time_interval(hass, lambda now: mgr.publish(), timedelta(seconds=30)))
    mgr.publish()
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    mgr: Manager = hass.data.pop(DOMAIN)
    webhook.async_unregister(hass, mgr.webhook_id)
    for svc in ("create_pairing", "confirm_pairing", "boost", "cancel_boost", "set_holiday", "set_app_mode",
                "approve_app", "dismiss_app", "set_child", "nudge", "release", "forget"):
        hass.services.async_remove(DOMAIN, svc)
    await mgr.store.async_save(mgr.hub.data)
    return True
