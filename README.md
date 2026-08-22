# AKWPIR reference implementation

This directory contains a clean reference implementation of the protocol
specified in the revised manuscript. It replaces the earlier prototype, whose
constants, serialization, proof padding, and complexity accounting did not
faithfully implement the formal construction.

The implementation makes the following choices explicit:

- coefficients are bytes in Z_256;
- ciphertext arithmetic is modulo 2^32 through Java int overflow;
- Delta is 2^24;
- keyword tags are full SHA-256 digests;
- each record includes a real-or-dummy flag, a 32-byte tag, and a fixed-length
  value;
- the data and Merkle-proof matrices share a row count but have independent
  column counts;
- the client secret is uniform in Z_q and query errors are sampled from a
  rounded Gaussian;
- FOUND, NOT_FOUND, and REJECTED are distinct outcomes;
- online server work is reported as linear in the encoded database size.

This code is intended for reproducibility and protocol auditing. The default
production dimension of 2048 is a manuscript performance setting, not a
standalone claim of a specific security level. A concrete security claim
requires a pinned LWE-estimator version and a complete parameter report.

## Build and test

JDK 17 or later is recommended.

On PowerShell:

    New-Item -ItemType Directory -Force out
    javac -encoding UTF-8 -d out src\*.java
    java -cp out CorrectnessTest
    java -cp out Benchmark 1024 20 512 32 5 3.2

Benchmark arguments are record count, number of queries, LWE dimension, and
value length in bytes, followed by the number of unmeasured warm-up queries and
the error standard deviation.
Output is CSV-shaped metric,value data that includes
communication, state sizes, timing, observed failures, and conservative
analytical failure bounds.

For the repeated scaling experiment and publication figures, run:

    python scripts/run_scaling_benchmark.py --java-home <JDK directory>
    python scripts/plot_scaling_results.py results/benchmark-scaling-summary.csv

Reviewer-requested payload and registration experiments are reproduced with:

    python scripts/run_payload_benchmark.py --java-home <JDK directory> --dimension 256
    python scripts/run_registration_sizing.py --java-home <JDK directory>
    python scripts/plot_reviewer_experiments.py results/benchmark-payload-summary.csv results/registration-sizing-summary.csv

The scaling script performs three independent repetitions per database size
and reports medians while retaining every raw run. The payload experiment tests
32-byte through 4-KiB values at sigma values 3.2 and 6.4. Registration sizing
uses three deterministic mapping keys at database sizes from 2^8 through 2^20;
it measures realized bucket occupancy and computes the exact serialized state
size without allocating the dense hint matrices. Reported link times are
idealized wire-time projections and exclude protocol and congestion overhead.

## Scope

The reference implementation assumes a static database epoch and a trusted
offline delivery of the client state and Merkle root. Dynamic updates,
multi-client registration, and a malicious data owner are outside the current
protocol model. The server sees no client message after its response; therefore
the selective-failure discussion in the manuscript is limited to the one-shot
server transcript and does not cover application-level retries.
