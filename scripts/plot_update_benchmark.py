#!/usr/bin/env python3
"""Create the fixed-shape epoch-update evaluation figure."""

import argparse
import csv
import math
from pathlib import Path

from reportlab.lib.colors import HexColor
from reportlab.pdfgen import canvas


WIDTH = 7.05 * 72
HEIGHT = 2.55 * 72
COLORS = ["#0072B2", "#D55E00", "#009E73"]


def read_rows(path):
    with path.open(newline="", encoding="utf-8") as stream:
        return [{key: float(value) for key, value in row.items()}
                for row in csv.DictReader(stream)]


def marker(pdf, x, y, color, shape):
    pdf.setFillColor(HexColor(color))
    pdf.setStrokeColor(HexColor(color))
    if shape == 0:
        pdf.circle(x, y, 2.3, fill=1, stroke=1)
    elif shape == 1:
        pdf.rect(x - 2.3, y - 2.3, 4.6, 4.6, fill=1, stroke=1)
    else:
        path = pdf.beginPath()
        path.moveTo(x, y + 2.8)
        path.lineTo(x - 2.6, y - 2.1)
        path.lineTo(x + 2.6, y - 2.1)
        path.close()
        pdf.drawPath(path, fill=1, stroke=1)


def panel(pdf, origin, labels, series, title, ylabel, log_y=False):
    panel_width = WIDTH / 2
    left, right = origin + 43, origin + panel_width - 8
    bottom, top = 30, HEIGHT - 22
    transformed = []
    for name, values in series:
        transformed.append((name, [math.log10(value) if log_y else value
                                   for value in values]))
    all_values = [value for _, values in transformed for value in values]
    low = min(all_values)
    high = max(all_values)
    if not log_y:
        low = 0
        high *= 1.12
    else:
        low = math.floor(low)
        high = math.ceil(high)
    span = max(high - low, 1e-9)

    pdf.setFillColor(HexColor("#111111"))
    pdf.setFont("Times-Bold", 8.5)
    pdf.drawCentredString(origin + panel_width / 2, HEIGHT - 10, title)
    for tick in range(5):
        value = low + span * tick / 4
        y = bottom + (top - bottom) * tick / 4
        pdf.setStrokeColor(HexColor("#D9D9D9"))
        pdf.setLineWidth(0.45)
        pdf.line(left, y, right, y)
        label = f"10^{value:.0f}" if log_y else f"{value:.1f}"
        pdf.setFont("Times-Roman", 6.8)
        pdf.setFillColor(HexColor("#333333"))
        pdf.drawRightString(left - 4, y - 2, label)
    pdf.setStrokeColor(HexColor("#333333"))
    pdf.line(left, bottom, left, top)
    pdf.line(left, bottom, right, bottom)
    xs = []
    for index, label in enumerate(labels):
        x = left + (right - left) * index / max(1, len(labels) - 1)
        xs.append(x)
        pdf.setFont("Times-Roman", 6.7)
        pdf.drawCentredString(x, bottom - 10, label)
    pdf.saveState()
    pdf.translate(origin + 9, HEIGHT / 2)
    pdf.rotate(90)
    pdf.setFont("Times-Roman", 7.2)
    pdf.drawCentredString(0, 0, ylabel)
    pdf.restoreState()

    for index, (name, values) in enumerate(transformed):
        color = COLORS[index]
        points = [(xs[i], bottom + (top - bottom) * (value - low) / span)
                  for i, value in enumerate(values)]
        pdf.setStrokeColor(HexColor(color))
        pdf.setLineWidth(1.2)
        path = pdf.beginPath()
        path.moveTo(*points[0])
        for point in points[1:]:
            path.lineTo(*point)
        pdf.drawPath(path, fill=0, stroke=1)
        for point in points:
            marker(pdf, *point, color, index)
        legend_y = top - 8 - index * 10
        pdf.line(left + 5, legend_y, left + 17, legend_y)
        marker(pdf, left + 11, legend_y, color, index)
        pdf.setFillColor(HexColor("#222222"))
        pdf.setFont("Times-Roman", 6.8)
        pdf.drawString(left + 22, legend_y - 2.2, name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("summary", type=Path)
    parser.add_argument("--output", type=Path,
                        default=Path("figures/update-benchmark.pdf"))
    args = parser.parse_args()
    rows = read_rows(args.summary)
    labels = [f"2^{int(round(math.log2(row['records'])))}" for row in rows]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(args.output), pagesize=(WIDTH, HEIGHT))
    panel(pdf, 0, labels, [
        ("Owner token", [row["owner_update_ms"] for row in rows]),
        ("Server apply", [row["server_apply_ms"] for row in rows]),
        ("Client apply", [row["client_apply_ms"] for row in rows]),
    ], "(a) One-record epoch transition", "Time (ms)")
    panel(pdf, WIDTH / 2, labels, [
        ("Client update", [row["client_update_bytes"] for row in rows]),
        ("Full registration", [row["full_client_state_bytes"] for row in rows]),
    ], "(b) Authenticated client delivery", "Bytes (log scale)", True)
    pdf.showPage()
    pdf.save()


if __name__ == "__main__":
    main()
