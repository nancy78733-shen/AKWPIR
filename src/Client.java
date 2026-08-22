import java.util.Arrays;

public final class Client {
    public enum Status {
        FOUND,
        NOT_FOUND,
        REJECTED
    }

    public static final class Query {
        final int[] coefficients;
        final long epoch;
        private final QueryState state;

        private Query(int[] coefficients, long epoch, QueryState state) {
            this.coefficients = coefficients;
            this.epoch = epoch;
            this.state = state;
        }

        public long serializedSizeBytes() {
            return (long) coefficients.length * Integer.BYTES + Long.BYTES;
        }
    }

    public static final class Result {
        public final Status status;
        public final byte[] value;

        private Result(Status status, byte[] value) {
            this.status = status;
            this.value = value == null ? null : value.clone();
        }

        static Result found(byte[] value) {
            return new Result(Status.FOUND, value);
        }

        static Result notFound() {
            return new Result(Status.NOT_FOUND, null);
        }

        static Result rejected() {
            return new Result(Status.REJECTED, null);
        }
    }

    private static final class QueryState {
        final int[] secret;
        final int bucket;
        final byte[] expectedTag;

        private QueryState(int[] secret, int bucket, byte[] expectedTag) {
            this.secret = secret;
            this.bucket = bucket;
            this.expectedTag = expectedTag;
        }
    }

    private final DataOwner.ClientState state;

    public Client(DataOwner.ClientState state) {
        this.state = state;
    }

    public Query query(String keyword) {
        int bucket = Crypto.keyedBucket(state.mappingKey, keyword, state.bucketCount);
        int[] secret = Crypto.randomVector(state.parameters.lweDimension);
        int[] coefficients = new int[state.bucketCount];
        for (int column = 0; column < state.bucketCount; column++) {
            int accumulator = 0;
            for (int row = 0; row < state.parameters.lweDimension; row++) {
                accumulator += secret[row] * state.publicMatrix[row][column];
            }
            accumulator += Crypto.sampleDiscreteGaussian(state.parameters.errorSigma);
            if (column == bucket) {
                accumulator += Parameters.DELTA;
            }
            coefficients[column] = accumulator;
        }
        QueryState queryState = new QueryState(secret, bucket, Crypto.keywordTag(keyword));
        return new Query(coefficients, state.epoch, queryState);
    }

    public Result reconstruct(Query query, Server.Response response) {
        if (query.epoch != state.epoch || response.epoch != state.epoch
                || response.data.length != state.dataColumns
                || response.proof.length != state.proofColumns) {
            return Result.rejected();
        }
        byte[] dataRow = decode(response.data, state.dataHint, query.state.secret);
        byte[] proof = decode(response.proof, state.proofHint, query.state.secret);
        if (!verifyMerklePath(dataRow, proof, query.state.bucket, state.merkleRoot)) {
            return Result.rejected();
        }

        int recordBytes = state.parameters.recordBytes();
        int valueOffset = Parameters.FLAG_BYTES + Parameters.TAG_BYTES;
        for (int slot = 0; slot < state.bucketCapacity; slot++) {
            int offset = slot * recordBytes;
            if (dataRow[offset] != 1) {
                continue;
            }
            byte[] tag = Arrays.copyOfRange(
                    dataRow,
                    offset + Parameters.FLAG_BYTES,
                    offset + valueOffset);
            if (Crypto.constantTimeEquals(tag, query.state.expectedTag)) {
                byte[] value = Arrays.copyOfRange(
                        dataRow,
                        offset + valueOffset,
                        offset + valueOffset + state.parameters.valueBytes);
                return Result.found(value);
            }
        }
        return Result.notFound();
    }

    public void applyUpdate(DataOwner.ClientUpdate update) {
        if (update.fromEpoch != state.epoch || update.toEpoch != state.epoch + 1) {
            throw new IllegalStateException("client update is stale or out of order");
        }
        if (update.dataHintDelta.length != state.dataHint.length
                || update.proofHintDelta.length != state.proofHint.length) {
            throw new IllegalArgumentException("client update dimension mismatch");
        }
        for (int row = 0; row < state.dataHint.length; row++) {
            for (int column = 0; column < update.dataHintDelta[row].length; column++) {
                state.dataHint[row][update.columnOffset + column]
                        += update.dataHintDelta[row][column];
            }
            for (int patch = 0; patch < update.proofLevels.length; patch++) {
                int sourceOffset = patch * Parameters.TAG_BYTES;
                int destinationOffset = update.proofLevels[patch] * Parameters.TAG_BYTES;
                for (int offset = 0; offset < Parameters.TAG_BYTES; offset++) {
                    state.proofHint[row][destinationOffset + offset]
                            += update.proofHintDelta[row][sourceOffset + offset];
                }
            }
        }
        state.merkleRoot = update.newRoot.clone();
        state.epoch = update.toEpoch;
    }

    private static byte[] decode(int[] answer, int[][] hint, int[] secret) {
        byte[] plaintext = new byte[answer.length];
        long halfDelta = Parameters.DELTA / 2L;
        for (int column = 0; column < answer.length; column++) {
            int phase = answer[column];
            for (int row = 0; row < secret.length; row++) {
                phase -= secret[row] * hint[row][column];
            }
            long unsignedPhase = Integer.toUnsignedLong(phase);
            long rounded = (unsignedPhase + halfDelta) / Parameters.DELTA;
            plaintext[column] = (byte) (rounded & 0xffL);
        }
        return plaintext;
    }

    private static boolean verifyMerklePath(
            byte[] dataRow,
            byte[] proof,
            int bucket,
            byte[] expectedRoot) {
        if (proof.length % Parameters.TAG_BYTES != 0) {
            return false;
        }
        byte[] current = Crypto.leafHash(dataRow);
        int index = bucket;
        int height = proof.length / Parameters.TAG_BYTES;
        for (int level = 0; level < height; level++) {
            byte[] sibling = Arrays.copyOfRange(
                    proof,
                    level * Parameters.TAG_BYTES,
                    (level + 1) * Parameters.TAG_BYTES);
            current = (index & 1) == 0
                    ? Crypto.nodeHash(current, sibling)
                    : Crypto.nodeHash(sibling, current);
            index >>>= 1;
        }
        return Crypto.constantTimeEquals(current, expectedRoot);
    }
}
