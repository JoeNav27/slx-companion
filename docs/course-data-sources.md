# Course data sources for the GPS screen (checked 2026-10-22)

## golfclubxy — free, keyless, LIVE-TESTED ✅ (recommended)
- https://golfclubxy.willconia.com — hole-by-hole tee + green GPS
  coordinates extracted from OpenStreetMap. 12,719 courses.
  **No API key, no account.** Read-only, rate-limited per IP.
- Endpoints: `GET /courses?q=<name>` (search), `GET /courses/{id}`
  (full holes: par, handicap, yardage, tee + green lat/lon, multiple
  tee boxes), plus GeoJSON / GPX / KML exports.
- Live test 2026-10-22: `/health` ok. Torrey Pines South (id
  35679036): tier A, status full, 18 holes; hole 1 = par 4, 414 yd,
  green 32.90229,-117.24936; hole yardages sane. Torrey North also A.
- User's local courses: Pala Mesa (Fallbrook), Arrowood (Oceanside),
  Shadowridge (Vista) — all tier A / full / 18 holes. Marine Memorial
  (Camp Pendleton) = tier C2 "unavailable" (0 holes) → fallback needed.
- Caveats: green coords are polygon centers (good for yardage, not
  pin positions); tee selection can be heuristic (API warns of
  possible 20–40 m tee error when tee colours aren't tagged);
  per-course quality varies — API returns tier + confidence +
  warnings, so the app can show them honestly. ODbL attribution req.
- Design: app fetches a course once, caches it on the phone, then
  works fully offline — distances computed phone-side to green center,
  spoken via TTS. Mark-it-yourself mode stays as the fallback for
  unmapped courses.

## golfapi.io — real, but commercial ❌ for this project
- 40,000+ courses, hole-by-hole green/tee coordinates, scorecards,
  slope/ratings; caching explicitly allowed.
- BUT: paid service, API key + account required, pricing not public
  ("contact us / free trial"). Breaks the no-account requirement.

## Stracka / Golf Intelligence — pro tier, skip
- The dataset behind 18Birdies/Garmin etc. Tester plan $49/mo for
  100 credits (full course detail = 3 credits). Overkill + paid.

## Also spotted
- OpenGolfAPI (opengolfapi.org): free, keyless reads, ODbL, 16,800+
  courses — scorecard/tee focus; green coordinates unconfirmed.
  Backup candidate if golfclubxy coverage disappoints.
