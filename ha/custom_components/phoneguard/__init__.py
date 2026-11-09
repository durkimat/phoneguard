"""Phone Guard: parent control for a supervised Android phone (see the project spec)."""
from __future__ import annotations

import json
import logging
import time
from datetime import timedelta

import voluptuous as vol
from aiohttp import web

from homeassistant.components import webhook, websocket_api
from homeassistant.components.http import HomeAssistantView
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.helpers.storage import Store

from .const import DOMAIN, NUDGE_ACTION, NUDGE_PACKAGE, STORAGE_KEY, STORAGE_VERSION
from . import spotify as SP
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
        self.sp_pending = SP.Pending()  # Spotify sign-ins in progress

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
            fix = view.get("fix")
            if fix:  # the phone's own position reports become a tracker the person can use
                from homeassistant.components.zone import async_active_zone
                zone = async_active_zone(self.hass, fix[1], fix[2], fix[3])
                state = ("home" if zone.entity_id == "zone.home" else zone.name) if zone else "not_home"
                self.hass.states.async_set(
                    f"device_tracker.phoneguard_{cid}", state,
                    {"friendly_name": f"{view['name']} (Phone Guard)", "source_type": "gps", "latitude": fix[1], "longitude": fix[2],
                     "gps_accuracy": fix[3], "provider": fix[4], "reported": fix[0]},
                )

    def changed(self, nudge_child: str | None = None) -> None:
        self.save()
        self.publish()
        if nudge_child:
            self.hass.async_create_task(self.nudge(nudge_child))

    # ---- talking to the phone ----------------------------------------------------------------

    async def url(self) -> str:
        """The address the phone posts to.

        Nabu Casa cloudhooks are fire and forget (they answer 200 with an empty body), so they cannot
        carry our signed reply. The remote UI route (<id>.ui.nabu.casa/api/webhook/<id>) does.
        """
        path = webhook.async_generate_path(self.webhook_id)
        try:
            from homeassistant.components import cloud
            return cloud.async_remote_ui_url(self.hass) + path
        except Exception as err:  # no Nabu Casa, or remote access off
            _LOGGER.info("No remote UI URL (%s); using the normal webhook URL", err)
            return webhook.async_generate_url(self.hass, self.webhook_id)

    async def drop_cloudhook(self) -> None:
        """Remove a cloudhook that an earlier version created for our webhook."""
        try:
            from homeassistant.components import cloud
            await cloud.async_delete_cloudhook(self.hass, self.webhook_id)
        except Exception:  # none existed, or cloud is not available
            pass

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


_DONE_PAGE = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Phone Guard</title><body style="font-family:system-ui,sans-serif;max-width:30em;margin:3em auto;padding:0 1em">
<h2>{title}</h2><p>{text}</p>"""


class SpotifyCallback(HomeAssistantView):
    """Spotify sends the browser back here with a code. No login is needed (the browser may be any browser), so the
    one-time `state` made when Sign in was pressed is what proves it is ours."""

    url = SP.CALLBACK_PATH
    name = "api:phoneguard:spotify_callback"
    requires_auth = False

    async def get(self, request: web.Request) -> web.Response:
        mgr: Manager | None = request.app["hass"].data.get(DOMAIN)

        def page(title: str, text: str, status: int = 200) -> web.Response:
            return web.Response(text=_DONE_PAGE.format(title=title, text=text), content_type="text/html", status=status)

        if mgr is None:
            return page("Phone Guard is not running", "Try again in a minute.", 503)
        q = request.query
        p = mgr.sp_pending.take(q.get("state", ""))
        if p is None:
            return page("This sign-in link has expired", "Press Sign in again on the Phone Guard card.", 400)
        if "error" in q or "code" not in q:
            return page("Spotify was not signed in", f"Spotify said: {q.get('error', 'no code')}. You can close this and try again.", 400)
        session = async_get_clientsession(mgr.hass)
        try:
            async with session.post(SP.TOKEN_URL, data=SP.token_form(p, q["code"])) as resp:
                body = await resp.json(content_type=None)
                if resp.status != 200 or not body.get("refresh_token"):
                    _LOGGER.warning("Spotify sign-in refused: %s", body.get("error_description") or body.get("error"))
                    return page("Spotify refused the sign-in", str(body.get("error_description") or body.get("error") or resp.status), 400)
            name = ""
            try:  # only to show whose account it is
                async with session.get("https://api.spotify.com/v1/me", headers={"Authorization": "Bearer " + body["access_token"]}) as me:
                    if me.status == 200:
                        name = (await me.json(content_type=None)).get("display_name") or ""
            except Exception:  # not needed
                pass
        except Exception as err:
            _LOGGER.warning("Spotify sign-in failed: %s", type(err).__name__)
            return page("Could not reach Spotify", "Try again in a minute.", 502)
        if p["child"] not in mgr.hub.data["children"]:
            return page("That phone no longer exists", "Nothing was saved.", 400)
        mgr.hub.set_spotify(p["child"], body["refresh_token"], name)
        mgr.changed(p["child"])
        who = f" as {name}" if name else ""
        return page("Signed in to Spotify", f"The phone will pick this up the next time it checks in (a minute or two){who}. You can close this page.")


@websocket_api.websocket_command({vol.Required("type"): "phoneguard/spotify_start", vol.Required("child"): cv.slug, vol.Required("origin"): cv.string})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_spotify_start(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict) -> None:
    """The card asks where to send the browser to sign in. The reply is the Spotify address and the return address to register."""
    mgr: Manager = hass.data[DOMAIN]
    if msg["child"] not in mgr.hub.data["children"]:
        connection.send_error(msg["id"], "not_found", "Unknown device")
        return
    client_id = (mgr.hub.data.get("options") or {}).get("spotify_client_id", "")
    if not client_id:
        connection.send_error(msg["id"], "no_client_id", "Enter the Spotify Client ID first")
        return
    try:
        url = mgr.sp_pending.start(msg["child"], client_id, msg["origin"])
        redirect = SP.redirect_uri(msg["origin"])
    except ValueError as err:
        connection.send_error(msg["id"], "bad_origin", str(err))
        return
    connection.send_result(msg["id"], {"url": url, "redirect": redirect})


CHILD = vol.Schema({vol.Required("child"): cv.string}, extra=vol.ALLOW_EXTRA)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
    data = await store.async_load() or {}
    mgr = Manager(hass, store, data)
    hass.data[DOMAIN] = mgr
    mgr.save()
    hass.async_create_task(mgr.drop_cloudhook())
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
        mgr.hub.ensure_child(cid, call.data.get("name"), call.data.get("person"))
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

    async def set_mode_all(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call)
        if call.data["mode"] not in mgr.hub.data["children"][cid]["modes"]:
            raise HomeAssistantError(f"Unknown mode {call.data['mode']!r}")
        mgr.hub.set_mode_all(cid, call.data["mode"], call.data["allowed"]); mgr.changed(cid)

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

    async def set_options(call: ServiceCall) -> None:
        await admin(call)
        try:
            mgr.hub.set_options({k: v for k, v in call.data.items()})
        except Exception as err:
            raise HomeAssistantError(f"Rejected: {err}") from err
        for cid in list(mgr.hub.data["children"]):
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

    async def set_override(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call)
        try:
            mgr.hub.set_override(cid, call.data["mode"])
        except ValueError as err:
            raise HomeAssistantError(f"Not changed: {err}") from err
        mgr.changed(cid)

    async def copy_settings(call: ServiceCall) -> None:
        await admin(call)
        src, dst = child_of(call), call.data["to"]
        if dst not in mgr.hub.data["children"]:
            raise HomeAssistantError(f"Unknown device {dst!r}")
        try:
            mgr.hub.copy_settings(src, dst, list(call.data["parts"]))
        except (ValueError, KeyError) as err:
            raise HomeAssistantError(f"Not copied: {err}") from err
        mgr.changed(dst)

    async def rename_child(call: ServiceCall) -> None:
        await admin(call)
        old, new = child_of(call), call.data["new_child"]
        try:
            mgr.hub.rename_child(old, new)
        except ValueError as err:
            raise HomeAssistantError(f"Not renamed: {err}") from err
        if old in mgr.qr:
            mgr.qr[new] = mgr.qr.pop(old)
        hass.states.async_remove(f"sensor.phoneguard_{old}")
        mgr.changed()

    async def remove_child(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call); mgr.hub.remove_child(cid); mgr.qr.pop(cid, None)
        hass.states.async_remove(f"sensor.phoneguard_{cid}")
        mgr.changed()

    async def spotify_signout(call: ServiceCall) -> None:
        await admin(call)
        cid = child_of(call); mgr.hub.set_spotify(cid, None); mgr.changed(cid)

    hass.http.register_view(SpotifyCallback())
    websocket_api.async_register_command(hass, ws_spotify_start)
    reg = hass.services.async_register
    reg(DOMAIN, "spotify_signout", spotify_signout, vol.Schema({vol.Required("child"): cv.slug}))
    reg(DOMAIN, "create_pairing", create_pairing, vol.Schema({vol.Required("child"): cv.slug, vol.Optional("name"): cv.string, vol.Optional("person"): cv.string}), supports_response=SupportsResponse.OPTIONAL)
    reg(DOMAIN, "confirm_pairing", confirm_pairing, vol.Schema({vol.Required("child"): cv.slug, vol.Required("sas"): cv.string}))
    reg(DOMAIN, "boost", boost, vol.Schema({vol.Required("child"): cv.slug, vol.Optional("mode", default="day"): cv.string, vol.Optional("minutes", default=15): vol.All(int, vol.Range(min=1, max=180)), vol.Optional("extend", default=True): cv.boolean}))
    reg(DOMAIN, "cancel_boost", cancel_boost, vol.Schema({vol.Required("child"): cv.slug}))
    reg(DOMAIN, "set_holiday", set_holiday, vol.Schema({vol.Required("child"): cv.slug, vol.Optional("from"): cv.string, vol.Optional("until"): cv.string}))
    reg(DOMAIN, "set_app_mode", set_app_mode, vol.Schema({vol.Required("child"): cv.slug, vol.Required("package"): cv.string, vol.Required("mode"): cv.string, vol.Required("allowed"): cv.boolean}))
    reg(DOMAIN, "set_mode_all", set_mode_all, vol.Schema({vol.Required("child"): cv.slug, vol.Required("mode"): cv.string, vol.Required("allowed"): cv.boolean}))
    reg(DOMAIN, "approve_app", approve_app, vol.Schema({vol.Required("child"): cv.slug, vol.Required("package"): cv.string, vol.Required("modes"): [cv.string]}))
    reg(DOMAIN, "dismiss_app", dismiss_app, vol.Schema({vol.Required("child"): cv.slug, vol.Required("package"): cv.string}))
    reg(DOMAIN, "set_child", set_child, vol.Schema({vol.Required("child"): cv.slug, vol.Required("config"): dict}))
    reg(DOMAIN, "set_options", set_options, vol.Schema({vol.Optional("spotify_client_id"): vol.Any(None, cv.string)}))
    reg(DOMAIN, "nudge", nudge, vol.Schema({vol.Required("child"): cv.slug}))
    reg(DOMAIN, "release", release, vol.Schema({vol.Required("child"): cv.slug}))
    reg(DOMAIN, "set_override", set_override, vol.Schema({vol.Required("child"): cv.slug, vol.Required("mode"): cv.string}))
    reg(DOMAIN, "copy_settings", copy_settings, vol.Schema({vol.Required("child"): cv.slug, vol.Required("to"): cv.slug, vol.Required("parts"): [cv.string]}))
    reg(DOMAIN, "rename_child", rename_child, vol.Schema({vol.Required("child"): cv.slug, vol.Required("new_child"): cv.slug}))
    reg(DOMAIN, "remove_child", remove_child, vol.Schema({vol.Required("child"): cv.slug}))
    reg(DOMAIN, "forget", forget, vol.Schema({vol.Required("child"): cv.slug}))

    # keep the published mode and boost current as time passes
    @callback
    def _tick(_now) -> None:
        mgr.publish()

    entry.async_on_unload(async_track_time_interval(hass, _tick, timedelta(seconds=30)))
    mgr.publish()
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    mgr: Manager = hass.data.pop(DOMAIN)
    webhook.async_unregister(hass, mgr.webhook_id)
    for svc in ("create_pairing", "confirm_pairing", "boost", "cancel_boost", "set_holiday", "set_app_mode",
                "approve_app", "dismiss_app", "set_child", "set_options", "nudge", "release", "forget", "remove_child", "copy_settings", "rename_child", "set_override", "spotify_signout", "set_mode_all"):
        hass.services.async_remove(DOMAIN, svc)
    await mgr.store.async_save(mgr.hub.data)
    return True
