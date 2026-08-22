# Representative VPIR comparison

This directory runs native code from three representative single-server
implementations on one machine:

* AKWPIR (this repository);
* SimplePIR and VeriSimplePIR from the pinned VeriSimplePIR repository; and
* the single-server LWE128 construction from the pinned Authenticated PIR
  artifact.

The comparison is a **PIR-core retrieval comparison**, not a claim that all
schemes implement identical functionality or security. AKWPIR accepts keyword
queries and supports authenticated epoch updates. The baselines are
index-addressed. The current AKWPIR Java prototype parameters are experimental
and are not asserted to match the concrete security of the baseline parameter
sets. Consequently, the output is suitable for implementation-level cost and
scaling comparisons, but not for a security-normalized speed ranking.

## Workload normalization

The primary workload stores `records` logical records of `value_bytes` bytes.
The pinned VeriSimplePIR artifact's tested path uses 8-bit entries, so its
record-level result is the **measured sum of `value_bytes` real byte-PIR
operations**. The official Authenticated PIR LWE128 implementation retrieves
one bit, so its record-level result is the **measured sum of
`8 * value_bytes` real bit-PIR operations**. The raw output records both
`native_queries_per_retrieval` and record-level latency/communication. No
multiplication-only extrapolation is reported as a measured result.

For VeriSimplePIR, the benchmark executes the artifact's full verified
preprocessing path and its real online verification equation. For SimplePIR,
the benchmark executes real database packing and hint generation. For the
Authenticated PIR LWE128 implementation, database creation includes digest
generation. AKWPIR preprocessing uses the repository's `DataOwner.preprocess`.

## Reproduction

Prerequisites are Ubuntu, Git, Python 3, OpenJDK 21, Clang 18, OpenSSL 3,
GNU Make, and Go 1.22. Exact upstream commits are in
`baselines.lock.json`.

From the repository root under Linux or WSL:

```sh
python3 benchmarks/vpir/run_comparison.py --bootstrap --smoke
python3 benchmarks/vpir/run_comparison.py \
  --sizes 1024 4096 16384 --repetitions 3 \
  --akwpir-queries 50 --cpp-queries 20 --apir-record-queries 1
```

`--bootstrap` clones and checks out the locked baselines below `.deps/` and
builds VeriSimplePIR. Generated dependencies, binaries, and results are ignored
by Git. The runner refuses to use a baseline whose HEAD differs from the lock.

The raw CSV is the source of truth. Summary CSV values are medians across
independent process repetitions. A deterministic environment manifest records
the operating system, CPU, memory, compilers, and locked commits.

## Interpretation rules

1. Compare online phases separately (`query`, `answer`, and
   `verify_recover`) before considering totals.
2. Keep one-time preprocessing separate and show amortized totals for an
   explicitly stated number of retrievals.
3. Do not treat AKWPIR's keyword mapping, dynamic epoch handling, or returned
   authentication path as free features of an index-PIR baseline.
4. Do not describe a native-parameter timing table as security-equivalent.
5. `tamper_rejected` is a functional mutation test, not a proof of security.
