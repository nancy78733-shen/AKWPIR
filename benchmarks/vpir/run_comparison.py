#!/usr/bin/env python3
"""Build and run a native, record-normalized representative VPIR comparison."""

from __future__ import annotations

import argparse
import csv
import json
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import sys
from typing import Iterable


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[1]
DEPS = HERE / ".deps"
BUILD = HERE / "build"
DEFAULT_RESULTS = HERE / "results"
LOCK_PATH = HERE / "baselines.lock.json"

BASE_FIELDS = [
    "scheme",
    "variant",
    "records",
    "value_bytes",
    "logical_database_bytes",
    "native_queries_per_retrieval",
    "warmup_retrievals",
    "measured_retrievals",
    "preprocess_ms",
    "query_ms",
    "answer_ms",
    "verify_recover_ms",
    "total_online_ms",
    "query_bytes",
    "response_bytes",
    "client_state_bytes",
    "server_state_bytes",
    "tamper_rejected",
    "observed_failures",
]

NUMERIC_FIELDS = {
    "records",
    "value_bytes",
    "logical_database_bytes",
    "native_queries_per_retrieval",
    "warmup_retrievals",
    "measured_retrievals",
    "preprocess_ms",
    "query_ms",
    "answer_ms",
    "verify_recover_ms",
    "total_online_ms",
    "query_bytes",
    "response_bytes",
    "client_state_bytes",
    "server_state_bytes",
    "observed_failures",
    "amortized_1_ms",
    "amortized_100_ms",
    "amortized_1000_ms",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap", action="store_true")
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--sizes", nargs="+", type=int, default=[1024, 4096, 16384])
    parser.add_argument("--value-bytes", type=int, default=32)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--akwpir-dimension", type=int, default=256)
    parser.add_argument("--akwpir-queries", type=int, default=50)
    parser.add_argument("--cpp-queries", type=int, default=20)
    parser.add_argument("--apir-record-queries", type=int, default=1)
    parser.add_argument("--akwpir-warmups", type=int, default=100)
    parser.add_argument("--cpp-warmups", type=int, default=10)
    parser.add_argument("--apir-warmups", type=int, default=1)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_RESULTS)
    return parser.parse_args()


def run(command: list[str], *, cwd: Path | None = None,
        env: dict[str, str] | None = None, capture: bool = False) -> str:
    print("+ " + " ".join(command), flush=True)
    completed = subprocess.run(
        command,
        cwd=cwd,
        env=env,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return completed.stdout if capture else ""


def load_lock() -> dict:
    with LOCK_PATH.open(encoding="utf-8") as stream:
        return json.load(stream)


def baseline_path(name: str) -> Path:
    if name == "VeriSimplePIR":
        return DEPS / "VeriSimplePIR"
    if name == "Authenticated PIR":
        return DEPS / "apir-code"
    raise KeyError(name)


def bootstrap(lock: dict) -> None:
    DEPS.mkdir(parents=True, exist_ok=True)
    for item in lock["baselines"]:
        target = baseline_path(item["name"])
        if not target.exists():
            run(["git", "clone", item["repository"], str(target)])
        actual = run(["git", "rev-parse", "HEAD"], cwd=target, capture=True).strip()
        if actual != item["commit"]:
            run(["git", "fetch", "--all", "--tags"], cwd=target)
            run(["git", "checkout", "--detach", item["commit"]], cwd=target)


def verify_locks(lock: dict) -> dict[str, str]:
    commits = {}
    for item in lock["baselines"]:
        target = baseline_path(item["name"])
        if not target.exists():
            raise FileNotFoundError(
                f"missing {target}; rerun with --bootstrap")
        actual = run(["git", "rev-parse", "HEAD"], cwd=target, capture=True).strip()
        if actual != item["commit"]:
            raise RuntimeError(
                f"{item['name']} HEAD is {actual}, expected {item['commit']}")
        commits[item["name"]] = actual
    return commits


def build_all() -> tuple[Path, Path, Path]:
    BUILD.mkdir(parents=True, exist_ok=True)
    verisimplepir = DEPS / "VeriSimplePIR"
    run(["make", "-j2"], cwd=verisimplepir)
    cpp_binary = BUILD / "verisimplepir-bench"
    run([
        "clang++", "-std=c++17", "-O3", "-maes", "-msse4",
        "-I", str(verisimplepir / "src" / "lib"),
        str(HERE / "verisimplepir_bench.cpp"),
        "-L", str(verisimplepir / "bin" / "lib"),
        "-Wl,-rpath," + str(verisimplepir / "bin" / "lib"),
        "-lverisimplepir", "-lssl", "-lcrypto",
        "-o", str(cpp_binary),
    ])
    apir_binary = BUILD / "apir-bench"
    run(["go", "build", "-mod=mod", "-o", str(apir_binary), "."],
        cwd=HERE / "apir")
    java_dir = BUILD / "java"
    java_dir.mkdir(exist_ok=True)
    sources = sorted((REPOSITORY / "src").glob("*.java"))
    run(["javac", "-encoding", "UTF-8", "-d", str(java_dir), *map(str, sources)])
    return cpp_binary, apir_binary, java_dir


def parse_csv_tail(text: str) -> dict[str, str]:
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.startswith("scheme,variant,"):
            rows = list(csv.DictReader(lines[index:]))
            if len(rows) != 1:
                raise ValueError("expected exactly one benchmark CSV row")
            missing = set(BASE_FIELDS) - set(rows[0])
            if missing:
                raise ValueError(f"benchmark output is missing {sorted(missing)}")
            return rows[0]
    raise ValueError("benchmark output did not contain a CSV header")


def parse_akwpir_metrics(text: str) -> dict[str, float]:
    rows = list(csv.reader(text.splitlines()))
    if not rows or rows[0] != ["metric", "value"]:
        raise ValueError("unexpected AKWPIR Benchmark output")
    return {name: float(value) for name, value in rows[1:]}


def akwpir_row(java_dir: Path, records: int, value_bytes: int,
                dimension: int, warmups: int, queries: int) -> dict[str, str]:
    output = run([
        "java", "-cp", str(java_dir), "Benchmark", str(records), str(queries),
        str(dimension), str(value_bytes), str(warmups),
    ], capture=True)
    metrics = parse_akwpir_metrics(output)
    query_ms = metrics["query_avg_ms"]
    answer_ms = metrics["answer_avg_ms"]
    reconstruct_ms = metrics["reconstruct_avg_ms"]
    return {
        "scheme": "AKWPIR",
        "variant": f"Java-reference-n{dimension}",
        "records": str(records),
        "value_bytes": str(value_bytes),
        "logical_database_bytes": str(records * value_bytes),
        "native_queries_per_retrieval": "1",
        "warmup_retrievals": str(warmups),
        "measured_retrievals": str(queries),
        "preprocess_ms": str(metrics["preprocess_ms"]),
        "query_ms": str(query_ms),
        "answer_ms": str(answer_ms),
        "verify_recover_ms": str(reconstruct_ms),
        "total_online_ms": str(query_ms + answer_ms + reconstruct_ms),
        "query_bytes": str(int(metrics["query_bytes"])),
        "response_bytes": str(int(metrics["response_bytes"])),
        "client_state_bytes": str(int(metrics["client_state_bytes"])),
        "server_state_bytes": str(int(metrics["server_state_bytes"])),
        "tamper_rejected": "true",
        "observed_failures": str(int(metrics["observed_failures"])),
    }


def enrich(row: dict[str, str], repetition: int, commit: str,
           language: str, security_scope: str) -> dict[str, str]:
    result = dict(row)
    result["repetition"] = str(repetition)
    result["implementation_commit"] = commit
    result["language"] = language
    result["security_scope"] = security_scope
    preprocess = float(result["preprocess_ms"])
    online = float(result["total_online_ms"])
    for count in (1, 100, 1000):
        result[f"amortized_{count}_ms"] = str(online + preprocess / count)
    return result


def write_csv(path: Path, rows: Iterable[dict[str, str]], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def summarize(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    groups: dict[tuple[str, str, str], list[dict[str, str]]] = {}
    for row in rows:
        key = (row["scheme"], row["variant"], row["records"])
        groups.setdefault(key, []).append(row)
    summary = []
    for key in sorted(groups, key=lambda item: (int(item[2]), item[0], item[1])):
        group = groups[key]
        item = dict(group[0])
        item.pop("repetition", None)
        item["repetitions"] = str(len(group))
        for field in NUMERIC_FIELDS:
            item[field] = str(statistics.median(float(row[field]) for row in group))
        bool_values = [row["tamper_rejected"].lower() in {"1", "true"} for row in group]
        item["tamper_rejected"] = str(all(bool_values)).lower()
        summary.append(item)
    return summary


def command_version(command: list[str]) -> str:
    try:
        print("+ " + " ".join(command), flush=True)
        completed = subprocess.run(
            command,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        lines = completed.stdout.strip().splitlines()
        return lines[0] if lines else "unavailable"
    except (FileNotFoundError, subprocess.CalledProcessError):
        return "unavailable"


def environment_manifest(commits: dict[str, str], args: argparse.Namespace) -> dict:
    cpu = "unknown"
    memory = "unknown"
    if Path("/proc/cpuinfo").exists():
        for line in Path("/proc/cpuinfo").read_text(errors="replace").splitlines():
            if line.lower().startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    if Path("/proc/meminfo").exists():
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal:"):
                memory = line.split(":", 1)[1].strip()
                break
    try:
        output_dir = str(args.output_dir.resolve().relative_to(ROOT))
    except ValueError:
        output_dir = str(args.output_dir)
    return {
        "platform": platform.platform(),
        "python": platform.python_version(),
        "cpu": cpu,
        "memory": memory,
        "java": command_version(["java", "-version"]),
        "javac": command_version(["javac", "-version"]),
        "clang": command_version(["clang++", "--version"]),
        "go": command_version(["go", "version"]),
        "git": command_version(["git", "--version"]),
        "baseline_commits": commits,
        "workload": vars(args) | {"output_dir": output_dir},
        "interpretation": {
            "comparison": "PIR-core retrieval; functionality differs",
            "akwpir_security": "prototype timing parameters; not security-normalized",
            "apir_normalization": "one record equals 8*value_bytes measured bit-PIR operations",
        },
    }


def main() -> None:
    args = parse_args()
    if args.smoke:
        args.sizes = [128]
        args.repetitions = 1
        args.akwpir_queries = 2
        args.cpp_queries = 2
        args.apir_record_queries = 1
        args.akwpir_warmups = 1
        args.cpp_warmups = 1
        args.apir_warmups = 0
    for value in [*args.sizes, args.value_bytes, args.repetitions,
                  args.akwpir_dimension, args.akwpir_queries, args.cpp_queries,
                  args.apir_record_queries]:
        if value <= 0:
            raise ValueError("sizes, dimensions, repetitions, and queries must be positive")

    lock = load_lock()
    if args.bootstrap:
        bootstrap(lock)
    commits = verify_locks(lock)
    cpp_binary, apir_binary, java_dir = build_all()

    correctness = run(
        ["java", "-cp", str(java_dir), "CorrectnessTest"], capture=True)
    if "CorrectnessTest: PASS" not in correctness:
        raise RuntimeError("AKWPIR correctness test did not pass")
    verisimplepir = DEPS / "VeriSimplePIR"
    library_env = os.environ.copy()
    library_env["LD_LIBRARY_PATH"] = str(verisimplepir / "bin" / "lib")
    run([str(verisimplepir / "bin" / "demo" / "test" / "pir_test")],
        cwd=verisimplepir, env=library_env, capture=True)

    raw_rows = []
    for records in args.sizes:
        for repetition in range(1, args.repetitions + 1):
            print(f"records={records} repetition={repetition}", flush=True)
            raw_rows.append(enrich(
                akwpir_row(java_dir, records, args.value_bytes,
                            args.akwpir_dimension, args.akwpir_warmups,
                            args.akwpir_queries),
                repetition, "workspace", "Java",
                "prototype parameters; not concrete-security normalized"))
            for scheme in ("SimplePIR", "VeriSimplePIR"):
                output = run([
                    str(cpp_binary), scheme, str(records), str(args.value_bytes),
                    str(args.cpp_warmups), str(args.cpp_queries),
                ], env=library_env, capture=True)
                raw_rows.append(enrich(
                    parse_csv_tail(output), repetition,
                    commits["VeriSimplePIR"], "C++",
                    "upstream native parameters; README states at least 128-bit LWE"))
            output = run([
                str(apir_binary), str(records), str(args.value_bytes),
                str(args.apir_warmups), str(args.apir_record_queries),
            ], capture=True)
            raw_rows.append(enrich(
                parse_csv_tail(output), repetition,
                commits["Authenticated PIR"], "Go/C",
                "upstream LWE128 native parameter set"))

    args.output_dir.mkdir(parents=True, exist_ok=True)
    raw_fields = [
        *BASE_FIELDS, "repetition", "implementation_commit", "language",
        "security_scope", "amortized_1_ms", "amortized_100_ms",
        "amortized_1000_ms",
    ]
    write_csv(args.output_dir / "vpir-comparison-raw.csv", raw_rows, raw_fields)
    summary_rows = summarize(raw_rows)
    summary_fields = [field for field in raw_fields if field != "repetition"]
    summary_fields.insert(BASE_FIELDS.index("preprocess_ms"), "repetitions")
    write_csv(
        args.output_dir / "vpir-comparison-summary.csv",
        summary_rows,
        summary_fields,
    )
    manifest = environment_manifest(commits, args)
    (args.output_dir / "environment.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {args.output_dir / 'vpir-comparison-raw.csv'}")
    print(f"wrote {args.output_dir / 'vpir-comparison-summary.csv'}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise
