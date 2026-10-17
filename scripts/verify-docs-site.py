"""Check generated Pages links and assets without accessing the network."""

from __future__ import annotations

from html.parser import HTMLParser
import json
from pathlib import Path
import sys
from urllib.parse import unquote, urljoin, urlsplit


ROOT = Path(__file__).resolve().parents[1]
BASE_URL = "https://xiaomizhoumc.github.io/DialogCanvas/"


class Page(HTMLParser):
    def __init__(self, html: str):
        super().__init__(convert_charrefs=True)
        self.targets: list[str] = []
        self.ids: set[str] = set()
        self.feed(html)

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        if values.get("id"):
            self.ids.add(values["id"])
        if tag in {"a", "link"} and values.get("href"):
            self.targets.append(values["href"])
        if tag in {"img", "script"} and values.get("src"):
            self.targets.append(values["src"])


def main() -> int:
    site = (ROOT / (sys.argv[1] if len(sys.argv) > 1 else "site")).resolve()
    if not (site / "index.html").is_file():
        raise SystemExit("Build the MkDocs website first.")
    pages = {path.resolve(): Page(path.read_text(encoding="utf-8")) for path in site.rglob("*.html")}
    errors = []
    checked = 0
    base = urlsplit(BASE_URL)
    for path, page in pages.items():
        page_url = BASE_URL + path.relative_to(site).as_posix()
        for target in page.targets:
            url = urlsplit(urljoin(page_url, target))
            if url.scheme not in {"http", "https"} or url.netloc != base.netloc:
                continue
            checked += 1
            if not url.path.startswith(base.path):
                errors.append(f"{path.relative_to(site)}: link escapes project site: {target}")
                continue
            destination = (site / unquote(url.path[len(base.path):])).resolve()
            if not destination.is_relative_to(site):
                errors.append(f"{path.relative_to(site)}: invalid target: {target}")
                continue
            if destination.is_dir():
                destination /= "index.html"
            if not destination.is_file():
                errors.append(f"{path.relative_to(site)}: missing file: {target}")
            elif url.fragment and destination in pages and unquote(url.fragment) not in pages[destination].ids:
                errors.append(f"{path.relative_to(site)}: missing anchor: {target}")
    report = {"htmlPages": len(pages), "localTargetsChecked": checked, "errors": errors,
              "scope": "Generated internal links, anchors, CSS/JS and images; external URLs not fetched"}
    output = ROOT / "build-reports/docs-site-validation.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
