#!/usr/bin/env python3
"""Run repeated AKWPIR benchmarks and write raw and median CSV files."""

import argparse
import csv
import os
from pathlib import Path
import shutil
import statistics
import subprocess


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument(
        "--sizes",
        nargs="+",
        type=int,
        default=[128, 256, 512, 1024, 2048, 4096, 8192],
    )
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--queries", type=int, default=50)
    parser.add_argument("--warmup-queries", type=int, default=10)
    parser.add_argument("--dimension", type=int, default=256)
    parser.add_argument("--value-bytes", type=int, default=32)
    parser.add_argument("--output-dir", type=Path, default=Path("results"))
    return parser.parse_args()


def java_binary(java_home, name):
    suffix = ".exe" if os.name == "nt" else ""
    if java_home:
        candidate = Path(java_home) / "bin" / f"{name}{suffix}"
        if candidate.exists():
            return str(candidate)
    resolved = shutil.which(name)
    if resolved:
        return resolved
    raise FileNotFoundError(f"cannot find {name}; pass --java-home")


def parse_metric_output(text):
    rows = list(csv.reader(text.splitlines()))
    if not rows or rows[0] != ["metric", "value"]:
        raise ValueError("unexpected Benchmark output")
    return {metric: float(value) for metric, value in rows[1:]}


def write_csv(path, rows, fieldnames):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def main():
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    javac = java_binary(args.java_home, "javac")
    java = java_binary(args.java_home, "java")
    out_dir = root / "out"
    out_dir.mkdir(exist_ok=True)
    sources = sorted((root / "src").glob("*.java"))
    subprocess.run(
        [javac, "-encoding", "UTF-8", "-d", str(out_dir), *map(str, sources)],
        check=True,
    )

    raw_rows = []
    for records in args.sizes:
        for repetition in range(1, args.repetitions + 1):
            command = [
                java,
                "-cp",
                str(out_dir),
                "Benchmark",
                str(records),
                str(args.queries),
                str(args.dimension),
                str(args.value_bytes),
                str(args.warmup_queries),
            ]
            metrics = parse_metric_output(
                subprocess.run(
                    command,
                    check=True,
                    text=True,
                    stdout=subprocess.PIPE,
                ).stdout
            )
            metrics["repetition"] = repetition
            raw_rows.append(metrics)
            print(
                f"records={records} repetition={repetition} "
                f"preprocess_ms={metrics['preprocess_ms']:.3f}",
                flush=True,
            )

    metric_names = [
        key for key in raw_rows[0] if key not in {"records", "repetition"}
    ]
    raw_fields = ["records", "repetition", *metric_names]
    write_csv(args.output_dir / "benchmark-scaling-raw.csv", raw_rows, raw_fields)

    summary_rows = []
    for records in args.sizes:
        group = [row for row in raw_rows if int(row["records"]) == records]
        summary = {"records": records, "repetitions": len(group)}
        for metric in metric_names:
            summary[metric] = statistics.median(row[metric] for row in group)
        summary_rows.append(summary)
    summary_fields = ["records", "repetitions", *metric_names]
    write_csv(
        args.output_dir / "benchmark-scaling-summary.csv",
        summary_rows,
        summary_fields,
    )


if __name__ == "__main__":
    main()

