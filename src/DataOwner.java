import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline preprocessing performed by the data owner.
 *
 * The two server matrices share the same row count, so one LWE query selects
 * the same bucket from both. Their column counts are intentionally independent:
 * the proof matrix contains only a Merkle authentication path, not a padded
 * copy of a full data row.
 */
public final class DataOwner {
    public static final class ClientState {
        final Parameters parameters;
        final byte[] mappingKey;
        final int[][] publicMatrix;
        final int[][] dataHint;
        final int[][] proofHint;
        final byte[] merkleRoot;
        final int bucketCount;
        final int bucketCapacity;
        final int dataColumns;
        final int proofColumns;

        private ClientState(
                Parameters parameters,
                byte[] mappingKey,
                int[][] publicMatrix,
                int[][] dataHint,
                int[][] proofHint,
                byte[] merkleRoot,
                int bucketCount,
                int bucketCapacity,
                int dataColumns,
                int proofColumns) {
            this.parameters = parameters;
            this.mappingKey = Crypto.copy(mappingKey);
            this.publicMatrix = publicMatrix;
            this.dataHint = dataHint;
            this.proofHint = proofHint;
            this.merkleRoot = Crypto.copy(merkleRoot);
            this.bucketCount = bucketCount;
            this.bucketCapacity = bucketCapacity;
            this.dataColumns = dataColumns;
            this.proofColumns = proofColumns;
        }

        public long serializedSizeBytes() {
            long matrix = (long) publicMatrix.length * bucketCount * Integer.BYTES;
            long data = (long) dataHint.length * dataColumns * Integer.BYTES;
            long proof = (long) proofHint.length * proofColumns * Integer.BYTES;
            return matrix + data + proof + mappingKey.length + merkleRoot.length;
        }
    }

    public static final class Setup {
        public final ClientState clientState;
        public final Server server;

        private Setup(ClientState clientState, Server server) {
            this.clientState = clientState;
            this.server = server;
        }
    }

    private static final class MerkleMaterial {
        final byte[] root;
        final byte[][] paths;

        private MerkleMaterial(byte[] root, byte[][] paths) {
            this.root = root;
            this.paths = paths;
        }
    }

    private DataOwner() {
    }

    public static Setup preprocess(Map<String, byte[]> records, Parameters parameters) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("records must be non-empty");
        }
        for (Map.Entry<String, byte[]> entry : records.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("keywords and values must be non-null");
            }
            if (entry.getValue().length != parameters.valueBytes) {
                throw new IllegalArgumentException(
                        "value for " + entry.getKey() + " has length " + entry.getValue().length
                                + ", expected " + parameters.valueBytes);
            }
        }

        int bucketCount = Math.max(
                1,
                (int) Math.ceil(Math.sqrt((double) records.size() * parameters.recordBytes())));
        byte[] mappingKey = new byte[32];
        Crypto.rng().nextBytes(mappingKey);

        List<List<byte[]>> buckets = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            buckets.add(new ArrayList<>());
        }

        List<String> sortedKeywords = new ArrayList<>(records.keySet());
        Collections.sort(sortedKeywords);
        for (String keyword : sortedKeywords) {
            int bucket = Crypto.keyedBucket(mappingKey, keyword, bucketCount);
            buckets.get(bucket).add(serializeRealRecord(
                    keyword,
                    records.get(keyword),
                    parameters));
        }

        int capacity = 1;
        for (List<byte[]> bucket : buckets) {
            capacity = Math.max(capacity, bucket.size());
        }
        int dataColumns = capacity * parameters.recordBytes();
        byte[][] dataMatrix = new byte[bucketCount][dataColumns];
        for (int row = 0; row < bucketCount; row++) {
            List<byte[]> bucket = buckets.get(row);
            for (int slot = 0; slot < capacity; slot++) {
                byte[] serialized = slot < bucket.size()
                        ? bucket.get(slot)
                        : serializeDummyRecord(parameters);
                System.arraycopy(
                        serialized,
                        0,
                        dataMatrix[row],
                        slot * parameters.recordBytes(),
                        parameters.recordBytes());
            }
        }

        MerkleMaterial merkle = buildMerkleMaterial(dataMatrix);
        byte[][] proofMatrix = merkle.paths;

        int[][] publicMatrix = Crypto.randomMatrix(parameters.lweDimension, bucketCount);
        int[][] dataHint = multiply(publicMatrix, dataMatrix);
        int[][] proofHint = multiply(publicMatrix, proofMatrix);

        ClientState clientState = new ClientState(
                parameters,
                mappingKey,
                publicMatrix,
                dataHint,
                proofHint,
                merkle.root,
                bucketCount,
                capacity,
                dataColumns,
                proofMatrix[0].length);
        return new Setup(clientState, new Server(dataMatrix, proofMatrix));
    }

    public static Map<String, byte[]> deterministicDataset(int count, int valueBytes) {
        Map<String, byte[]> records = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            byte[] seed = ByteBuffer.allocate(Integer.BYTES).putInt(i).array();
            byte[] digest = Crypto.sha256(seed);
            byte[] value = new byte[valueBytes];
            for (int j = 0; j < valueBytes; j++) {
                value[j] = digest[j % digest.length];
            }
            records.put(String.format("keyword-%08d", i), value);
        }
        return records;
    }

    private static byte[] serializeRealRecord(
            String keyword,
            byte[] value,
            Parameters parameters) {
        byte[] record = new byte[parameters.recordBytes()];
        record[0] = 1;
        byte[] tag = Crypto.keywordTag(keyword);
        System.arraycopy(tag, 0, record, Parameters.FLAG_BYTES, Parameters.TAG_BYTES);
        System.arraycopy(
                value,
                0,
                record,
                Parameters.FLAG_BYTES + Parameters.TAG_BYTES,
                parameters.valueBytes);
        return record;
    }

    private static byte[] serializeDummyRecord(Parameters parameters) {
        byte[] record = new byte[parameters.recordBytes()];
        Crypto.rng().nextBytes(record);
        record[0] = 0;
        return record;
    }

    private static int[][] multiply(int[][] left, byte[][] right) {
        int rows = left.length;
        int shared = right.length;
        int columns = right[0].length;
        int[][] product = new int[rows][columns];
        for (int row = 0; row < rows; row++) {
            for (int k = 0; k < shared; k++) {
                int coefficient = left[row][k];
                byte[] rightRow = right[k];
                for (int column = 0; column < columns; column++) {
                    product[row][column] += coefficient * Byte.toUnsignedInt(rightRow[column]);
                }
            }
        }
        return product;
    }

    private static MerkleMaterial buildMerkleMaterial(byte[][] rows) {
        int leafCount = 1;
        while (leafCount < rows.length) {
            leafCount <<= 1;
        }
        int height = Integer.numberOfTrailingZeros(leafCount);
        byte[][][] levels = new byte[height + 1][][];
        levels[0] = new byte[leafCount][];
        for (int i = 0; i < leafCount; i++) {
            byte[] row = rows[Math.min(i, rows.length - 1)];
            levels[0][i] = Crypto.leafHash(row);
        }
        for (int level = 1; level <= height; level++) {
            int nodes = levels[level - 1].length / 2;
            levels[level] = new byte[nodes][];
            for (int i = 0; i < nodes; i++) {
                levels[level][i] = Crypto.nodeHash(
                        levels[level - 1][2 * i],
                        levels[level - 1][2 * i + 1]);
            }
        }

        byte[][] paths = new byte[rows.length][height * Parameters.TAG_BYTES];
        for (int row = 0; row < rows.length; row++) {
            int index = row;
            for (int level = 0; level < height; level++) {
                byte[] sibling = levels[level][index ^ 1];
                System.arraycopy(
                        sibling,
                        0,
                        paths[row],
                        level * Parameters.TAG_BYTES,
                        Parameters.TAG_BYTES);
                index >>>= 1;
            }
        }
        return new MerkleMaterial(Crypto.copy(levels[height][0]), paths);
    }
}
