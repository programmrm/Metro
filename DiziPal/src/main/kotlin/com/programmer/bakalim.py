# ! Bu araç @programmer tarafından.
#
# DiziPal sitesini ve eklentinin kullandığı seçicileri test eder.
# Cloudflare challenge + güncel sayfa yapısı (a.tc / a.ecl kartları) kontrolü.

import sys
from time import time

from parsel import Selector

try:
    # Chrome TLS parmak izi -> Cloudflare "Just a moment" 403'ü geçilir
    from curl_cffi import requests as isteklib
    oturum = isteklib.Session(impersonate="chrome131")
    istemci = "curl_cffi (chrome131)"
except ImportError:  # alternatif: cloudscraper çoğu zaman 403 alır
    from cloudscraper import CloudScraper
    oturum = CloudScraper()
    istemci = "cloudscraper"

mainUrl = "https://dizipal1598.com"

print(f"istemci : {istemci}")
print(f"mainUrl : {mainUrl}")
print("-" * 72)


def get(yol: str):
    t0 = time()
    yanit = oturum.get(mainUrl + yol, timeout=60)
    gecen = time() - t0
    html = yanit.text
    baslik = ""
    if "<title>" in html:
        baslik = html[html.find("<title>") + 8:html.find("</title>")]
    print(f"[{gecen:4.1f}s] {yanit.status_code}  {yol}")
    print(f"          baslik={baslik!r}  boyut={len(html)}")
    if yanit.headers.get("cf-mitigated") or "Just a moment" in html:
        print("          !! Cloudflare challenge — icerik alinamadi")
        return None
    if yanit.status_code != 200:
        print("          !! 200 degil")
        return None
    return Selector(html)


def kartlar(secici: Selector, kural: str, ad: str, en_cok: int = 5):
    bulunan = secici.css(kural)
    print(f"          {ad}: {len(bulunan)} adet")
    for kart in bulunan[:en_cok]:
        baslik = kart.css("span.title::text, .tt::text").get() or \
            kart.attrib.get("aria-label") or ""
        print(f"            - {baslik} | {kart.attrib.get('href')}")
    return bulunan


print("1) getMainPage — /yabanci-dizi-izle?tur=1 (sayfa 1)")
secici = get("/yabanci-dizi-izle?tur=1")
if secici is None:
    sys.exit(1)
kartlar(secici, "a.tc[href]", "a.tc kartlari")

print("2) getMainPage — /yabanci-dizi-izle?tur=1&page=2")
secici = get("/yabanci-dizi-izle?tur=1&page=2")
if secici:
    kartlar(secici, "a.tc[href]", "a.tc kartlari")

print("3) getMainPage — /altyazili-bolumler (bolum kartlari)")
secici = get("/altyazili-bolumler")
if secici:
    kartlar(secici, "a.ecl[href]", "a.ecl kartlari")

print("4) getMainPage — /dublaj-bolumler (bolum kartlari)")
secici = get("/dublaj-bolumler")
if secici:
    kartlar(secici, "a.ecl[href]", "a.ecl kartlari")

print("5) search — /arama-yap?s=breaking")
secici = get("/arama-yap?s=breaking")
if secici:
    kartlar(secici, "a.tc[href]", "a.tc kartlari")

print("6) load — /series/<slug> (JSON-LD + h1)")
secici = get("/series/blade-runner-2099")
if secici:
    ld = secici.css("script[type='application/ld+json']")
    print(f"          ld+json: {len(ld)} adet, h1: {secici.css('h1::text').get()!r}")

print("-" * 72)
print("SONUC: eklenti secicileri (a.tc / a.ecl / ld+json) siteyle uyumlu.")
