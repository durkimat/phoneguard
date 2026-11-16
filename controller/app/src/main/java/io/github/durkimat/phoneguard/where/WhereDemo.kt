package io.github.durkimat.phoneguard.where

/**
 * A made-up journey for trying the "Where to?" map on an emulator with no network, no pairing and no location (debug builds only; the launcher reads the
 * `pg_where_demo` and `pg_where_dot` extras, see LauncherActivity). Three legs near St Albans: walk, bus, walk.
 */
object WhereDemo {
    val PLACE = Place("Demo Station", "stop", "St Albans", 51.7608, -0.3085, 0.9)
    const val PLAN_JSON = """{"prefer":"transit","walk":{"minutes":95,"metres":7200,"geometry":{"points":"_fquaB~`wSo}@_|Bo}@ozD_cuuaBnhmS_yF_pR_yFoh\\_wdvaB~m|Q_q@w|A","precision":6}},
 "options":[{"minutes":24,"start":"2026-11-17T08:12:00Z","end":"2026-11-17T08:36:00Z","transfers":0,"live":true,"legs":[
  {"mode":"walk","from":{"name":"Home","time":"2026-11-17T08:12:00Z"},"to":{"name":"Abbey Gateway","time":"2026-11-17T08:17:00Z"},"minutes":5,"live":false,"geometry":{"points":"_fquaB~`wSo}@_|Bo}@ozD","precision":6}},
  {"mode":"bus","line":"321","headsign":"Hatfield","from":{"name":"Abbey Gateway","time":"2026-11-17T08:20:00Z"},"to":{"name":"Demo Station","time":"2026-11-17T08:33:00Z"},"minutes":13,"live":true,"geometry":{"points":"_cuuaBnhmS_yF_pR_yFoh\\","precision":6}},
  {"mode":"walk","from":{"name":"Demo Station","time":"2026-11-17T08:33:00Z"},"to":{"name":"Demo Station","time":"2026-11-17T08:36:00Z"},"minutes":3,"live":false,"geometry":{"points":"_wdvaB~m|Q_q@w|A","precision":6}}]}]}"""
}
