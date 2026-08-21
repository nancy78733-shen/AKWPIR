#!/usr/bin/env python3
"""Create vector PDF figures from benchmark-scaling-summary.csv."""

import argparse
import csv
import math
from pathlib import Path

from reportlab.lib.colors import HexColor
from reportlab.pdfgen import canvas


COLORS = ["#0072B2", "#D55E00", "#009E73", "#CC79A7"]
MARKERS = ["circle", "square", "triangle", "diamond"]
PAGE_WIDTH = 7.05 * 72.0
PAGE_HEIGHT = 2.55 * 72.0


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("summary_csv", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("figures"))
    return parser.parse_args()


def read_rows(path):
    with path.open(newline="", encoding="utf-8") as stream:
        return [
            {key: float(value) for key, value in row.items()}
            for row in csv.DictReader(stream)
        ]


def format_tick(value):
    if value >= 100:
        return f"{value:.0f}"
    if value >= 10:
        return f"{value:.1f}"
    if value >= 1:
        return f"{value:.2f}".rstrip("0").rstrip(".")
    return f"{value:.2f}".rstrip("0").rstrip(".")


def draw_marker(pdf, x, y, shape, color):
    pdf.setFillColor(color)
    pdf.setStrokeColor(color)
    size = 2.4
    if shape == "circle":
        pdf.circle(x, y, size, stroke=1, fill=1)
    elif shape == "square":
        pdf.rect(x - size, y - size, 2 * size, 2 * size, stroke=1, fill=1)
    elif shape == "triangle":
        path = pdf.beginPath()
        path.moveTo(x, y + size + 0.7)
        path.lineTo(x - size - 0.5, y - size)
        path.lineTo(x + size + 0.5, y - size)
        path.close()
        pdf.drawPath(path, stroke=1, fill=1)
    else:
        path = pdf.beginPath()
        path.moveTo(x, y + size + 0.6)
        path.lineTo(x - size - 0.6, y)
        path.lineTo(x, y - size - 0.6)
        path.lineTo(x + size + 0.6, y)
        path.close()
        pdf.drawPath(path, stroke=1, fill=1)


def draw_panel(pdf, x0, y0, width, height, records, series, title, y_label):
    left = x0 + 38
    right = x0 + width - 8
    bottom = y0 + 30
    top = y0 + height - 22
    plot_width = right - left
    plot_height = top - bottom
    maximum = max(max(values) for _, values in series)
    y_max = maximum * 1.15 if maximum else 1.0

    pdf.setFont("Times-Bold", 8.5)
    pdf.setFillColor(HexColor("#111111"))
    pdf.drawCentredString(x0 + width / 2, y0 + height - 10, title)

    for tick in range(5):
        value = y_max * tick / 4
        y = bottom + plot_height * tick / 4
        pdf.setStrokeColor(HexColor("#D9D9D9"))
        pdf.setLineWidth(0.45)
        pdf.line(left, y, right, y)
        pdf.setFont("Times-Roman", 6.8)
        pdf.setFillColor(HexColor("#333333"))
        pdf.drawRightString(left - 4, y - 2.2, format_tick(value))

    pdf.setStrokeColor(HexColor("#333333"))
    pdf.setLineWidth(0.7)
    pdf.line(left, bottom, left, top)
    pdf.line(left, bottom, right, bottom)

    x_positions = []
    for index, record_count in enumerate(records):
        x = left + plot_width * index / (len(records) - 1)
        x_positions.append(x)
        pdf.setFont("Times-Roman", 6.8)
        pdf.setFillColor(HexColor("#333333"))
        pdf.drawCentredString(
            x,
            bottom - 10,
            f"2^{int(round(math.log2(record_count)))}",
        )

    pdf.setFont("Times-Roman", 7.2)
    pdf.drawCentredString(x0 + width / 2, y0 + 5, "Number of records")
    pdf.saveState()
    pdf.translate(x0 + 8, y0 + height / 2)
    pdf.rotate(90)
    pdf.drawCentredString(0, 0, y_label)
    pdf.restoreState()

    for series_index, (label, values) in enumerate(series):
        color = HexColor(COLORS[series_index])
        points = [
            (x_positions[index], bottom + plot_height * value / y_max)
            for index, value in enumerate(values)
        ]
        pdf.setStrokeColor(color)
        pdf.setLineWidth(1.2)
        path = pdf.beginPath()
        path.moveTo(*points[0])
        for point in points[1:]:
            path.lineTo(*point)
        pdf.drawPath(path, stroke=1, fill=0)
        for point in points:
            draw_marker(pdf, *point, MARKERS[series_index], color)

    if len(series) > 1:
        legend_x = left + 6
        legend_y = top - 8
        for series_index, (label, _) in enumerate(series):
            color = HexColor(COLORS[series_index])
            y = legend_y - series_index * 10
            pdf.setStrokeColor(color)
            pdf.setLineWidth(1.2)
            pdf.line(legend_x, y, legend_x + 12, y)
            draw_marker(
                pdf,
                legend_x + 6,
                y,
                MARKERS[series_index],
                color,
            )
            pdf.setFillColor(HexColor("#222222"))
            pdf.setFont("Times-Roman", 6.8)
            pdf.drawString(legend_x + 17, y - 2.2, label)


def draw_figure(output_path, records, panels):
    pdf = canvas.Canvas(str(output_path), pagesize=(PAGE_WIDTH, PAGE_HEIGHT))
    panel_width = PAGE_WIDTH / 2
    for index, panel in enumerate(panels):
        draw_panel(
            pdf,
            index * panel_width,
            0,
            panel_width,
            PAGE_HEIGHT,
            records,
            panel[0],
            panel[1],
            panel[2],
        )
    pdf.showPage()
    pdf.save()


def main():
    args = parse_args()
    rows = read_rows(args.summary_csv)
    records = [row["records"] for row in rows]
    args.output_dir.mkdir(parents=True, exist_ok=True)

    draw_figure(
        args.output_dir / "benchmark-timing.pdf",
        records,
        [
            (
                [("Preprocessing", [row["preprocess_ms"] / 1000 for row in rows])],
                "(a) Offline preprocessing",
                "Time (s)",
            ),
            (
                [
                    ("Client query", [row["query_avg_ms"] for row in rows]),
                    ("Server answer", [row["answer_avg_ms"] for row in rows]),
                    ("Reconstruction", [row["reconstruct_avg_ms"] for row in rows]),
                ],
                "(b) Online latency",
                "Average time (ms)",
            ),
        ],
    )

    draw_figure(
        args.output_dir / "benchmark-resources.pdf",
        records,
        [
            (
                [
                    ("Query", [row["query_bytes"] / 1024 for row in rows]),
                    ("Response", [row["response_bytes"] / 1024 for row in rows]),
                ],
                "(a) Online communication",
                "Size (KiB)",
            ),
            (
                [
                    (
                        "Server state",
                        [row["server_state_bytes"] / (1024 * 1024) for row in rows],
                    ),
                    (
                        "Client state",
                        [row["client_state_bytes"] / (1024 * 1024) for row in rows],
                    ),
                ],
                "(b) Persistent state",
                "Size (MiB)",
            ),
        ],
    )


if __name__ == "__main__":
    main()

