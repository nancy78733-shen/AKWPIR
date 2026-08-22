import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline preprocessing and fixed-shape epoch transitions under an
 * authenticated-channel assumption.
 */
public final class DataOwner {
    public static final class ClientState {
        final Parameters parameters;
        final byte[] mappingKey;
        final int[][] publicMatrix;
        final int[][] dataHint;
        final int[][] proofHint;
        byte[] merkleRoot;
        final int bucketCount;
        final int bucketCapacity;
        final int dataColumns;
        final int proofColumns;
        long epoch;

        private ClientState(Parameters parameters, byte[] mappingKey,
                int[][] publicMatrix, int[][] dataHint, int[][] proofHint,
                byte[] merkleRoot, int bucketCount, int bucketCapacity,
                int dataColumns, int proofColumns) {
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
            this.epoch = 0;
        }

        public long serializedSizeBytes() {
            long matrix = (long) publicMatrix.length * bucketCount * Integer.BYTES;
            long data = (long) dataHint.length * dataColumns * Integer.BYTES;
            long proof = (long) proofHint.length * proofColumns * Integer.BYTES;
            return matrix + data + proof + mappingKey.length + merkleRoot.length;
        }

        public long epoch() {
            return epoch;
        }
    }

    public static final class Setup {
        public final ClientState clientState;
        public final Server server;
        public final OwnerState ownerState;

        private Setup(ClientState clientState, Server server, OwnerState ownerState) {
            this.clientState = clientState;
            this.server = server;
            this.ownerState = ownerState;
        }
    }

    public static final class RebuildRequiredException extends RuntimeException {
        RebuildRequiredException(String message) {
            super(message);
        }
    }

    public static final class ServerUpdate {
        final long fromEpoch;
        final long toEpoch;
        final int bucket;
        final int columnOffset;
        final byte[] newRecord;
        final EpochUpdate.ProofRangePatch[] proofPatches;

        private ServerUpdate(long fromEpoch, long toEpoch, int bucket,
                int columnOffset, byte[] newRecord,
                EpochUpdate.ProofRangePatch[] proofPatches) {
            this.fromEpoch = fromEpoch;
            this.toEpoch = toEpoch;
            this.bucket = bucket;
            this.columnOffset = columnOffset;
            this.newRecord = newRecord.clone();
            this.proofPatches = proofPatches.clone();
        }

        public long serializedSizeBytes() {
            return 2L * Long.BYTES + 2L * Integer.BYTES + newRecord.length
                    + (long) proofPatches.length
                    * (3L * Integer.BYTES + Parameters.TAG_BYTES);
        }
    }

    public static final class ClientUpdate {
        final long fromEpoch;
        final long toEpoch;
        final int columnOffset;
        final int[][] dataHintDelta;
        final int[] proofLevels;
        final int[][] proofHintDelta;
        final byte[] newRoot;

        private ClientUpdate(long fromEpoch, long toEpoch, int columnOffset,
                int[][] dataHintDelta, int[] proofLevels,
                int[][] proofHintDelta, byte[] newRoot) {
            this.fromEpoch = fromEpoch;
            this.toEpoch = toEpoch;
            this.columnOffset = columnOffset;
            this.dataHintDelta = dataHintDelta;
            this.proofLevels = proofLevels;
            this.proofHintDelta = proofHintDelta;
            this.newRoot = newRoot.clone();
        }

        public long serializedSizeBytes() {
            long data = (long) dataHintDelta.length * dataHintDelta[0].length
                    * Integer.BYTES;
            long proof = (long) proofHintDelta.length * proofHintDelta[0].length
                    * Integer.BYTES;
            return 2L * Long.BYTES + Integer.BYTES + data + proof
                    + (long) proofLevels.length * Integer.BYTES + newRoot.length;
        }
    }

    public static final class UpdateBundle {
        public final ServerUpdate serverUpdate;
        public final ClientUpdate clientUpdate;

        private UpdateBundle(ServerUpdate serverUpdate, ClientUpdate clientUpdate) {
            this.serverUpdate = serverUpdate;
            this.clientUpdate = clientUpdate;
        }
    }

    public static final class OwnerState {
        private final Parameters parameters;
        private final byte[] mappingKey;
        private final int[][] publicMatrix;
        private final byte[][] dataMatrix;
        private final String[][] keywords;
        private final Map<String, Location> locations;
        private final MerkleTree merkleTree;
        private long epoch;

        private OwnerState(Parameters parameters, byte[] mappingKey,
                int[][] publicMatrix, byte[][] dataMatrix, String[][] keywords,
                Map<String, Location> locations, MerkleTree merkleTree) {
            this.parameters = parameters;
            this.mappingKey = mappingKey.clone();
            this.publicMatrix = publicMatrix;
            this.dataMatrix = dataMatrix;
            this.keywords = keywords;
            this.locations = locations;
            this.merkleTree = merkleTree;
            this.epoch = 0;
        }

        public long epoch() {
            return epoch;
        }

        public boolean hasCapacityFor(String keyword) {
            int bucket = Crypto.keyedBucket(mappingKey, keyword, dataMatrix.length);
            for (String current : keywords[bucket]) {
                if (current == null) {
                    return true;
                }
            }
            return false;
        }

        public UpdateBundle updateValue(String keyword, byte[] value) {
            Location location = locations.get(keyword);
            if (location == null) {
                throw new IllegalArgumentException("keyword does not exist: " + keyword);
            }
            validateValue(value);
            return transition(location, serializeRealRecord(keyword, value, parameters), keyword);
        }

        public UpdateBundle delete(String keyword) {
            Location location = locations.get(keyword);
            if (location == null) {
                throw new IllegalArgumentException("keyword does not exist: " + keyword);
            }
            return transition(location, serializeDummyRecord(parameters), null);
        }

        public UpdateBundle insert(String keyword, byte[] value) {
            if (locations.containsKey(keyword)) {
                throw new IllegalArgumentException("keyword already exists: " + keyword);
            }
            validateValue(value);
            int bucket = Crypto.keyedBucket(mappingKey, keyword, dataMatrix.length);
            int slot = -1;
            for (int i = 0; i < keywords[bucket].length; i++) {
                if (keywords[bucket][i] == null) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                throw new RebuildRequiredException(
                        "target bucket is full; fixed-shape update requires rebuilding");
            }
            return transition(new Location(bucket, slot),
                    serializeRealRecord(keyword, value, parameters), keyword);
        }

        private void validateValue(byte[] value) {
            if (value == null || value.length != parameters.valueBytes) {
                throw new IllegalArgumentException("value length must be " + parameters.valueBytes);
            }
        }

        private UpdateBundle transition(Location location, byte[] newRecord, String newKeyword) {
            int recordBytes = parameters.recordBytes();
            int columnOffset = location.slot * recordBytes;
            byte[] oldRecord = new byte[recordBytes];
            System.arraycopy(dataMatrix[location.bucket], columnOffset,
                    oldRecord, 0, recordBytes);
            byte[] oldRow = dataMatrix[location.bucket].clone();
            System.arraycopy(newRecord, 0, dataMatrix[location.bucket],
                    columnOffset, recordBytes);

            String oldKeyword = keywords[location.bucket][location.slot];
            if (oldKeyword != null) {
                locations.remove(oldKeyword);
            }
            keywords[location.bucket][location.slot] = newKeyword;
            if (newKeyword != null) {
                locations.put(newKeyword, location);
            }

            MerkleTransition treeUpdate = merkleTree.updateRow(
                    location.bucket, oldRow, dataMatrix[location.bucket]);
            EpochUpdate.ProofRangePatch[] patches = EpochUpdate.proofRangePatches(
                    location.bucket, dataMatrix.length,
                    treeUpdate.oldNodes, treeUpdate.newNodes);
            int[][] dataDelta = EpochUpdate.dataHintDelta(
                    publicMatrix, location.bucket, oldRecord, newRecord);
            int[][] proofDelta = EpochUpdate.proofHintDelta(publicMatrix, patches);
            int[] levels = new int[patches.length];
            for (int i = 0; i < patches.length; i++) {
                levels[i] = patches[i].level;
            }

            long fromEpoch = epoch;
            long toEpoch = ++epoch;
            ServerUpdate serverUpdate = new ServerUpdate(fromEpoch, toEpoch,
                    location.bucket, columnOffset, newRecord, patches);
            ClientUpdate clientUpdate = new ClientUpdate(fromEpoch, toEpoch,
                    columnOffset, dataDelta, levels, proofDelta, treeUpdate.newRoot);
            return new UpdateBundle(serverUpdate, clientUpdate);
        }
    }

    private static final class Location {
        final int bucket;
        final int slot;

        private Location(int bucket, int slot) {
            this.bucket = bucket;
            this.slot = slot;
        }
    }

    private static final class MerkleTransition {
        final byte[][] oldNodes;
        final byte[][] newNodes;
        final byte[] newRoot;

        private MerkleTransition(byte[][] oldNodes, byte[][] newNodes, byte[] newRoot) {
            this.oldNodes = oldNodes;
            this.newNodes = newNodes;
            this.newRoot = newRoot;
        }
    }

    private static final class MerkleTree {
        final int rowCount;
        final byte[][][] levels;

        private MerkleTree(byte[][] rows) {
            rowCount = rows.length;
            int leafCount = 1;
            while (leafCount < rows.length) {
                leafCount <<= 1;
            }
            int height = Integer.numberOfTrailingZeros(leafCount);
            levels = new byte[height + 1][][];
            levels[0] = new byte[leafCount][];
            for (int i = 0; i < leafCount; i++) {
                levels[0][i] = i < rows.length
                        ? Crypto.leafHash(rows[i])
                        : Crypto.paddingLeafHash(i);
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
        }

        byte[] root() {
            return levels[levels.length - 1][0].clone();
        }

        byte[][] paths() {
            int height = levels.length - 1;
            byte[][] paths = new byte[rowCount][height * Parameters.TAG_BYTES];
            for (int row = 0; row < rowCount; row++) {
                int index = row;
                for (int level = 0; level < height; level++) {
                    System.arraycopy(levels[level][index ^ 1], 0,
                            paths[row], level * Parameters.TAG_BYTES,
                            Parameters.TAG_BYTES);
                    index >>>= 1;
                }
            }
            return paths;
        }

        MerkleTransition updateRow(int row, byte[] expectedOldRow, byte[] newRow) {
            if (!Crypto.constantTimeEquals(levels[0][row], Crypto.leafHash(expectedOldRow))) {
                throw new IllegalStateException("owner Merkle state is inconsistent");
            }
            int height = levels.length - 1;
            byte[][] oldNodes = new byte[height][];
            byte[][] newNodes = new byte[height][];
            int index = row;
            byte[] current = Crypto.leafHash(newRow);
            for (int level = 0; level < height; level++) {
                oldNodes[level] = levels[level][index].clone();
                levels[level][index] = current;
                newNodes[level] = current.clone();
                int siblingIndex = index ^ 1;
                current = (index & 1) == 0
                        ? Crypto.nodeHash(current, levels[level][siblingIndex])
                        : Crypto.nodeHash(levels[level][siblingIndex], current);
                index >>>= 1;
            }
            levels[height][0] = current;
            return new MerkleTransition(oldNodes, newNodes, current.clone());
        }
    }

    private DataOwner() {
    }

    public static Setup preprocess(Map<String, byte[]> records, Parameters parameters) {
        return preprocess(records, parameters, 0);
    }

    public static Setup preprocess(Map<String, byte[]> records, Parameters parameters,
            int spareSlotsPerBucket) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("records must be non-empty");
        }
        if (spareSlotsPerBucket < 0) {
            throw new IllegalArgumentException("spareSlotsPerBucket must be non-negative");
        }
        for (Map.Entry<String, byte[]> entry : records.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("keywords and values must be non-null");
            }
            if (entry.getValue().length != parameters.valueBytes) {
                throw new IllegalArgumentException("value for " + entry.getKey()
                        + " has length " + entry.getValue().length
                        + ", expected " + parameters.valueBytes);
            }
        }

        int bucketCount = Math.max(1,
                (int) Math.ceil(Math.sqrt((double) records.size() * parameters.recordBytes())));
        byte[] mappingKey = new byte[32];
        Crypto.rng().nextBytes(mappingKey);
        List<List<byte[]>> buckets = new ArrayList<>(bucketCount);
        List<List<String>> bucketKeywords = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            buckets.add(new ArrayList<>());
            bucketKeywords.add(new ArrayList<>());
        }
        List<String> sortedKeywords = new ArrayList<>(records.keySet());
        Collections.sort(sortedKeywords);
        for (String keyword : sortedKeywords) {
            int bucket = Crypto.keyedBucket(mappingKey, keyword, bucketCount);
            buckets.get(bucket).add(serializeRealRecord(keyword, records.get(keyword), parameters));
            bucketKeywords.get(bucket).add(keyword);
        }

        int observedMaximum = 1;
        for (List<byte[]> bucket : buckets) {
            observedMaximum = Math.max(observedMaximum, bucket.size());
        }
        int capacity = observedMaximum + spareSlotsPerBucket;
        int dataColumns = capacity * parameters.recordBytes();
        byte[][] dataMatrix = new byte[bucketCount][dataColumns];
        String[][] keywords = new String[bucketCount][capacity];
        Map<String, Location> locations = new HashMap<>();
        for (int row = 0; row < bucketCount; row++) {
            List<byte[]> bucket = buckets.get(row);
            for (int slot = 0; slot < capacity; slot++) {
                byte[] serialized = slot < bucket.size()
                        ? bucket.get(slot) : serializeDummyRecord(parameters);
                System.arraycopy(serialized, 0, dataMatrix[row],
                        slot * parameters.recordBytes(), parameters.recordBytes());
                if (slot < bucket.size()) {
                    String keyword = bucketKeywords.get(row).get(slot);
                    keywords[row][slot] = keyword;
                    locations.put(keyword, new Location(row, slot));
                }
            }
        }

        MerkleTree merkleTree = new MerkleTree(dataMatrix);
        byte[][] proofMatrix = merkleTree.paths();
        int[][] publicMatrix = Crypto.randomMatrix(parameters.lweDimension, bucketCount);
        int[][] dataHint = multiply(publicMatrix, dataMatrix);
        int[][] proofHint = multiply(publicMatrix, proofMatrix);
        ClientState clientState = new ClientState(parameters, mappingKey,
                publicMatrix, dataHint, proofHint, merkleTree.root(), bucketCount,
                capacity, dataColumns, proofMatrix[0].length);
        OwnerState ownerState = new OwnerState(parameters, mappingKey,
                publicMatrix, dataMatrix, keywords, locations, merkleTree);
        return new Setup(clientState, new Server(dataMatrix, proofMatrix, 0), ownerState);
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

    private static byte[] serializeRealRecord(String keyword, byte[] value,
            Parameters parameters) {
        byte[] record = new byte[parameters.recordBytes()];
        record[0] = 1;
        byte[] tag = Crypto.keywordTag(keyword);
        System.arraycopy(tag, 0, record, Parameters.FLAG_BYTES, Parameters.TAG_BYTES);
        System.arraycopy(value, 0, record,
                Parameters.FLAG_BYTES + Parameters.TAG_BYTES, parameters.valueBytes);
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
                    product[row][column] += coefficient
                            * Byte.toUnsignedInt(rightRow[column]);
                }
            }
        }
        return product;
    }
}
