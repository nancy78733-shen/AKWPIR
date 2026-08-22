#!/usr/bin/env python3
"""Plot median native VPIR latency and communication with raw-run ranges."""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path
import statistics

import matplotlib.pyplot as plt


DISPLAY = {
    "AKWPIR": "AKWPIR*",
    "SimplePIR": "SimplePIR",
    "VeriSimplePIR": "VeriSimplePIR",
    "AuthenticatedPIR": "Authenticated PIR",
}

COLORS = {
    "AKWPIR": "#1769aa",
    "SimplePIR": "#6a994e",
    "VeriSimplePIR": "#bc6c25",
    "AuthenticatedPIR": "#9d4edd",
}

MARKERS = {
    "AKWPIR": "o",
    "SimplePIR": "s",
    "VeriSimplePIR": "^",
    "AuthenticatedPIR": "D",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_csv", type=Path)
    parser.add_argument("output", type=Path)
    return parser.parse_args()


def load(path: Path) -> dict[tuple[str, int], list[dict[str, str]]]:
    groups: dict[tuple[str, int], list[dict[str, str]]] = defaultdict(list)
    with path.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            groups[(row["scheme"], int(float(row["records"])))].append(row)
    return groups


def series(groups, scheme: str, metric: str):
    sizes = sorted(records for candidate, records in groups if candidate == scheme)
    medians, lower, upper = [], [], []
    for records in sizes:
        values = [float(row[metric]) for row in groups[(scheme, records)]]
        median = statistics.median(values)
        medians.append(median)
        lower.append(median - min(values))
        upper.append(max(values) - median)
    return sizes, medians, [lower, upper]


def main() -> None:
    args = parse_args()
    groups = load(args.raw_csv)
    schemes = ["AKWPIR", "SimplePIR", "VeriSimplePIR", "AuthenticatedPIR"]
    fig, axes = plt.subplots(1, 2, figsize=(9.2, 3.7))

    for scheme in schemes:
        sizes, values, errors = series(groups, scheme, "total_online_ms")
        axes[0].errorbar(
            sizes, values, yerr=errors, label=DISPLAY[scheme],
            color=COLORS[scheme], marker=MARKERS[scheme], linewidth=1.8,
            markersize=5, capsize=3,
        )

        communication = {}
        for records in sizes:
            rows = groups[(scheme, records)]
            communication[records] = [
                (float(row["query_bytes"]) + float(row["response_bytes"])) / 1024
                for row in rows
            ]
        medians = [statistics.median(communication[value]) for value in sizes]
        axes[1].plot(
            sizes, medians, label=DISPLAY[scheme], color=COLORS[scheme],
            marker=MARKERS[scheme], linewidth=1.8, markersize=5,
        )

    for axis in axes:
        axis.set_xscale("log", base=2)
        axis.set_yscale("log")
        axis.grid(True, which="both", linestyle=":", linewidth=0.6, alpha=0.7)
        axis.set_xlabel("Logical records (32 bytes each)")
        axis.set_xticks([1024, 4096, 16384], [r"$2^{10}$", r"$2^{12}$", r"$2^{14}$"])
    axes[0].set_ylabel("Online latency per record (ms)")
    axes[0].set_title("(a) Native online latency")
    axes[1].set_ylabel("Query + response (KiB)")
    axes[1].set_title("(b) Native communication")
    axes[0].legend(frameon=False, fontsize=8, loc="upper left")
    fig.text(
        0.5, -0.01,
        "* AKWPIR uses prototype n=256 timings; curves are not security-normalized.",
        ha="center", va="top", fontsize=8,
    )
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.output, dpi=300, bbox_inches="tight")
    if args.output.suffix.lower() != ".pdf":
        fig.savefig(args.output.with_suffix(".pdf"), bbox_inches="tight")


if __name__ == "__main__":
    main()
