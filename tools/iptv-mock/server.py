"""Local IPTV test server: an M3U playlist and a fake Xtream Codes panel, both backed by
free Pluto TV channels (iptv-org list) and the matching public Pluto XMLTV guide.

The Android emulator reaches this host at http://10.0.2.2:8090
  M3U:    http://10.0.2.2:8090/playlist.m3u      (guide via url-tvg)
  Xtream: server http://10.0.2.2:8090  user test  pass test
"""
import gzip
import json
import pathlib
import re
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = pathlib.Path(__file__).parent
HOST = "http://10.0.2.2:8090"
USER, PASS = "test", "test"


def fetch(url, dest):
    path = HERE / dest
    if not path.exists():
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        path.write_bytes(urllib.request.urlopen(req, timeout=60).read())
    return path.read_bytes()


playlist_src = fetch("https://iptv-org.github.io/iptv/countries/us.m3u", "us.m3u").decode("utf-8", "replace")
epg_gz = fetch("https://i.mjh.nz/PlutoTV/us.xml.gz", "pluto_us.xml.gz")
epg_xml = gzip.decompress(epg_gz)
epg_ids = set(re.findall(rb'<channel id="([^"]+)"', epg_xml))

channels = []
lines = playlist_src.splitlines()
for i, line in enumerate(lines):
    if not line.startswith("#EXTINF") or i + 1 >= len(lines):
        continue
    url = lines[i + 1].strip()
    m = re.search(r"jmp2\.uk/plu-([0-9a-f]+)\.m3u8", url)
    if not m or m.group(1).encode() not in epg_ids:
        continue
    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
    channels.append({
        "pluto": m.group(1),
        "name": line.rsplit(",", 1)[-1].strip(),
        "logo": attrs.get("tvg-logo", ""),
        "group": (attrs.get("group-title") or "Uncategorized").split(";")[0],
        "url": url,
    })
groups = sorted({c["group"] for c in channels})

# VOD: public-domain films and one series from the Internet Archive (vod.json / series.json).
def _load(name):
    path = HERE / name
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else []
movies = [m for m in _load("vod.json") if m["id"] != "3stooges"]
series = _load("series.json")
MOVIE_CATS = ["Classics", "Comedy", "Thriller"]
def movie_cat(i): return str(100 + i % len(MOVIE_CATS))

print(f"{len(channels)} channels in {len(groups)} groups with guide data", flush=True)


def m3u():
    out = [f'#EXTM3U url-tvg="{HOST}/epg.xml.gz"']
    for n, c in enumerate(channels, 1):
        out.append(f'#EXTINF:-1 tvg-id="{c["pluto"]}" tvg-chno="{n}" tvg-logo="{c["logo"]}" group-title="{c["group"]}",{c["name"]}')
        out.append(c["url"])
    return "\n".join(out).encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send(self, code, body=b"", ctype="application/json", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        authed = q.get("username") == USER and q.get("password") == PASS
        if u.path == "/playlist.m3u":
            return self.send(200, m3u(), "audio/x-mpegurl")
        if u.path == "/epg.xml.gz":
            return self.send(200, epg_gz, "application/gzip")
        if u.path == "/xmltv.php":
            return self.send(200 if authed else 401, epg_xml if authed else b"", "application/xml")
        if u.path == "/player_api.php":
            if not authed:
                return self.send(200, json.dumps({"user_info": {"auth": 0}}).encode())
            action = q.get("action")
            if action is None:
                body = {"user_info": {"username": USER, "auth": 1, "status": "Active", "max_connections": "1",
                                      "allowed_output_formats": ["m3u8"]},
                        "server_info": {"url": "10.0.2.2", "port": "8090", "server_protocol": "http"}}
            elif action == "get_live_categories":
                body = [{"category_id": str(i + 1), "category_name": g, "parent_id": 0} for i, g in enumerate(groups)]
            elif action == "get_live_streams":
                body = [{"num": n, "name": c["name"], "stream_type": "live", "stream_id": n, "stream_icon": c["logo"],
                         "epg_channel_id": c["pluto"], "category_id": str(groups.index(c["group"]) + 1),
                         "tv_archive": 0, "tv_archive_duration": 0}
                        for n, c in enumerate(channels, 1)]
            elif action == "get_vod_categories":
                body = [{"category_id": str(100 + i), "category_name": n, "parent_id": 0} for i, n in enumerate(MOVIE_CATS)]
            elif action == "get_vod_streams":
                body = [{"num": i + 1, "name": m["title"], "stream_type": "movie", "stream_id": 5000 + i,
                         "stream_icon": m["image"], "rating": "7.1", "added": "1700000000",
                         "category_id": movie_cat(i), "container_extension": "mp4"}
                        for i, m in enumerate(movies) if q.get("category_id") in (None, movie_cat(i))]
            elif action == "get_vod_info":
                i = int(q.get("vod_id", "0")) - 5000
                m = movies[i]
                body = {"info": {"name": m["title"], "movie_image": m["image"], "backdrop_path": [m["image"]],
                                 "plot": m["plot"], "releasedate": m["year"] + "-01-01", "duration_secs": m["secs"],
                                 "genre": MOVIE_CATS[i % len(MOVIE_CATS)], "rating": "7.1", "cast": "", "director": ""},
                        "movie_data": {"stream_id": 5000 + i, "name": m["title"], "container_extension": "mp4"}}
            elif action == "get_series_categories":
                body = [{"category_id": "200", "category_name": "Classic TV", "parent_id": 0}]
            elif action == "get_series":
                body = [{"num": i + 1, "name": sr["title"], "series_id": 7000 + i, "cover": sr["image"],
                         "plot": "Slapstick shorts.", "releaseDate": sr["year"] or "1935", "rating": "8",
                         "backdrop_path": [sr["image"]], "category_id": "200"} for i, sr in enumerate(series)]
            elif action == "get_series_info":
                sr = series[int(q.get("series_id", "0")) - 7000]
                eps = {}
                for n, e in enumerate(sr["episodes"]):
                    season = 1 + n // 2
                    eps.setdefault(str(season), []).append({
                        "id": str(8000 + n), "episode_num": n % 2 + 1, "title": e["title"], "season": season,
                        "container_extension": "mp4",
                        "info": {"movie_image": sr["image"], "plot": "", "duration_secs": e["secs"]}})
                body = {"info": {"name": sr["title"], "cover": sr["image"], "plot": "Slapstick shorts.",
                                 "backdrop_path": [sr["image"]], "releaseDate": sr["year"], "rating": "8"},
                        "seasons": [{"season_number": int(k), "name": f"Season {k}"} for k in eps],
                        "episodes": eps}
            else:
                body = []
            return self.send(200, json.dumps(body).encode())
        mv = re.match(rf"/movie/{USER}/{PASS}/(\d+)\.\w+$", u.path)
        if mv:
            return self.send(302, headers={"Location": movies[int(mv.group(1)) - 5000]["file"]})
        ep = re.match(rf"/series/{USER}/{PASS}/(\d+)\.\w+$", u.path)
        if ep:
            n = int(ep.group(1)) - 8000
            all_eps = [e for sr in series for e in sr["episodes"]]
            return self.send(302, headers={"Location": all_eps[n]["file"]})
        m = re.match(rf"/live/{USER}/{PASS}/(\d+)\.\w+$", u.path)
        if m and 1 <= int(m.group(1)) <= len(channels):
            return self.send(302, headers={"Location": channels[int(m.group(1)) - 1]["url"]})
        self.send(404, b"not found", "text/plain")

    def log_message(self, fmt, *args):
        sys.stdout.write("%s\n" % (fmt % args))
        sys.stdout.flush()


ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
