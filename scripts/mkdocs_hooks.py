"""Keep repository-relative Wiki links useful in the published documentation."""

from __future__ import annotations

from pathlib import Path
import re
from urllib.parse import quote, unquote, urlsplit, urlunsplit


# Existing Wiki files use inline Markdown links, including relative links to
# technical documents and YAML examples outside docs_dir. Keep source links
# unchanged for GitHub readers; only transform those destinations at build time.
INLINE_LINK = re.compile(r"(?P<start>\]\()(?P<target>[^\s)]+)(?P<end>\))")
FENCED_BLOCK = re.compile(r"(^[ \t]*```[^\n]*\n.*?^[ \t]*```[^\n]*$)", re.MULTILINE | re.DOTALL)


def on_page_markdown(markdown, *, page, config, files):
    docs_root = Path(config["docs_dir"]).resolve()
    repository_root = Path(config["config_file_path"]).resolve().parent
    source = Path(page.file.abs_src_path)
    repository_url = config["repo_url"].rstrip("/")

    def rewrite(match):
        destination = urlsplit(match["target"])
        if destination.scheme or destination.netloc or not destination.path:
            return match[0]
        if destination.path.startswith("/"):
            return match[0]
        target = (source.parent / unquote(destination.path)).resolve()
        if target.is_relative_to(docs_root):
            return match[0]
        if not target.is_relative_to(repository_root) or not target.exists():
            raise ValueError(f"Wiki link target is missing or outside repository: {source.name}: {match['target']}")
        kind = "tree" if target.is_dir() else "blob"
        relative = quote(target.relative_to(repository_root).as_posix(), safe="/")
        url = urlsplit(f"{repository_url}/{kind}/main/{relative}")
        url = urlunsplit((url.scheme, url.netloc, url.path, destination.query, destination.fragment))
        return f"{match['start']}{url}{match['end']}"

    # Do not transform sample Markdown displayed inside fenced code blocks.
    pieces = FENCED_BLOCK.split(markdown)
    return "".join(piece if index % 2 else INLINE_LINK.sub(rewrite, piece) for index, piece in enumerate(pieces))
