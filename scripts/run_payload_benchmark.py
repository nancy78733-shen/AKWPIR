#!/usr/bin/env python3
"""Benchmark payload-length scaling and decryption-failure bounds."""

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
    parser.add_argument("--value-bytes", nargs="+", type=int,
                        default=[32, 256, 1024, 4096])
    parser.add_argument("--sigmas", nargs="+", type=float, default=[3.2, 6.4])
    parser.add_argument("--records", type=int, default=16)
    parser.add_argument("--dimension", type=int, default=64)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--queries", type=int, default=100)
    parser.add_argument("--warmup-queries", type=int, default=10)
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


def parse_metrics(text):
    rows = list(csv.reader(text.splitlines()))
    if not rows or rows[0] != ["metric", "value"]:
        raise ValueError("unexpected Benchmark output")
    return {metric: float(value) for metric, value in rows[1:]}


def write_csv(path, rows, fields):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
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
    subprocess.run([javac, "-encoding", "UTF-8", "-d", str(out_dir),
                    *map(str, sources)], check=True)

    raw = []
    for value_bytes in args.value_bytes:
        for sigma in args.sigmas:
            for repetition in range(1, args.repetitions + 1):
                command = [
                    java, "-cp", str(out_dir), "Benchmark",
                    str(args.records), str(args.queries), str(args.dimension),
                    str(value_bytes), str(args.warmup_queries), str(sigma),
                ]
                metrics = parse_metrics(subprocess.run(
                    command, check=True, text=True,
                    stdout=subprocess.PIPE).stdout)
                metrics["repetition"] = repetition
                raw.append(metrics)
                print(f"value_bytes={value_bytes} sigma={sigma} "
                      f"repetition={repetition} failures="
                      f"{int(metrics['observed_failures'])}", flush=True)

    metric_names = [key for key in raw[0]
                    if key not in {"value_bytes", "error_sigma", "repetition"}]
    raw_fields = ["value_bytes", "error_sigma", "repetition", *metric_names]
    write_csv(args.output_dir / "benchmark-payload-raw.csv", raw, raw_fields)

    summary = []
    for value_bytes in args.value_bytes:
        for sigma in args.sigmas:
            group = [row for row in raw
                     if int(row["value_bytes"]) == value_bytes
                     and row["error_sigma"] == sigma]
            row = {"value_bytes": value_bytes, "error_sigma": sigma,
                   "repetitions": len(group)}
            for metric in metric_names:
                row[metric] = statistics.median(item[metric] for item in group)
            row["total_measured_queries"] = sum(item["queries"] for item in group)
            row["total_observed_failures"] = sum(
                item["observed_failures"] for item in group)
            summary.append(row)
    summary_fields = ["value_bytes", "error_sigma", "repetitions",
                      *metric_names, "total_measured_queries",
                      "total_observed_failures"]
    write_csv(args.output_dir / "benchmark-payload-summary.csv",
              summary, summary_fields)


if __name__ == "__main__":
    main()
