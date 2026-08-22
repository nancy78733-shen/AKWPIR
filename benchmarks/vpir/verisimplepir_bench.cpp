#include "pir/pir.h"
#include "pir/preproc_pir.h"

#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <tuple>

namespace {

using Clock = std::chrono::steady_clock;

double milliseconds(Clock::time_point start, Clock::time_point end) {
    return std::chrono::duration<double, std::milli>(end - start).count();
}

std::uint64_t matrix_bytes(const Matrix& matrix) {
    return matrix.rows * matrix.cols * sizeof(Elem);
}

struct Metrics {
    double preprocess_ms = 0.0;
    double query_ms = 0.0;
    double answer_ms = 0.0;
    double verify_recover_ms = 0.0;
    std::uint64_t query_bytes = 0;
    std::uint64_t response_bytes = 0;
    std::uint64_t client_state_bytes = 0;
    std::uint64_t server_state_bytes = 0;
    bool tamper_rejected = false;
    std::uint64_t observed_failures = 0;
};

Metrics run_simplepir(std::uint64_t records, std::uint64_t value_bytes,
                      std::uint64_t warmups, std::uint64_t queries) {
    Metrics metrics;
    const std::uint64_t entries = records * value_bytes;
    const auto setup_start = Clock::now();
    auto pir = std::make_unique<VLHEPIR>(
        entries, 8, true, false, true, true);
    Matrix database = pir->db.packDataInMatrix(pir->dbParams);
    PackedMatrix packed = packMatrixHardCoded(database, pir->dbParams.p);
    Matrix public_a = pir->Init();
    Matrix hint = pir->GenerateHint(public_a, database);
    metrics.preprocess_ms = milliseconds(setup_start, Clock::now());
    metrics.server_state_bytes = matrix_bytes(database);
    metrics.client_state_bytes = matrix_bytes(hint);

    auto retrieve = [&](std::uint64_t record, bool measured) {
        for (std::uint64_t byte = 0; byte < value_bytes; ++byte) {
            const std::uint64_t index = record * value_bytes + byte;
            auto start = Clock::now();
            auto ct_sk = pir->Query(public_a, index);
            auto after_query = Clock::now();
            Matrix ciphertext = std::get<0>(ct_sk);
            Matrix secret = std::get<1>(ct_sk);
            Matrix answer = pir->Answer(ciphertext, packed);
            auto after_answer = Clock::now();
            entry_t recovered = pir->Recover(hint, answer, secret, index);
            auto after_recover = Clock::now();
            if (recovered != pir->db.getDataAtIndex(index)) {
                metrics.observed_failures++;
            }
            if (measured) {
                metrics.query_ms += milliseconds(start, after_query);
                metrics.answer_ms += milliseconds(after_query, after_answer);
                metrics.verify_recover_ms += milliseconds(after_answer, after_recover);
                metrics.query_bytes += matrix_bytes(ciphertext);
                metrics.response_bytes += matrix_bytes(answer);
            }
        }
    };

    for (std::uint64_t i = 0; i < warmups; ++i) {
        retrieve(i % records, false);
    }
    for (std::uint64_t i = 0; i < queries; ++i) {
        retrieve((i * 7919 + 17) % records, true);
    }
    metrics.query_ms /= queries;
    metrics.answer_ms /= queries;
    metrics.verify_recover_ms /= queries;
    metrics.query_bytes /= queries;
    metrics.response_bytes /= queries;
    return metrics;
}

Metrics run_verisimplepir(std::uint64_t records, std::uint64_t value_bytes,
                          std::uint64_t warmups, std::uint64_t queries) {
    Metrics metrics;
    const std::uint64_t entries = records * value_bytes;
    const auto setup_start = Clock::now();
    auto pir = std::make_unique<VeriSimplePIR>(
        entries, 8, true, false, false, true, 1, true, false);
    Matrix database = pir->db.packDataInMatrix(pir->dbParams);
    Matrix database_transposed = transpose(database);
    PackedMatrix packed = packMatrixHardCoded(database, pir->dbParams.p);

    Matrix public_a = pir->Init();
    Matrix hint = pir->GenerateHint(public_a, database);
    Multi_Limb_Matrix preproc_a = pir->PreprocInit();
    Multi_Limb_Matrix preproc_hint =
        pir->PreprocGenerateHint(preproc_a, database_transposed);
    unsigned char preproc_hash[SHA256_DIGEST_LENGTH];
    pir->HashAandH(preproc_hash, preproc_a, preproc_hint);
    BinaryMatrix challenge = pir->PreprocSampleC();
    auto preproc_message = pir->PreprocClientMessage(preproc_a, challenge);
    auto preproc_ciphertexts = std::get<0>(preproc_message);
    auto preproc_secrets = std::get<1>(preproc_message);
    auto preproc_answers =
        pir->PreprocAnswer(preproc_ciphertexts, database_transposed);
    Matrix preproc_proof = pir->PreprocProve(
        preproc_hash, preproc_ciphertexts, preproc_answers, database_transposed);
    pir->PreprocVerify(preproc_a, preproc_hint, preproc_hash,
                       preproc_ciphertexts, preproc_answers, preproc_proof);
    Matrix verification_matrix =
        pir->PreprocRecoverZ(preproc_hint, preproc_secrets, preproc_answers);
    pir->VerifyPreprocZ(
        verification_matrix, public_a, challenge, hint);
    metrics.preprocess_ms = milliseconds(setup_start, Clock::now());
    metrics.server_state_bytes = matrix_bytes(database);
    metrics.client_state_bytes =
        matrix_bytes(hint)
        + challenge.rows * challenge.cols * sizeof(bool)
        + matrix_bytes(verification_matrix);

    auto retrieve = [&](std::uint64_t record, bool measured, bool test_tamper) {
        for (std::uint64_t byte = 0; byte < value_bytes; ++byte) {
            const std::uint64_t index = record * value_bytes + byte;
            auto start = Clock::now();
            auto ct_sk = pir->Query(public_a, index);
            auto after_query = Clock::now();
            Matrix ciphertext = std::get<0>(ct_sk);
            Matrix secret = std::get<1>(ct_sk);
            Matrix answer = pir->Answer(ciphertext, packed);
            auto after_answer = Clock::now();
            pir->PreVerify(ciphertext, answer, verification_matrix, challenge);
            entry_t recovered = pir->Recover(hint, answer, secret, index);
            auto after_recover = Clock::now();
            if (recovered != pir->db.getDataAtIndex(index)) {
                metrics.observed_failures++;
            }
            if (test_tamper && byte == 0) {
                Matrix changed(answer);
                changed.data[0] += 1;
                Matrix left = matMulVec(verification_matrix, ciphertext);
                Matrix right = matBinaryMulVec(challenge, changed);
                metrics.tamper_rejected = !eq(left, right);
            }
            if (measured) {
                metrics.query_ms += milliseconds(start, after_query);
                metrics.answer_ms += milliseconds(after_query, after_answer);
                metrics.verify_recover_ms += milliseconds(after_answer, after_recover);
                metrics.query_bytes += matrix_bytes(ciphertext);
                metrics.response_bytes += matrix_bytes(answer);
            }
        }
    };

    for (std::uint64_t i = 0; i < warmups; ++i) {
        retrieve(i % records, false, false);
    }
    for (std::uint64_t i = 0; i < queries; ++i) {
        retrieve((i * 7919 + 17) % records, true, i == 0);
    }
    metrics.query_ms /= queries;
    metrics.answer_ms /= queries;
    metrics.verify_recover_ms /= queries;
    metrics.query_bytes /= queries;
    metrics.response_bytes /= queries;
    return metrics;
}

void emit(const std::string& scheme, std::uint64_t records,
          std::uint64_t value_bytes, std::uint64_t warmups,
          std::uint64_t queries, const Metrics& metrics) {
    const double total =
        metrics.query_ms + metrics.answer_ms + metrics.verify_recover_ms;
    std::cout << std::setprecision(12);
    std::cout
        << "scheme,variant,records,value_bytes,logical_database_bytes,"
           "native_queries_per_retrieval,warmup_retrievals,measured_retrievals,"
           "preprocess_ms,query_ms,answer_ms,verify_recover_ms,total_online_ms,"
           "query_bytes,response_bytes,client_state_bytes,server_state_bytes,"
           "tamper_rejected,observed_failures\n";
    std::cout << scheme << ',' << scheme << ',' << records << ','
              << value_bytes << ',' << records * value_bytes << ','
              << value_bytes << ','
              << warmups << ',' << queries << ',' << metrics.preprocess_ms << ','
              << metrics.query_ms << ',' << metrics.answer_ms << ','
              << metrics.verify_recover_ms << ',' << total << ','
              << metrics.query_bytes << ',' << metrics.response_bytes << ','
              << metrics.client_state_bytes << ',' << metrics.server_state_bytes
              << ',' << (metrics.tamper_rejected ? 1 : 0) << ','
              << metrics.observed_failures << '\n';
}

std::uint64_t parse_positive(const char* text, const char* name) {
    const auto value = std::stoull(text);
    if (value == 0) {
        throw std::invalid_argument(std::string(name) + " must be positive");
    }
    return value;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        if (argc != 6) {
            std::cerr << "usage: verisimplepir_bench SCHEME RECORDS "
                         "VALUE_BYTES WARMUPS QUERIES\n";
            return 2;
        }
        const std::string scheme(argv[1]);
        const auto records = parse_positive(argv[2], "records");
        const auto value_bytes = parse_positive(argv[3], "value_bytes");
        const auto warmups = std::stoull(argv[4]);
        const auto queries = parse_positive(argv[5], "queries");
        Metrics metrics;
        if (scheme == "SimplePIR") {
            metrics = run_simplepir(records, value_bytes, warmups, queries);
        } else if (scheme == "VeriSimplePIR") {
            metrics = run_verisimplepir(records, value_bytes, warmups, queries);
        } else {
            throw std::invalid_argument("scheme must be SimplePIR or VeriSimplePIR");
        }
        emit(scheme, records, value_bytes, warmups, queries, metrics);
        return metrics.observed_failures == 0 ? 0 : 1;
    } catch (const std::exception& error) {
        std::cerr << "benchmark error: " << error.what() << '\n';
        return 2;
    }
}
