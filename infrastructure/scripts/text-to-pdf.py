#!/usr/bin/env python3
"""Convert a UTF-8 text file into a simple multi-page PDF. Architecture §36 (document fixtures).

Written in-repo with no third-party dependency on purpose: the demo fixtures must be reproducible on
any machine with a stock Python, and pulling reportlab in just to build two demo files would add a
dependency to the repo that nothing else needs.

Emits a real text-layer PDF using the base-14 Helvetica font, so PDFBox extracts genuine text WITH
coordinates. That matters for the demo: a PDF built as page images would parse to zero spans and the
comparison would report every requirement MISSING (§17.5), which would look like a product bug rather
than a fixture problem.
"""
import sys
from pathlib import Path

# US Letter in PDF points.
PAGE_WIDTH, PAGE_HEIGHT = 612, 792
MARGIN_LEFT, MARGIN_TOP = 62, 730
FONT_SIZE, LEADING = 11, 15.5
LINES_PER_PAGE = int((MARGIN_TOP - 60) / LEADING)
MAX_CHARS = 92


def escape(text: str) -> str:
    """Escapes the three characters that terminate or nest a PDF string literal."""
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def wrap(line: str) -> list[str]:
    """Greedy word wrap. Preserves blank lines, which carry paragraph structure."""
    if not line.strip():
        return [""]
    words, out, current = line.split(), [], ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if len(candidate) <= MAX_CHARS:
            current = candidate
        else:
            out.append(current)
            current = word
    if current:
        out.append(current)
    return out


def paginate(text: str) -> list[list[str]]:
    lines: list[str] = []
    for raw in text.splitlines():
        lines.extend(wrap(raw))
    return [lines[i:i + LINES_PER_PAGE] for i in range(0, len(lines), LINES_PER_PAGE)] or [[""]]


def build(text: str) -> bytes:
    pages = paginate(text)

    # Object 1 = Catalog, 2 = Pages, 3 = Font, then one Page + one Contents per page.
    objects: dict[int, bytes] = {}
    first_page_obj = 4
    page_ids = [first_page_obj + i * 2 for i in range(len(pages))]

    kids = " ".join(f"{pid} 0 R" for pid in page_ids)
    objects[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objects[2] = (
        f"<< /Type /Pages /Count {len(pages)} /Kids [{kids}] >>".encode()
    )
    objects[3] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"

    for index, page_lines in enumerate(pages):
        page_id = page_ids[index]
        contents_id = page_id + 1

        stream = ["BT", f"/F1 {FONT_SIZE} Tf", f"{LEADING} TL",
                  f"1 0 0 1 {MARGIN_LEFT} {MARGIN_TOP} Tm"]
        for line in page_lines:
            # Tj then T* — emit the text, then advance one line. A single TD per line would
            # accumulate rounding drift down the page.
            stream.append(f"({escape(line)}) Tj")
            stream.append("T*")
        stream.append("ET")
        body = "\n".join(stream).encode("latin-1", "replace")

        objects[page_id] = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {PAGE_WIDTH} {PAGE_HEIGHT}] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {contents_id} 0 R >>"
        ).encode()
        objects[contents_id] = (
            b"<< /Length " + str(len(body)).encode() + b" >>\nstream\n" + body + b"\nendstream"
        )

    out = bytearray(b"%PDF-1.4\n")
    offsets: dict[int, int] = {}
    for obj_id in sorted(objects):
        offsets[obj_id] = len(out)
        out += f"{obj_id} 0 obj\n".encode() + objects[obj_id] + b"\nendobj\n"

    # xref must list every object contiguously from 0, and byte offsets must be exact — a reader
    # rejects the file outright if they are not.
    xref_offset = len(out)
    count = max(objects) + 1
    out += f"xref\n0 {count}\n".encode()
    out += b"0000000000 65535 f \n"
    for obj_id in range(1, count):
        out += f"{offsets[obj_id]:010d} 00000 n \n".encode()
    out += (
        f"trailer\n<< /Size {count} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode()
    )
    return bytes(out)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: text-to-pdf.py <input.txt> <output.pdf>", file=sys.stderr)
        raise SystemExit(2)
    source, target = Path(sys.argv[1]), Path(sys.argv[2])
    target.write_bytes(build(source.read_text(encoding="utf-8")))
    print(f"{target} ({target.stat().st_size} bytes)")
