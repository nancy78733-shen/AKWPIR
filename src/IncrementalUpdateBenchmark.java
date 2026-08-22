import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Measures one fixed-shape, full-record epoch transition. */
public final class IncrementalUpdateBenchmark {
    private IncrementalUpdateBenchmark() {
    }

    public static void main(String[] args) {
        int records = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
        int dimension = args.length > 1 ? Integer.parseInt(args[1]) : 2048;
        int valueBytes = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        int seed = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        int recordBytes = Parameters.FLAG_BYTES + Parameters.TAG_BYTES + valueBytes;
        int bucketCount = Math.max(1,
                (int) Math.ceil(Math.sqrt((double) records * recordBytes)));
        int capacity = maximumOccupancy(records, bucketCount, seed) + 1;
        int height = ceilLog2(bucketCount);
        int updatedRow = Math.floorMod(seed * 104729, bucketCount);

        int[][] publicMatrix = new int[dimension][bucketCount];
        Random random = new Random(seed);
        for (int row = 0; row < dimension; row++) {
            for (int column = 0; column < bucketCount; column++) {
                publicMatrix[row][column] = random.nextInt();
            }
        }
        byte[] oldRecord = new byte[recordBytes];
        byte[] newRecord = new byte[recordBytes];
        random.nextBytes(oldRecord);
        random.nextBytes(newRecord);
        oldRecord[0] = 1;
        newRecord[0] = 1;

        long ownerStart = System.nanoTime();
        byte[][] oldNodes = new byte[height][];
        byte[][] newNodes = new byte[height][];
        byte[] oldCurrent = Crypto.leafHash(oldRecord);
        byte[] newCurrent = Crypto.leafHash(newRecord);
        int index = updatedRow;
        for (int level = 0; level < height; level++) {
            oldNodes[level] = oldCurrent;
            newNodes[level] = newCurrent;
            byte[] sibling = Crypto.sha256(ByteBuffer.allocate(3 * Integer.BYTES)
                    .putInt(seed).putInt(level).putInt(index ^ 1).array());
            oldCurrent = (index & 1) == 0
                    ? Crypto.nodeHash(oldCurrent, sibling)
                    : Crypto.nodeHash(sibling, oldCurrent);
            newCurrent = (index & 1) == 0
                    ? Crypto.nodeHash(newCurrent, sibling)
                    : Crypto.nodeHash(sibling, newCurrent);
            index >>>= 1;
        }
        EpochUpdate.ProofRangePatch[] patches = EpochUpdate.proofRangePatches(
                updatedRow, bucketCount, oldNodes, newNodes);
        int[][] dataDelta = EpochUpdate.dataHintDelta(
                publicMatrix, updatedRow, oldRecord, newRecord);
        int[][] proofDelta = EpochUpdate.proofHintDelta(publicMatrix, patches);
        long ownerNanos = System.nanoTime() - ownerStart;

        byte[] serverRecord = oldRecord.clone();
        byte[][] proofMatrix = new byte[bucketCount][height * Parameters.TAG_BYTES];
        long serverStart = System.nanoTime();
        System.arraycopy(newRecord, 0, serverRecord, 0, recordBytes);
        for (EpochUpdate.ProofRangePatch patch : patches) {
            int offset = patch.level * Parameters.TAG_BYTES;
            for (int row = patch.startRow; row < patch.endRow; row++) {
                System.arraycopy(patch.newDigest, 0, proofMatrix[row], offset,
                        Parameters.TAG_BYTES);
            }
        }
        long serverNanos = System.nanoTime() - serverStart;

        int[][] clientDataColumns = new int[dimension][recordBytes];
        int[][] clientProofHint = new int[dimension][height * Parameters.TAG_BYTES];
        long clientStart = System.nanoTime();
        for (int row = 0; row < dimension; row++) {
            for (int column = 0; column < recordBytes; column++) {
                clientDataColumns[row][column] += dataDelta[row][column];
            }
            for (int patch = 0; patch < patches.length; patch++) {
                int source = patch * Parameters.TAG_BYTES;
                int destination = patches[patch].level * Parameters.TAG_BYTES;
                for (int offset = 0; offset < Parameters.TAG_BYTES; offset++) {
                    clientProofHint[row][destination + offset]
                            += proofDelta[row][source + offset];
                }
            }
        }
        long clientNanos = System.nanoTime() - clientStart;

        int dataColumns = capacity * recordBytes;
        int proofColumns = height * Parameters.TAG_BYTES;
        long fullClientState = (long) dimension * bucketCount * Integer.BYTES
                + (long) dimension * dataColumns * Integer.BYTES
                + (long) dimension * proofColumns * Integer.BYTES
                + 2L * Parameters.TAG_BYTES;
        long clientToken = 2L * Long.BYTES + Integer.BYTES
                + (long) dimension * recordBytes * Integer.BYTES
                + (long) dimension * patches.length * Parameters.TAG_BYTES * Integer.BYTES
                + (long) patches.length * Integer.BYTES + Parameters.TAG_BYTES;
        long serverToken = 2L * Long.BYTES + 2L * Integer.BYTES + recordBytes
                + (long) patches.length
                * (3L * Integer.BYTES + Parameters.TAG_BYTES);

        System.out.println("metric,value");
        emit("records", records);
        emit("seed", seed);
        emit("lwe_dimension", dimension);
        emit("value_bytes", valueBytes);
        emit("record_bytes", recordBytes);
        emit("bucket_count", bucketCount);
        emit("bucket_capacity_with_spare", capacity);
        emit("tree_height", height);
        emit("proof_patches", patches.length);
        emit("owner_update_ms", ownerNanos / 1_000_000.0);
        emit("server_apply_ms", serverNanos / 1_000_000.0);
        emit("client_apply_ms", clientNanos / 1_000_000.0);
        emit("client_update_bytes", clientToken);
        emit("server_update_bytes", serverToken);
        emit("full_client_state_bytes", fullClientState);
        emit("client_update_reduction", (double) fullClientState / clientToken);
    }

    private static int maximumOccupancy(int records, int bucketCount, int seed) {
        int[] occupancy = new int[bucketCount];
        byte[] key = Crypto.sha256(ByteBuffer.allocate(Integer.BYTES).putInt(seed).array());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (int i = 0; i < records; i++) {
                String keyword = String.format(Locale.ROOT, "keyword-%08d", i);
                byte[] digest = mac.doFinal(keyword.getBytes(StandardCharsets.UTF_8));
                long nonNegative = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong()
                        & Long.MAX_VALUE;
                occupancy[(int) (nonNegative % bucketCount)]++;
            }
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
        int maximum = 1;
        for (int count : occupancy) {
            maximum = Math.max(maximum, count);
        }
        return maximum;
    }

    private static int ceilLog2(int value) {
        return value <= 1 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }

    private static void emit(String metric, Object value) {
        System.out.println(metric + "," + value);
    }
}
