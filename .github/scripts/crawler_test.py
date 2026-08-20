#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FilmHdCehennemi crawler testi.
FilmHdCehennemi.kt plugin'inde uygulanan mantigi birebir kopyalar ve
canli sitede dogrular: liste, arama, detay(bolumler) ve embed(rapidrame) cikarimi.
Calistirma: python3 .github/scripts/crawler_test.py
"""

import base64
import json
import re
import subprocess
import sys
import urllib.parse

BASE = "https://www.hdfilmcehennemi.nl"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"


def fetch(url, referer=None, headers=None):
    cmd = ["curl", "-s", "-L", "-A", UA, "--max-time", "30", "-w", "\n__STATUS__%{http_code}__CTYPE__%{content_type}"]
    if referer:
        cmd += ["-e", referer]
    for k, v in (headers or {}).items():
        cmd += ["-H", f"{k}: {v}"]
    cmd += [url]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    out = proc.stdout
    m = re.search(r"__STATUS__(\d+)__CTYPE__(.*)$", out, re.S)
    status = int(m.group(1)) if m else 0
    ctype = m.group(2) if m else ""
    body = out[:out.rfind("__STATUS__")] if m else out
    return status, ctype, body


def atob_b64(data, step):
    """JS atob mantigi: base64 decode -> latin1 string."""
    if step == "xor":
        # tek atob
        pad = (4 - (len(data) % 4)) % 4
        data += "=" * pad
        return base64.b64decode(data)
    # subtract varyanti: birden fazla atob + reverse
    def decode(raw):
        raw_str = raw if isinstance(raw, str) else raw.decode("latin-1")
        pad = (4 - (len(raw_str) % 4)) % 4
        raw_str += "=" * pad
        return base64.b64decode(raw_str)
    s = data.encode("latin-1")
    s = decode(s)
    s = decode(bytes(reversed(s)))
    s = decode(bytes(reversed(s)))
    s = decode(s)
    return s


def rot_shift(s, n):
    out = []
    for c in s:
        if "a" <= c <= "z":
            out.append(chr((ord(c) - 97 + n) % 26 + 97))
        elif "A" <= c <= "Z":
            out.append(chr((ord(c) - 65 + n) % 26 + 65))
        else:
            out.append(c)
    return "".join(out)


def packer_token(n, a):
    prefix = "" if n < a else packer_token(n // a, a)
    r = n % a
    last = chr(r + 29) if r > 35 else "0123456789abcdefghijklmnopqrstuvwxyz"[r]
    return prefix + last


def decode_packers(html):
    out = html
    while True:
        m = re.search(r"eval\(function\(p,a,c,k,e,d\)\{", out)
        if not m:
            return out
        start = m.start()
        depth = 0
        end = -1
        for j in range(start, len(out)):
            if out[j] == "(":
                depth += 1
            elif out[j] == ")":
                depth -= 1
                if depth == 0:
                    end = j
                    break
        if end == -1:
            return out
        seg = out[start:end + 1]
        mm = re.search(r"\('(.+)',(\d+),(\d+),'(.+)'\.split\('\|'\),\s*0,\s*\{\}\)\)", seg, re.S)
        if not mm:
            return out
        p, a, k, keys = mm.group(1), int(mm.group(2)), int(mm.group(3)), mm.group(4).split("|")
        mapping = {packer_token(i, a): keys[i] for i in range(len(keys)) if keys[i]}
        body = p
        for i in range(len(keys) - 1, -1, -1):
            tok = packer_token(i, a)
            repl = mapping.get(tok, tok)
            if repl != tok:
                body = re.sub(r"\b" + re.escape(tok) + r"\b", repl, body)
        out = out[:start] + body + out[end + 1:]
        if len(out) > 2000000:
            return out


def b64_decode_bytes(data):
    s = data.decode("latin-1", "replace")
    pad = (4 - (len(s) % 4)) % 4
    s += "=" * pad
    return base64.b64decode(s)


def run_js_decode(chunks, body):
    """Obfuscated embed fonksiyonunu sirayla calistirir (varyant bagimsiz)."""
    data = ("".join(chunks).replace("\\/", "/")).encode("latin-1")
    pos = 0
    while True:
        loop_m = re.search(r"for\s*\(\s*let\s*i", body[pos:])
        loop_idx = loop_m.start() + pos if loop_m else -1
        candidates = []
        for kind, tok in (("atob", "atob("), ("rev", "reverse("), ("rot", "replace(/[a-zA-Z]/g")):
            i = body.find(tok, pos)
            if i >= 0:
                candidates.append((i, kind))
        if not candidates:
            break
        i, kind = min(candidates, key=lambda x: x[0])
        if loop_idx >= 0 and i > loop_idx:
            break
        pos = i + 1
        if kind == "atob":
            data = b64_decode_bytes(data)
        elif kind == "rev":
            data = bytes(reversed(data))
        elif kind == "rot":
            m = re.search(r"base\s*\+\s*(\d+)\s*\)\s*%\s*26", body[pos - 1:])
            n = int(m.group(1)) if m else 0
            data = rot_shift(data.decode("latin-1", "replace"), n).encode("latin-1")

    out = []
    if "^" in body and "acc" in body:
        seed = int(re.search(r"acc\s*=\s*(\d+)", body).group(1))
        step = int(re.search(r"acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)", body).group(1))
        acc = seed
        for byte in data:
            acc = (acc + step) % 256
            plain = byte ^ acc
            acc = (acc + byte) % 256
            out.append(chr(plain))
    else:
        seedm = re.search(r"(\d+)\s*%\s*\(\s*i", body)
        seed = int(seedm.group(1)) if seedm else 987647084
        addm = re.search(r"\(\s*i\s*\+\s*(\d+)\s*\)", body)
        addn = int(addm.group(1)) if addm else 18
        for i, byte in enumerate(data):
            delta = seed % (i + addn)
            x = byte - delta
            out.append(chr(((x % 256) + 256) % 256))
    return "".join(out)


def extract_video_url(html):
    decoded = decode_packers(html)
    for assign in re.finditer(r"var\s+(\w+)\s*=\s*(\w+)\s*\(\s*\[([^\]]*)\]", decoded):
        fn = assign.group(2)
        chunks = [c.replace("\\/", "/") for c in re.findall(r'"([^"]+)"', assign.group(3))]
        if not chunks:
            continue
        fm = re.search(r"function\s+" + re.escape(fn) + r"\s*\([^)]*\)\s*\{(.*)\}", decoded, re.S)
        if not fm:
            continue
        try:
            url = run_js_decode(chunks, fm.group(1))
            if url.startswith("http"):
                return url
        except Exception:
            continue
    return None


def parse_list_items(html):
    items = []
    for m in re.finditer(r'<a[^>]*class="poster"[^>]*data-token[^>]*>', html):
        tag = m.group(0)
        href = re.search(r'href="([^"]+)"', tag)
        title = re.search(r'title="([^"]*)"', tag)
        if not href or not title:
            continue
        items.append((href.group(1), title.group(1)))
    return items


def extract_subtitles(html):
    """Embed sayfasindaki JWPlayer 'tracks' JSON'undan .vtt altyazilarini cikarir."""
    subs = []
    seen = set()
    for m in re.finditer(r'"file"\s*:\s*"([^"]+\.vtt[^"]*)"[^}]*?"label"\s*:\s*"([^"]+)"', html):
        raw_url, lang = m.group(1), m.group(2)
        url = raw_url.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
        if url.startswith("http") and url not in seen:
            seen.add(url)
            subs.append((lang, url))
    return subs


def extract_m3u8_subtitles(m3u8_url, referer):
    """Master playlist'teki EXT-X-MEDIA:TYPE=SUBTITLES altyazilarini cikarir."""
    code, ctype, body = fetch(m3u8_url, referer=referer)
    if code != 200:
        return []
    subs = []
    for m in re.finditer(r'#EXT-X-MEDIA:TYPE=SUBTITLES[^#]*?URI="([^"]+)"[^#]*?LANGUAGE="([^"]+)"', body):
        uri, lang = m.group(1), m.group(2)
        if not uri.startswith("http"):
            uri = m3u8_url[:m3u8_url.rfind("/")] + "/" + uri
        subs.append((lang, uri))
    return subs


def main():
    ok = True

    print("== 1) Ana sayfa listesi ==")
    code, ctype, html = fetch(BASE + "/category/film-izle-2/")
    items = parse_list_items(html)
    print(f"   HTTP {code} | poster ogesi: {len(items)}")
    for href, name in items[:5]:
        print(f"   - [{name}] {href}")
    if len(items) < 5:
        ok = False

    print("== 2) Arama ==")
    q = urllib.parse.quote("anna")
    code, ctype, raw = fetch(BASE + f"/search?q={q}", referer=BASE + "/",
                             headers={"X-Requested-With": "fetch", "Content-Type": "application/json"})
    data = json.loads(raw)
    seen = []
    for r in data.get("results", []):
        seen += re.findall(r'<a[^>]*href="([^"]+)"[^>]*class="search-result"|<a[^>]*class="search-result"[^>]*href="([^"]+)"', r)
    seen = [x or y for x, y in seen]
    print(f"   HTTP {code} | sonuc: {len(seen)}")
    for h in seen[:5]:
        print("   -", h)
    if len(seen) < 1:
        ok = False

    print("== 3) Detay (dizi bolumleri) ==")
    series = None
    for href, name in items:
        if "/dizi/" in href:
            series = href
            break
    if not series:
        series = BASE + "/dizi/anna-pigeon/"
    code, ctype, html = fetch(series)
    ld = re.search(r'<script[^>]*application/ld\+json[^>]*>(.*?)</script>', html, re.S)
    name = episodes = None
    doc = {}
    if ld:
        doc = json.loads(ld.group(1))
        name = doc.get("name")
        seasons = doc.get("containsSeason") or []
        episodes = [(s.get("seasonNumber"), e.get("episodeNumber"), e.get("name"), e.get("url"))
                    for s in seasons for e in (s.get("episode") or [])]
    print(f"   tip: {doc.get('@type') if ld else 'bilinmiyor'} | baslik: {name} | bolum: {len(episodes) if episodes else 0}")
    if episodes:
        s, n, en, eu = episodes[0]
        print(f"   ilk bolum: {s}.sezon {n}.bolum -> {eu}")
        ep_url = eu
    else:
        ep_url = None
        ok = False

    print("== 4) Video/embed cikarimi ==")
    if ep_url:
        code, ctype, html = fetch(ep_url)
        iframe = re.search(r'<iframe[^>]*data-src="([^"]+)"', html) or re.search(r'<iframe[^>]*src="([^"]+)"', html)
        embed = iframe.group(1) if iframe else None
        print(f"   iframe: {embed}")
        if embed:
            code, ctype, ep_html = fetch(embed, referer=ep_url)
            video = extract_video_url(ep_html)
            print(f"   video: {video}")
            if video and video.startswith("http"):
                vcode, vtype, _ = fetch(video, referer=embed)
                print(f"   video kontrol -> HTTP {vcode} type={vtype}")
                if vcode != 200:
                    ok = False
            else:
                print("   ! video cikarilamadi")
                ok = False
    else:
        code, ctype, html = fetch(BASE + "/here-the-whole-time-2026/")
        iframe = re.search(r'<iframe[^>]*data-src="([^"]+)"', html)
        embed = iframe.group(1) if iframe else None
        print(f"   film iframe: {embed}")
        if embed:
            code, ctype, m_html = fetch(embed, referer=BASE + "/")
            video = extract_video_url(m_html)
            print(f"   video: {video}")
            if video and video.startswith("http"):
                vcode, vtype, _ = fetch(video, referer=embed)
                print(f"   video kontrol -> HTTP {vcode} type={vtype}")
                if vcode != 200:
                    ok = False
            else:
                ok = False

    print("== 5) Altyazi (subtitle) cikarimi ==")
    sub_items = []
    # Film uzerinden .vtt track + m3u8 subtitle kontrol (filmlerde altyazi mevcut)
    code, ctype, html = fetch(BASE + "/here-the-whole-time-2026/")
    iframe = re.search(r'<iframe[^>]*data-src="([^"]+)"', html)
    embed = iframe.group(1) if iframe else None
    print(f"   film iframe: {embed}")
    if embed:
        code, ctype, raw = fetch(embed, referer=BASE + "/")
        sub_items += extract_subtitles(raw)
        video = extract_video_url(raw)
        print(f"   film video: {video}")
        if video:
            sub_items += extract_m3u8_subtitles(video, embed)
    unique_subs = {}
    for lang, url in sub_items:
        unique_subs.setdefault(url, lang)
    print(f"   altyazi sayisi: {len(unique_subs)}")
    for url, lang in list(unique_subs.items())[:5]:
        print(f"   - [{lang}] {url}")
    if unique_subs:
        first_url = next(iter(unique_subs))
        scode, stype, _ = fetch(first_url, referer=embed or BASE + "/")
        print(f"   vtt kontrol -> HTTP {scode} type={stype}")
        if scode != 200:
            ok = False
    else:
        print("   ! altyazi cikarilamadi")
        ok = False

    print()
    print("SONUC:", "BASARILI" if ok else "BASARISIZ")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())