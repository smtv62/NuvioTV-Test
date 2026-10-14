#!/usr/bin/env python3
"""D Smart GO live channel scraper.

Pulls the public channel catalogue from the D Smart GO CMS, mints signed HLS
URLs (the raw playlist URLs return 403 without a ticket signature) and writes:

  * playlists/dsmart.m3u  - player ready playlist
  * playlists/dsmart.json - structured metadata for the app

Only channels whose stream can actually be signed are written, so the output
contains the free-to-watch / working channels.

Signatures expire after roughly four hours, which is why this script is meant
to be run on a schedule (see .github/workflows/dsmart-playlist.yml).

Standard library only - no pip installs required.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone

SITE_ORIGIN = "https://www.dsmartgo.com.tr"

# The MerlinCDN host names below are the current ones and can rotate when the
# web app ships a new build, so the script always tries to re-read them from
# site-config first and only falls back to these constants.
SITE_CONFIG_URL = "https://bfogwjgq8dbp.merlincdn.net/client/site-config"
SITE_CONFIG_TOKEN = "EhuPj4n9ZnCa2YPqEIXKrLYuhVtsYggCMRKtfPqP42f3bedb"

FALLBACK = {
    "cms_base": "https://iwxa44sbbqmf.merlincdn.net",
    "cms_apikey": "a8fbff0087d146ddbfa26a13ebbf83c6",
    "ticket_base": "https://m9zgxauuw7mb.merlincdn.net",
    "crm_base": "https://kmblvxczr8i7.merlincdn.net",
    "crm_apikey": "ac3f095f717f2665f3e8787d8f62ebc1",
    "ticket_security": "Grqf3ayZlLoTSxirPazh6ovzDc20oqU4etTKgLbz77a8dc46",
    "ues_public_key": "3a21111d8723ed6b82043fa407f8f5f0",
}

LIVE_CHANNEL_CONTENT_TYPE_ID = 27819
PAGE_SIZE = 100

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
)

M3U_HEADER = (
    "#EXTM3U\n"
    "# D Smart GO - free / working live channels\n"
    "# generated: {generated_at}\n"
    "# links expire: {expires_at}\n"
)


# --------------------------------------------------------------------------- #
# HTTP helpers
# --------------------------------------------------------------------------- #
def request(method, url, *, body=None, headers=None, timeout=30):
    """Perform an HTTP request and return ``(status, text)``.

    Non 2xx answers are returned instead of raised so callers can decide what
    is fatal. The MerlinCDN edge rejects curl-like clients on POST requests,
    which is why everything goes through urllib with a browser user agent.
    """
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")

    merged = {
        "user-agent": USER_AGENT,
        "accept": "application/json, text/plain, */*",
        "accept-language": "tr",
        "origin": SITE_ORIGIN,
        "referer": SITE_ORIGIN + "/",
    }
    if data is not None:
        merged["content-type"] = "application/json"
    merged.update(headers or {})

    req = urllib.request.Request(url, data=data, method=method, headers=merged)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        try:
            payload = exc.read().decode("utf-8", "replace")
        except Exception:  # noqa: BLE001 - best effort
            payload = ""
        return exc.code, payload
    except Exception as exc:  # noqa: BLE001 - network layer errors
        return None, str(exc)


def expect_json(status, payload, what):
    if status != 200:
        raise RuntimeError(f"{what}: HTTP {status}: {payload[:200]}")
    try:
        return json.loads(payload)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"{what}: invalid JSON: {payload[:200]}") from exc


# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
def _dig(data, path, default=None):
    current = data
    for key in path.split("."):
        if not isinstance(current, dict) or key not in current:
            return default
        current = current[key]
    return current if current is not None else default


def load_config(timeout=20):
    """Resolve API hosts and keys, preferring a live site-config read."""
    cfg = dict(FALLBACK)
    cfg["site_config_ok"] = False

    status, payload = request(
        "GET",
        f"{SITE_CONFIG_URL}?version=1.0.5&platform=web-desktop&timestamp={int(time.time())}",
        headers={"authorization": f"Bearer {SITE_CONFIG_TOKEN}"},
        timeout=timeout,
    )
    if status == 200:
        try:
            conf = json.loads(payload)
        except json.JSONDecodeError:
            conf = {}
        if conf:
            cfg["site_config_ok"] = True
            cfg["cms_base"] = _dig(
                conf, "servicesConfig.contentConfig.cms.serviceBaseUrl", cfg["cms_base"]
            )
            cfg["cms_apikey"] = _dig(
                conf, "servicesConfig.contentConfig.cms.apiKey", cfg["cms_apikey"]
            )
            cfg["ticket_base"] = _dig(
                conf, "authConfig.crmTicket.serviceBaseUrl", cfg["ticket_base"]
            )
            cfg["crm_base"] = _dig(
                conf, "authConfig.crm.serviceBaseUrl", cfg["crm_base"]
            )
            cfg["crm_apikey"] = _dig(
                conf, "authConfig.crm.apiKey", cfg["crm_apikey"]
            )
            cfg["ticket_security"] = _dig(
                conf,
                "servicesConfig.contentConfig.rodp-engine.ticketSecurity",
                cfg["ticket_security"],
            )
            cfg["ues_public_key"] = _dig(
                conf,
                "servicesConfig.dataCollectionConfig.fasterious.apiKey",
                cfg["ues_public_key"],
            )
    return cfg


def cms_headers(cfg):
    return {
        "apikey": cfg["cms_apikey"],
        "langcode": "tr",
    }


# --------------------------------------------------------------------------- #
# Catalogue
# --------------------------------------------------------------------------- #
def custom_fields(item):
    return {entry.get("field"): entry for entry in item.get("customFields") or []}


def first_value(item, field):
    entry = custom_fields(item).get(field)
    if not entry:
        return None
    return entry.get("value")


def all_values(item, field):
    return [
        entry.get("value")
        for entry in item.get("customFields") or []
        if entry.get("field") == field and entry.get("value")
    ]


def image_url(item, image_type):
    for image in item.get("images") or []:
        if image.get("type") == image_type and image.get("url"):
            return image["url"]
    return None


def base_stream_url(item):
    """Return the unsigned HLS master URL of a channel, if it has one."""
    videos = item.get("videos") or []
    for preferred in ("HLS-Auto", "HLS-High"):
        for video in videos:
            if video.get("type") == preferred and video.get("url"):
                return video["url"]
    for video in videos:
        if (video.get("type") or "").startswith("HLS") and video.get("url"):
            return video["url"]
    return None


def fetch_channels(cfg, timeout=30):
    """Return every LiveChannel item published in the CMS."""
    channels = []
    page = 1
    total = None
    while total is None or len(channels) < total:
        body = {
            "displayCount": PAGE_SIZE,
            "contentTypeIds": [LIVE_CHANNEL_CONTENT_TYPE_ID],
            "customFilters": [],
            "sort": {"field": "CustomFieldOrder", "order": "asc", "namespace": "Order"},
            "include": ["customField"],
            "pageNumber": page,
        }
        status, payload = request(
            "POST",
            f"{cfg['cms_base']}/v1/item/filter/?mcdn-langauge-code=tr&platform=web",
            body=body,
            headers=cms_headers(cfg),
            timeout=timeout,
        )
        data = expect_json(status, payload, "channel filter")
        batch = data.get("data") or []
        channels.extend(batch)
        total = data.get("totalCount") or len(channels)
        if not batch:
            break
        page += 1
        if page > 50:  # hard stop against a pagination loop
            break
    return channels, total


# --------------------------------------------------------------------------- #
# Stream signing
# --------------------------------------------------------------------------- #
def start_session(cfg, timeout=20):
    """Register a guest id with the edge (best effort, mirrors the web app)."""
    try:
        status, payload = request(
            "GET",
            f"https://fasterius.ercdn.com/ues/client/get_id?publicKey={cfg['ues_public_key']}",
            timeout=timeout,
        )
        if status != 200:
            return None
        guest_id = json.loads(payload)["result"]["id"]
    except Exception:  # noqa: BLE001 - non fatal
        return None

    try:
        query = urllib.parse.urlencode({"key": cfg["crm_apikey"], "userId": guest_id})
        status, _ = request(
            "GET", f"{cfg['crm_base']}/create?{query}", timeout=timeout
        )
        return guest_id if status == 200 else None
    except Exception:  # noqa: BLE001 - non fatal
        return None


# The web app picks its request shape from servicesConfig.contentConfig.live in
# site-config: each entry matches a channel VideoUrlConfTag and lists the
# streamTypes that are allowed for it. Unknown tags fall back to the generic
# liveErStreamer recipe.
DAION_TAGS = {"daion", "daionWithNoDVR"}
DEFAULT_TAGS = {"default", "defaultWithArchive"}


def _num(value, fallback):
    try:
        return float(value)
    except (TypeError, ValueError):
        return float(fallback)


def candidate_streams(item):
    """Ordered ``{streamType, type, dvr}`` attempts for the ticket API."""
    fields = custom_fields(item)
    tag = (fields.get("VideoUrlConfTag") or {}).get("code") or ""

    initial = _num((fields.get("InitialDvrDuration") or {}).get("value"), 10)
    dvr = _num((fields.get("DvrDuration") or {}).get("value"), 240)
    reverse = _num((fields.get("ReverseEpgDuration") or {}).get("value"), 4320)

    def attempt(stream_type, vid_type, minutes):
        return {"streamType": stream_type, "type": vid_type, "dvr": minutes}

    candidates = []
    if tag in DAION_TAGS:
        # Daion (Demirören/D Smart) feeds: the ercdn rendition needs no extra
        # parameters, the raw daion rendition needs ?app=dsmart_web.
        candidates += [
            attempt("daion", "HLS-Auto", None),
            attempt("daion", "HLS-Daion", None),
        ]
    elif tag in DEFAULT_TAGS:
        candidates += [
            attempt("hasdvr", "HLS-Auto", 1440),
            attempt("hasreverseepg", "HLS-Auto", reverse),
        ]
    else:
        candidates += [
            attempt("hasinitialdvr", "HLS-Auto", initial),
            attempt("hasdvr", "HLS-Auto", dvr),
            attempt("hasreverseepg", "HLS-Auto", reverse),
        ]

    # Fallbacks: CMS flags are occasionally stale or the tag is missing.
    for fallback in (
        attempt("daion", "HLS-Auto", None),
        attempt("daion", "HLS-Daion", None),
        attempt("hasinitialdvr", "HLS-Auto", initial),
        attempt("hasdvr", "HLS-Auto", dvr),
        attempt("hasdvr", "HLS-Auto", 1440),
    ):
        if fallback not in candidates:
            candidates.append(fallback)
    return candidates


def mint_url(cfg, item, timeout=25):
    """Return ``(signed_url, expires_epoch, error)`` for a channel.

    A ``None`` URL means the channel is locked for guests: ``500106`` is the
    site's NEED_LOGIN and ``500308`` its NEED_PACKAGE code.
    """
    last_error = "no candidates"
    for stream in candidate_streams(item):
        primary = dict(stream)
        minutes = primary.pop("dvr", None)
        if minutes is not None:
            primary["dvrQueryParam"] = {"dvr": int(minutes)}
        body = {"id": item["id"], "products": [], "primary": primary}

        status, payload = request(
            "POST",
            f"{cfg['ticket_base']}/content?key={cfg['crm_apikey']}",
            body=body,
            headers={**cms_headers(cfg), "sec": cfg["ticket_security"]},
            timeout=timeout,
        )
        if status == 200:
            try:
                data = json.loads(payload)
                answer = data.get("result") or {}
                primary_answer = answer.get("primary") or {}
            except (json.JSONDecodeError, AttributeError):  # noqa: PERF203
                last_error = "malformed response"
                continue

            if primary_answer.get("success") and primary_answer.get("url"):
                url = _finalize_url(primary_answer["url"])
                return url, _expiry(url), None
            last_error = describe_error(data)
            if last_error.startswith("500106"):
                break  # login requirement is decided per channel, not per stream
            continue

        # 4xx still carries the structured ticket error (NEED_LOGIN, ...).
        try:
            last_error = describe_error(json.loads(payload))
        except json.JSONDecodeError:
            last_error = f"HTTP {status}"
    return None, None, last_error


def describe_error(data):
    """Map the ticket API error codes onto readable reasons."""
    code = data.get("errorCode")
    known = {
        "500102": "session expired",
        "500106": "needs login",
        "500301": "stream type not supported",
        "500308": "needs paid package",
    }
    if code in known:
        return f"{code} {known[code]}"
    message = data.get("errorMessage") or code or "unknown error"
    return str(message)


def _finalize_url(url):
    """The raw daion CDN answers 404 unless the player app name is present."""
    if ".daioncdn.net" not in url or "app=" in url:
        return url
    separator = "&" if "?" in url else "?"
    return f"{url}{separator}app=dsmart_web"


def _expiry(url):
    try:
        return int(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["e"][0])
    except Exception:  # noqa: BLE001 - signature without explicit expiry
        return None


def verify_stream(url, timeout=20):
    """Confirm the signed master playlist actually serves an HLS manifest."""
    status, payload = request("GET", url, timeout=timeout)
    return status == 200 and payload.lstrip().startswith("#EXTM3U")


# --------------------------------------------------------------------------- #
# Normalisation
# --------------------------------------------------------------------------- #
def normalise(item):
    genres = all_values(item, "Genres")
    channel_no = first_value(item, "ChannelNo")
    try:
        channel_no = int(channel_no) if channel_no is not None else None
    except (TypeError, ValueError):
        channel_no = None

    return {
        "id": item["id"],
        "name": item.get("displayTitle") or item.get("name") or "",
        "slug": item.get("contentEncodedName") or item.get("encodedName") or "",
        "channel_no": channel_no,
        "group": " / ".join(genres) if genres else "Diğer",
        "logo": image_url(item, "Logo"),
        "thumbnail": image_url(item, "Thumbnail"),
        "base_url": base_stream_url(item),
        "url_tag": first_value(item, "VideoUrlConfTag"),
    }


def resolve_channels(cfg, channels, workers, verify, timeout):
    """Sign every channel concurrently and keep the ones that work."""
    results = []
    failures = {}

    def work(channel):
        meta = normalise(channel)
        url, expires, error = mint_url(cfg, channel, timeout=timeout)
        if not url:
            return None, f"{meta['name']}: {error or 'no free stream'}"
        if verify and not verify_stream(url, timeout=timeout):
            return None, f"{meta['name']}: signature rejected by CDN"
        meta["url"] = url
        meta["expires_at"] = (
            datetime.fromtimestamp(expires, tz=timezone.utc).isoformat() if expires else None
        )
        return meta, None

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(work, channel) for channel in channels]
        for future in as_completed(futures):
            meta, error = future.result()
            if meta:
                results.append(meta)
            else:
                reason = error.split(": ", 1)[-1]
                failures[reason] = failures.get(reason, 0) + 1
                print(f"  skip -> {error}", file=sys.stderr)

    results.sort(key=lambda entry: (entry["channel_no"] is None, entry["channel_no"], entry["name"]))
    return results, failures


# --------------------------------------------------------------------------- #
# Writers
# --------------------------------------------------------------------------- #
def _m3u_escape(value):
    return str(value or "").replace('"', "'").replace("\n", " ").strip()


def write_m3u(path, channels, generated_at, expires_at):
    lines = [
        M3U_HEADER.format(generated_at=generated_at, expires_at=expires_at),
    ]
    for channel in channels:
        attrs = [
            f'tvg-id="{_m3u_escape(channel["slug"])}"',
            f'tvg-name="{_m3u_escape(channel["name"])}"',
        ]
        if channel.get("logo"):
            attrs.append(f'tvg-logo="{_m3u_escape(channel["logo"])}"')
        if channel.get("channel_no") is not None:
            attrs.append(f'tvg-chno="{channel["channel_no"]}"')
        attrs.append(f'group-title="{_m3u_escape(channel["group"])}"')
        lines.append(f"#EXTINF:-1 {' '.join(attrs)},{_m3u_escape(channel['name'])}")
        lines.append(channel["url"])
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def write_json(path, channels, generated_at, expires_at, scanned, skipped, skip_reasons):
    payload = {
        "source": "dsmartgo.com.tr",
        "generated_at": generated_at,
        "expires_at": expires_at,
        "count": len(channels),
        "scanned": scanned,
        "skipped": skipped,
        "skip_reasons": skip_reasons,
        "channels": [
            {
                "id": channel["id"],
                "name": channel["name"],
                "slug": channel["slug"],
                "channel_no": channel["channel_no"],
                "group": channel["group"],
                "logo": channel["logo"],
                "thumbnail": channel["thumbnail"],
                "base_url": channel["base_url"],
                "url": channel["url"],
                "expires_at": channel["expires_at"],
            }
            for channel in channels
        ],
    }
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def main(argv=None):
    parser = argparse.ArgumentParser(description="Build a D Smart GO channel playlist")
    parser.add_argument("--out-dir", default="playlists", help="output directory")
    parser.add_argument("--workers", type=int, default=8, help="parallel signing requests")
    parser.add_argument("--timeout", type=int, default=30, help="per request timeout")
    parser.add_argument(
        "--min-channels",
        type=int,
        default=5,
        help="fail the run when fewer working channels are found",
    )
    parser.add_argument(
        "--no-verify",
        action="store_true",
        help="skip fetching each signed playlist (faster, less safe)",
    )
    args = parser.parse_args(argv)

    verify = not args.no_verify

    print("[1/4] loading site configuration ...")
    cfg = load_config(timeout=args.timeout)
    print(f"      site-config: {'ok' if cfg['site_config_ok'] else 'fallback constants'}")

    print("[2/4] downloading channel catalogue ...")
    channels, total = fetch_channels(cfg, timeout=args.timeout)
    if not channels:
        print("ERROR: the CMS returned no channels", file=sys.stderr)
        return 1
    print(f"      {len(channels)}/{total} channels found")

    print("[3/4] registering guest session ...")
    guest_id = start_session(cfg, timeout=args.timeout)
    print(f"      guest id: {guest_id or 'skipped'}")

    print(f"[4/4] minting signed streams ({'verifying' if verify else 'no verify'}) ...")
    working, failures = resolve_channels(
        cfg, channels, args.workers, verify, args.timeout
    )
    skipped = sum(failures.values())
    print(f"      {len(working)} working, {skipped} locked/failed")
    for reason, count in sorted(failures.items(), key=lambda pair: -pair[1]):
        print(f"        {count:>3} x {reason}")

    if len(working) < args.min_channels:
        print(
            f"ERROR: only {len(working)} working channels "
            f"(minimum {args.min_channels}) - refusing to write output",
            file=sys.stderr,
        )
        return 1

    generated = datetime.now(timezone.utc).replace(microsecond=0)
    expiries = [
        entry["expires_at"] for entry in working if entry.get("expires_at")
    ]
    expires_at = min(expiries) if expiries else None

    os.makedirs(args.out_dir, exist_ok=True)
    m3u_path = os.path.join(args.out_dir, "dsmart.m3u")
    json_path = os.path.join(args.out_dir, "dsmart.json")

    write_m3u(m3u_path, working, generated.isoformat(), expires_at or "unknown")
    write_json(
        json_path,
        working,
        generated.isoformat(),
        expires_at,
        scanned=len(channels),
        skipped=skipped,
        skip_reasons=failures,
    )

    print(f"wrote {m3u_path} and {json_path}")
    print(f"links valid until {expires_at or 'unknown'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
