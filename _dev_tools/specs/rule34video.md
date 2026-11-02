# rule34video.com video source spec (checked 2026-11-02): NOT BUILT

Checked live on 2026-11-02 from England with Python `requests`, anonymous, one request (the home page,
`_dev_tools\probe_rule34video\probe.py`, reply in `probe_rule34video\out\home.html`, not committed).

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## Result: stopped at an age-verification wall

* `GET https://rule34video.com/` answered **200** [LIVE 200] (server `ddos-guard`, sets `__ddg*` cookies), but the
  page `<head>` loads a third-party **age-verification checker** (`https://www.ageverif.com/checker.js?key=...`)
  [LIVE]. In a browser from the UK this puts an age check in front of the site (the UK Online Safety Act checks).
* The video list markup is in the HTML behind that script (`div.item.thumb` cards with `data-video-card-id`), so a
  plain HTTP client would skip the check. That is working around an age-verification wall, so **the probe stopped
  here**: no list / video page / stream / thumbnail was fetched or parsed, and no Kotlin source was written.
* Not tried (on purpose): VPN / proxy / mirror domains, the site's `/embed/<id>` player, the KVS `get_file` /
  `license_code` stream decoding.

## What would be needed to add it later (all GUESS)

* A way to pass the site's own age check that the user does themselves in the in-app web view (cookies then shared
  through `WebCookieJar`), if the site offers one that is lawful for the user to complete.
* It is a KVS (Kernel Video Sharing) site: lists `/latest-updates/`, `/most-popular/`, `/top-rated/` (seen in the
  page's `langUrls` JSON [LIVE]); video pages hold `flashvars` with `video_url` / `video_alt_url*` (`function/0/...`
  values decoded with `license_code`) [GUESS].

## Related

nsfwr34.com (spec `nsfwr34.md`) has ~3 % of its old posts (2022-2023) as `rule34video.com/embed/<id>` iframes: the
nsfwr34 source marks those as not playable in the app instead of opening rule34video.
