import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Measures realized bucket occupancy without allocating the dense hint
 * matrices. The resulting byte counts are exact for the documented serialized
 * representation; link-time columns are idealized wire-time projections and
 * exclude framing, authentication, and congestion overhead.
 */
public final class RegistrationSizing {
    private RegistrationSizing() {
    }

    public static void main(String[] args) {
        int recordCount = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
        int lweDimension = args.length > 1 ? Integer.parseInt(args[1]) : 2048;
        int valueBytes = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        int seed = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        int recordBytes = Parameters.FLAG_BYTES + Parameters.TAG_BYTES + valueBytes;
        int bucketCount = Math.max(
                1,
                (int) Math.ceil(Math.sqrt((double) recordCount * recordBytes)));
        int[] occupancy = new int[bucketCount];
        byte[] key = Crypto.sha256(ByteBuffer.allocate(Integer.BYTES).putInt(seed).array());

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (int i = 0; i < recordCount; i++) {
                String keyword = String.format(Locale.ROOT, "keyword-%08d", i);
                byte[] digest = mac.doFinal(keyword.getBytes(StandardCharsets.UTF_8));
                long nonNegative = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong()
                        & Long.MAX_VALUE;
                occupancy[(int) (nonNegative % bucketCount)]++;
            }
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }

        int bucketCapacity = 1;
        for (int count : occupancy) {
            bucketCapacity = Math.max(bucketCapacity, count);
        }
        int dataColumns = bucketCapacity * recordBytes;
        int proofColumns = Parameters.TAG_BYTES * ceilLog2(bucketCount);
        long publicMatrixBytes = (long) lweDimension * bucketCount * Integer.BYTES;
        long dataHintBytes = (long) lweDimension * dataColumns * Integer.BYTES;
        long proofHintBytes = (long) lweDimension * proofColumns * Integer.BYTES;
        long clientStateBytes = publicMatrixBytes + dataHintBytes + proofHintBytes
                + 2L * Parameters.TAG_BYTES;
        long serverStateBytes = (long) bucketCount * (dataColumns + proofColumns);

        System.out.println("metric,value");
        emit("records", recordCount);
        emit("seed", seed);
        emit("lwe_dimension", lweDimension);
        emit("value_bytes", valueBytes);
        emit("record_bytes", recordBytes);
        emit("bucket_count", bucketCount);
        emit("bucket_capacity", bucketCapacity);
        emit("data_columns", dataColumns);
        emit("proof_columns", proofColumns);
        emit("public_matrix_bytes", publicMatrixBytes);
        emit("data_hint_bytes", dataHintBytes);
        emit("proof_hint_bytes", proofHintBytes);
        emit("client_state_bytes", clientStateBytes);
        emit("server_state_bytes", serverStateBytes);
        emit("wire_seconds_100mbps", clientStateBytes * 8.0 / 100_000_000.0);
        emit("wire_seconds_1gbps", clientStateBytes * 8.0 / 1_000_000_000.0);
    }

    private static int ceilLog2(int value) {
        if (value <= 1) {
            return 0;
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }

    private static void emit(String metric, Object value) {
        System.out.println(metric + "," + value);
    }
}
