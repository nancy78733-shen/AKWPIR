#!/usr/bin/env python3
"""Measure bucket occupancy and exact registered-client state sizes."""

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
    parser.add_argument("--sizes", nargs="+", type=int,
                        default=[2**8, 2**10, 2**12, 2**14,
                                 2**16, 2**18, 2**20])
    parser.add_argument("--dimension", type=int, default=2048)
    parser.add_argument("--value-bytes", type=int, default=32)
    parser.add_argument("--seeds", nargs="+", type=int, default=[1, 2, 3])
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
        raise ValueError("unexpected RegistrationSizing output")
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
    for records in args.sizes:
        for seed in args.seeds:
            metrics = parse_metrics(subprocess.run([
                java, "-cp", str(out_dir), "RegistrationSizing",
                str(records), str(args.dimension), str(args.value_bytes),
                str(seed)], check=True, text=True,
                stdout=subprocess.PIPE).stdout)
            raw.append(metrics)
            print(f"records={records} seed={seed} "
                  f"capacity={int(metrics['bucket_capacity'])}", flush=True)

    fields = list(raw[0])
    write_csv(args.output_dir / "registration-sizing-raw.csv", raw, fields)
    metric_names = [key for key in fields if key not in {"records", "seed"}]
    summary = []
    for records in args.sizes:
        group = [row for row in raw if int(row["records"]) == records]
        row = {"records": records, "seeds": len(group)}
        for metric in metric_names:
            row[metric] = statistics.median(item[metric] for item in group)
        summary.append(row)
    write_csv(args.output_dir / "registration-sizing-summary.csv", summary,
              ["records", "seeds", *metric_names])


if __name__ == "__main__":
    main()
