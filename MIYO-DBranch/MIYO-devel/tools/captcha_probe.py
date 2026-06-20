#!/usr/bin/env python3
"""
Probe manga source URLs with a persistent cookie jar and report captcha state.

This is a developer/CI diagnostic tool. It does not solve or bypass captcha.
It verifies whether stored cookies are still accepted by a provider, writes the
cookie jar with owner-only permissions, and can watch URLs continuously.
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


DEFAULT_COOKIE_JAR = Path.home() / ".local/state/miyo/captcha-probe/cookies.txt"
DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
)
MAX_BODY_BYTES = 512_000
DEFAULT_EXPIRY_WARNING_SECONDS = 60 * 60

STRONG_CHALLENGE_MARKERS = (
    "just a moment",
    "performing security verification",
    "verification successful",
    "checking your browser",
    "checking if the site connection is secure",
    "cf-chl",
    "__cf_chl_tk",
    "cdn-cgi/challenge-platform",
    "turnstile",
    "hcaptcha",
    "recaptcha",
)

SOFT_CHALLENGE_MARKERS = (
    "cloudflare",
    "security service",
    "verify you are not a bot",
    "captcha",
    "ray id",
)

CLEARANCE_COOKIES = (
    "cf_clearance",
    "__cf_bm",
    "__cflb",
    "__cfseq",
)


@dataclass(frozen=True)
class CookieSummary:
    name: str
    domain: str
    path: str
    expires_utc: str | None
    seconds_until_expiry: int | None


@dataclass(frozen=True)
class ProbeResult:
    url: str
    final_url: str | None
    status_code: int | None
    state: str
    has_clearance_cookie: bool
    cookie_count: int
    challenge_markers: list[str]
    clearance_cookies: list[CookieSummary]
    redirect_chain: list[dict[str, Any]]
    content_type: str | None
    server: str | None
    cf_ray: str | None
    expires_soon: bool
    error: str | None = None

    def to_json(self) -> str:
        return json.dumps(
            {
                "time": datetime.now(timezone.utc).isoformat(),
                "url": self.url,
                "final_url": self.final_url,
                "status_code": self.status_code,
                "state": self.state,
                "has_clearance_cookie": self.has_clearance_cookie,
                "cookie_count": self.cookie_count,
                "challenge_markers": self.challenge_markers,
                "clearance_cookies": [cookie.__dict__ for cookie in self.clearance_cookies],
                "redirect_chain": self.redirect_chain,
                "content_type": self.content_type,
                "server": self.server,
                "cf_ray": self.cf_ray,
                "expires_soon": self.expires_soon,
                "error": self.error,
            },
            sort_keys=True,
        )


class TrackingRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self) -> None:
        super().__init__()
        self.chain: list[dict[str, Any]] = []

    def reset(self) -> None:
        self.chain.clear()

    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> urllib.request.Request | None:
        self.chain.append(
            {
                "from": req.full_url,
                "to": newurl,
                "status": code,
            },
        )
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("urls", nargs="*", help="Provider URLs to probe")
    parser.add_argument("--file", help="Text file containing one URL per line")
    parser.add_argument("--cookie-jar", default=str(DEFAULT_COOKIE_JAR), help="Mozilla cookie jar path")
    parser.add_argument("--cookie-header", help="Extra Cookie header to send without writing it to the jar")
    parser.add_argument("--dump-cookie-header", action="store_true", help="Print the reusable Cookie header")
    parser.add_argument("--expiry-warning", type=int, default=DEFAULT_EXPIRY_WARNING_SECONDS)
    parser.add_argument("--watch", action="store_true", help="Probe forever")
    parser.add_argument("--interval", type=float, default=60.0, help="Watch interval in seconds")
    parser.add_argument("--timeout", type=float, default=25.0, help="Request timeout in seconds")
    parser.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    parser.add_argument("--referer", help="Optional Referer header")
    parser.add_argument("--json", action="store_true", help="Emit JSON lines only")
    return parser.parse_args()


def read_urls(args: argparse.Namespace) -> list[str]:
    urls = list(args.urls)
    if args.file:
        for line in Path(args.file).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                urls.append(line)
    normalized = []
    for url in urls:
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise SystemExit(f"invalid URL: {url}")
        normalized.append(url)
    if not normalized:
        raise SystemExit("provide at least one URL or --file")
    return normalized


def load_cookie_jar(path: Path) -> http.cookiejar.MozillaCookieJar:
    path.parent.mkdir(parents=True, exist_ok=True)
    jar = http.cookiejar.MozillaCookieJar(str(path))
    if path.exists() and path.stat().st_size > 0:
        jar.load(ignore_discard=True, ignore_expires=False)
    jar.clear_expired_cookies()
    save_cookie_jar(jar, path)
    return jar


def save_cookie_jar(jar: http.cookiejar.MozillaCookieJar, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    jar.save(ignore_discard=True, ignore_expires=False)
    os.chmod(path, 0o600)


def host_matches(cookie_domain: str, host: str) -> bool:
    domain = cookie_domain.lstrip(".").lower()
    host = host.lower()
    return host == domain or host.endswith("." + domain)


def clearance_cookies(jar: http.cookiejar.CookieJar, url: str) -> list[CookieSummary]:
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname or ""
    now = time.time()
    cookies: list[CookieSummary] = []
    for cookie in jar:
        if not host_matches(cookie.domain, host):
            continue
        if cookie.expires is not None and cookie.expires <= now:
            continue
        if cookie.name not in CLEARANCE_COOKIES and not cookie.name.startswith("__cf"):
            continue
        expires_utc = None
        seconds_until_expiry = None
        if cookie.expires is not None:
            seconds_until_expiry = int(cookie.expires - now)
            expires_utc = datetime.fromtimestamp(cookie.expires, timezone.utc).isoformat()
        cookies.append(
            CookieSummary(
                name=cookie.name,
                domain=cookie.domain,
                path=cookie.path,
                expires_utc=expires_utc,
                seconds_until_expiry=seconds_until_expiry,
            ),
        )
    return cookies


def detect_challenge_markers(body: bytes, headers: Iterable[tuple[str, str]]) -> list[str]:
    header_text = "\n".join(f"{key}: {value}" for key, value in headers).lower()
    text = body[:MAX_BODY_BYTES].decode("utf-8", errors="ignore").lower()
    haystack = header_text + "\n" + text
    markers = [
        marker
        for marker in (*STRONG_CHALLENGE_MARKERS, *SOFT_CHALLENGE_MARKERS)
        if marker in haystack
    ]
    return sorted(set(markers))


def is_challenge_response(status_code: int | None, markers: list[str]) -> bool:
    strong = any(marker in STRONG_CHALLENGE_MARKERS for marker in markers)
    soft = any(marker in SOFT_CHALLENGE_MARKERS for marker in markers)
    return strong or (soft and status_code in {401, 403, 429, 503})


def build_opener(
    jar: http.cookiejar.CookieJar,
) -> tuple[urllib.request.OpenerDirector, TrackingRedirectHandler]:
    redirect_handler = TrackingRedirectHandler()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar),
        redirect_handler,
    )
    return opener, redirect_handler


def request_cookie_header(jar: http.cookiejar.CookieJar, url: str) -> str:
    request = urllib.request.Request(url)
    jar.add_cookie_header(request)
    return request.get_header("Cookie") or ""


def response_header(headers: Any, name: str) -> str | None:
    return headers.get(name) or headers.get(name.lower()) or headers.get(name.upper())


def network_error_result(
    url: str,
    jar: http.cookiejar.MozillaCookieJar,
    error: Exception,
) -> ProbeResult:
    cookies = clearance_cookies(jar, url)
    return ProbeResult(
        url=url,
        final_url=None,
        status_code=None,
        state="network_error",
        has_clearance_cookie=bool(cookies),
        cookie_count=len(jar),
        challenge_markers=[],
        clearance_cookies=cookies,
        redirect_chain=[],
        content_type=None,
        server=None,
        cf_ray=None,
        expires_soon=False,
        error=f"{type(error).__name__}: {error}",
    )


def probe_url(
    opener: urllib.request.OpenerDirector,
    redirect_handler: TrackingRedirectHandler,
    jar: http.cookiejar.MozillaCookieJar,
    url: str,
    timeout: float,
    user_agent: str,
    referer: str | None,
    cookie_header: str | None,
    expiry_warning: int,
) -> ProbeResult:
    redirect_handler.reset()
    before_clearance = clearance_cookies(jar, url)
    headers = {
        "User-Agent": user_agent,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Cache-Control": "no-cache",
        **({"Referer": referer} if referer else {}),
        **({"Cookie": cookie_header} if cookie_header else {}),
    }
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with opener.open(request, timeout=timeout) as response:
            body = response.read(MAX_BODY_BYTES)
            final_url = response.geturl()
            status_code = response.getcode()
            response_headers = response.headers
    except urllib.error.HTTPError as error:
        body = error.read(MAX_BODY_BYTES)
        final_url = error.geturl()
        status_code = error.code
        response_headers = error.headers
    except Exception as error:
        return network_error_result(url, jar, error)

    after_clearance = clearance_cookies(jar, final_url or url)
    markers = detect_challenge_markers(body, response_headers.items())
    challenge = is_challenge_response(status_code, markers)
    expires_soon = any(
        cookie.seconds_until_expiry is not None and cookie.seconds_until_expiry <= expiry_warning
        for cookie in after_clearance
    )
    if challenge and before_clearance:
        state = "cookie_rejected_or_expired"
    elif challenge:
        state = "needs_verification"
    elif after_clearance and expires_soon:
        state = "verified_expires_soon"
    elif after_clearance:
        state = "verified"
    elif status_code is not None and status_code >= 500:
        state = "server_error_no_challenge_cookie"
    elif status_code is not None and status_code >= 400:
        state = "http_error_no_challenge_cookie"
    else:
        state = "ok_no_challenge_cookie"

    return ProbeResult(
        url=url,
        final_url=final_url,
        status_code=status_code,
        state=state,
        has_clearance_cookie=bool(after_clearance),
        cookie_count=len(jar),
        challenge_markers=markers,
        clearance_cookies=after_clearance,
        redirect_chain=list(redirect_handler.chain),
        content_type=response_header(response_headers, "Content-Type"),
        server=response_header(response_headers, "Server"),
        cf_ray=response_header(response_headers, "CF-Ray"),
        expires_soon=expires_soon,
    )


def print_result(
    result: ProbeResult,
    json_only: bool,
    dump_cookie_header: bool,
    cookie_header: str,
) -> None:
    print(result.to_json(), flush=True)
    if json_only:
        return
    if result.state in {"needs_verification", "cookie_rejected_or_expired"}:
        print(f"action: open this URL in the app captcha WebView again: {result.url}", flush=True)
    elif result.state == "verified_expires_soon":
        print("action: cookie is currently accepted but expires soon; refresh it before long jobs", flush=True)
    if dump_cookie_header:
        print(f"cookie_header: {cookie_header}", flush=True)


def main() -> int:
    args = parse_args()
    urls = read_urls(args)
    cookie_path = Path(args.cookie_jar).expanduser()
    jar = load_cookie_jar(cookie_path)
    opener, redirect_handler = build_opener(jar)

    while True:
        for url in urls:
            result = probe_url(
                opener=opener,
                redirect_handler=redirect_handler,
                jar=jar,
                url=url,
                timeout=args.timeout,
                user_agent=args.user_agent,
                referer=args.referer,
                cookie_header=args.cookie_header,
                expiry_warning=args.expiry_warning,
            )
            save_cookie_jar(jar, cookie_path)
            print_result(
                result=result,
                json_only=args.json,
                dump_cookie_header=args.dump_cookie_header,
                cookie_header=request_cookie_header(jar, result.final_url or result.url),
            )
        if not args.watch:
            break
        time.sleep(max(args.interval, 1.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
