import java.util.Arrays;
import java.util.Map;

public final class Benchmark {
    private Benchmark() {
    }

    public static void main(String[] args) {
        int recordCount = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
        int queryCount = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int lweDimension = args.length > 2 ? Integer.parseInt(args[2]) : 512;
        int valueBytes = args.length > 3 ? Integer.parseInt(args[3]) : 32;
        int warmupQueries = args.length > 4 ? Integer.parseInt(args[4]) : 5;
        Parameters parameters = new Parameters(lweDimension, 3.2, valueBytes);

        // Exclude one-time JVM, provider, and SecureRandom initialization from
        // the measured preprocessing interval.
        DataOwner.preprocess(
                DataOwner.deterministicDataset(16, parameters.valueBytes),
                parameters);

        Map<String, byte[]> dataset =
                DataOwner.deterministicDataset(recordCount, parameters.valueBytes);
        long preprocessStart = System.nanoTime();
        DataOwner.Setup setup = DataOwner.preprocess(dataset, parameters);
        long preprocessNanos = System.nanoTime() - preprocessStart;
        Client client = new Client(setup.clientState);

        for (int i = 0; i < warmupQueries; i++) {
            String keyword = String.format("keyword-%08d", i % recordCount);
            Client.Query query = client.query(keyword);
            Server.Response response = setup.server.answer(query);
            Client.Result result = client.reconstruct(query, response);
            if (result.status != Client.Status.FOUND
                    || !Arrays.equals(dataset.get(keyword), result.value)) {
                throw new IllegalStateException("warm-up query failed");
            }
        }

        long queryNanos = 0;
        long answerNanos = 0;
        long reconstructNanos = 0;
        int failures = 0;
        long queryBytes = 0;
        long responseBytes = 0;

        for (int i = 0; i < queryCount; i++) {
            String keyword = String.format("keyword-%08d", i % recordCount);
            long start = System.nanoTime();
            Client.Query query = client.query(keyword);
            queryNanos += System.nanoTime() - start;

            start = System.nanoTime();
            Server.Response response = setup.server.answer(query);
            answerNanos += System.nanoTime() - start;

            start = System.nanoTime();
            Client.Result result = client.reconstruct(query, response);
            reconstructNanos += System.nanoTime() - start;
            if (result.status != Client.Status.FOUND
                    || !Arrays.equals(dataset.get(keyword), result.value)) {
                failures++;
            }
            queryBytes = query.serializedSizeBytes();
            responseBytes = response.serializedSizeBytes();
        }

        int totalColumns = setup.clientState.dataColumns + setup.clientState.proofColumns;
        double exponent = -Math.pow(Parameters.DELTA, 2)
                / (8.0
                * Math.pow(parameters.errorSigma, 2)
                * Math.pow(255.0, 2)
                * setup.clientState.bucketCount);
        double log2FailureBound =
                Math.log(2.0 * totalColumns) / Math.log(2.0) + exponent / Math.log(2.0);
        double log2TagCollisionBound =
                Math.log(setup.clientState.bucketCapacity) / Math.log(2.0) - 256.0;

        System.out.println("metric,value");
        emit("records", recordCount);
        emit("queries", queryCount);
        emit("warmup_queries", warmupQueries);
        emit("lwe_dimension", lweDimension);
        emit("value_bytes", valueBytes);
        emit("bucket_count", setup.clientState.bucketCount);
        emit("bucket_capacity", setup.clientState.bucketCapacity);
        emit("data_columns", setup.clientState.dataColumns);
        emit("proof_columns", setup.clientState.proofColumns);
        emit("preprocess_ms", nanosToMillis(preprocessNanos));
        emit("query_avg_ms", nanosToMillis(queryNanos) / queryCount);
        emit("answer_avg_ms", nanosToMillis(answerNanos) / queryCount);
        emit("reconstruct_avg_ms", nanosToMillis(reconstructNanos) / queryCount);
        emit("query_bytes", queryBytes);
        emit("response_bytes", responseBytes);
        emit("server_state_bytes", setup.server.serializedStateBytes());
        emit("client_state_bytes", setup.clientState.serializedSizeBytes());
        emit("observed_failures", failures);
        emit("log2_decryption_failure_bound", log2FailureBound);
        emit("log2_tag_collision_bound", log2TagCollisionBound);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void emit(String metric, Object value) {
        System.out.println(metric + "," + value);
    }
}

