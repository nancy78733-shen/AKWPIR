#!/usr/bin/env python3
"""Plot payload failure margins and registered-client state scaling."""

import argparse
import csv
import math
from pathlib import Path

from reportlab.lib.colors import HexColor
from reportlab.pdfgen import canvas


COLORS = ["#0072B2", "#D55E00"]
MARKERS = ["circle", "square"]
PAGE_WIDTH = 7.05 * 72.0
PAGE_HEIGHT = 2.55 * 72.0


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("payload_summary", type=Path)
    parser.add_argument("registration_summary", type=Path)
    parser.add_argument("--output", type=Path,
                        default=Path("figures/reviewer-experiments.pdf"))
    return parser.parse_args()


def read_rows(path):
    with path.open(newline="", encoding="utf-8") as stream:
        return [{key: float(value) for key, value in row.items()}
                for row in csv.DictReader(stream)]


def draw_marker(pdf, x, y, shape, color):
    pdf.setFillColor(color)
    pdf.setStrokeColor(color)
    size = 2.4
    if shape == "circle":
        pdf.circle(x, y, size, stroke=1, fill=1)
    else:
        pdf.rect(x - size, y - size, 2 * size, 2 * size, stroke=1, fill=1)


def tick_text(value):
    if value >= 1_000_000:
        return f"{value / 1_000_000:.2f}M"
    if value >= 100000:
        return f"{value / 1000:.0f}k"
    if value >= 100:
        return f"{value:.0f}"
    if value >= 10:
        return f"{value:.1f}"
    return f"{value:.2f}".rstrip("0").rstrip(".")


def draw_panel(pdf, x0, labels, series, title, y_label):
    width = PAGE_WIDTH / 2
    left = x0 + 42
    right = x0 + width - 8
    bottom = 30
    top = PAGE_HEIGHT - 22
    plot_width = right - left
    plot_height = top - bottom
    maximum = max(max(values) for _, values in series)
    y_max = maximum * 1.12 if maximum else 1.0

    pdf.setFont("Times-Bold", 8.5)
    pdf.setFillColor(HexColor("#111111"))
    pdf.drawCentredString(x0 + width / 2, PAGE_HEIGHT - 10, title)
    for tick in range(5):
        value = y_max * tick / 4
        y = bottom + plot_height * tick / 4
        pdf.setStrokeColor(HexColor("#D9D9D9"))
        pdf.setLineWidth(0.45)
        pdf.line(left, y, right, y)
        pdf.setFont("Times-Roman", 6.8)
        pdf.setFillColor(HexColor("#333333"))
        pdf.drawRightString(left - 4, y - 2.2, tick_text(value))
    pdf.setStrokeColor(HexColor("#333333"))
    pdf.setLineWidth(0.7)
    pdf.line(left, bottom, left, top)
    pdf.line(left, bottom, right, bottom)

    xs = []
    for index, label in enumerate(labels):
        x = left + plot_width * index / max(1, len(labels) - 1)
        xs.append(x)
        pdf.setFont("Times-Roman", 6.7)
        pdf.setFillColor(HexColor("#333333"))
        pdf.drawCentredString(x, bottom - 10, label)

    pdf.saveState()
    pdf.translate(x0 + 9, PAGE_HEIGHT / 2)
    pdf.rotate(90)
    pdf.setFont("Times-Roman", 7.2)
    pdf.drawCentredString(0, 0, y_label)
    pdf.restoreState()

    for index, (name, values) in enumerate(series):
        color = HexColor(COLORS[index])
        points = [(xs[i], bottom + plot_height * value / y_max)
                  for i, value in enumerate(values)]
        pdf.setStrokeColor(color)
        pdf.setLineWidth(1.2)
        path = pdf.beginPath()
        path.moveTo(*points[0])
        for point in points[1:]:
            path.lineTo(*point)
        pdf.drawPath(path, stroke=1, fill=0)
        for point in points:
            draw_marker(pdf, *point, MARKERS[index], color)

    if len(series) > 1:
        legend_x = left + 6
        legend_y = top - 8
        for index, (name, _) in enumerate(series):
            color = HexColor(COLORS[index])
            y = legend_y - index * 10
            pdf.setStrokeColor(color)
            pdf.line(legend_x, y, legend_x + 12, y)
            draw_marker(pdf, legend_x + 6, y, MARKERS[index], color)
            pdf.setFillColor(HexColor("#222222"))
            pdf.setFont("Times-Roman", 6.8)
            pdf.drawString(legend_x + 17, y - 2.2, name)


def main():
    args = parse_args()
    payload = read_rows(args.payload_summary)
    registration = read_rows(args.registration_summary)
    values = sorted({int(row["value_bytes"]) for row in payload})
    labels = [f"{value} B" if value < 1024 else f"{value // 1024} KiB"
              for value in values]
    failure_series = []
    for sigma in [3.2, 6.4]:
        rows = {int(row["value_bytes"]): row for row in payload
                if row["error_sigma"] == sigma}
        failure_series.append((
            f"sigma={sigma}",
            [-rows[value]["log2_decryption_failure_bound"]
             for value in values],
        ))
    record_labels = [f"2^{int(round(math.log2(row['records'])))}"
                     for row in registration]
    state_mib = [row["client_state_bytes"] / (1024 * 1024)
                 for row in registration]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(args.output), pagesize=(PAGE_WIDTH, PAGE_HEIGHT))
    draw_panel(pdf, 0, labels, failure_series,
               "(a) Payload-length failure bound", "-log2 bound")
    draw_panel(pdf, PAGE_WIDTH / 2, record_labels,
               [("Client state", state_mib)],
               "(b) Registration state", "Size (MiB)")
    pdf.showPage()
    pdf.save()


if __name__ == "__main__":
    main()
