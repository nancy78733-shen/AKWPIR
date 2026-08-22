/**
 * Online server for the dense reference implementation.
 *
 * The answer computation is linear in the encoded database size. The protocol
 * reduces communication and client online work; it does not claim sublinear
 * server computation.
 */
public final class Server {
    public static final class Response {
        public final int[] data;
        public final int[] proof;
        public final long epoch;

        public Response(int[] data, int[] proof) {
            this(data, proof, 0);
        }

        public Response(int[] data, int[] proof, long epoch) {
            this.data = data.clone();
            this.proof = proof.clone();
            this.epoch = epoch;
        }

        public long serializedSizeBytes() {
            return (long) (data.length + proof.length) * Integer.BYTES;
        }
    }

    private final byte[][] dataMatrix;
    private final byte[][] proofMatrix;
    private long epoch;

    Server(byte[][] dataMatrix, byte[][] proofMatrix) {
        this(dataMatrix, proofMatrix, 0);
    }

    Server(byte[][] dataMatrix, byte[][] proofMatrix, long epoch) {
        this.dataMatrix = cloneMatrix(dataMatrix);
        this.proofMatrix = cloneMatrix(proofMatrix);
        this.epoch = epoch;
    }

    public Response answer(Client.Query query) {
        if (query.coefficients.length != dataMatrix.length) {
            throw new IllegalArgumentException("query row dimension does not match server state");
        }
        if (query.epoch != epoch) {
            throw new IllegalStateException("query epoch does not match server epoch");
        }
        return new Response(
                multiply(query.coefficients, dataMatrix),
                multiply(query.coefficients, proofMatrix), epoch);
    }

    public void applyUpdate(DataOwner.ServerUpdate update) {
        if (update.fromEpoch != epoch || update.toEpoch != epoch + 1) {
            throw new IllegalStateException("server update is stale or out of order");
        }
        if (update.bucket < 0 || update.bucket >= dataMatrix.length
                || update.columnOffset < 0
                || update.columnOffset + update.newRecord.length
                > dataMatrix[update.bucket].length) {
            throw new IllegalArgumentException("server update dimension mismatch");
        }
        System.arraycopy(update.newRecord, 0, dataMatrix[update.bucket],
                update.columnOffset, update.newRecord.length);
        for (EpochUpdate.ProofRangePatch patch : update.proofPatches) {
            int offset = patch.level * Parameters.TAG_BYTES;
            for (int row = patch.startRow; row < patch.endRow; row++) {
                System.arraycopy(patch.newDigest, 0, proofMatrix[row],
                        offset, Parameters.TAG_BYTES);
            }
        }
        epoch = update.toEpoch;
    }

    public long epoch() {
        return epoch;
    }

    public long serializedStateBytes() {
        return (long) dataMatrix.length
                * (dataMatrix[0].length + proofMatrix[0].length);
    }

    private static int[] multiply(int[] vector, byte[][] matrix) {
        int[] product = new int[matrix[0].length];
        for (int row = 0; row < matrix.length; row++) {
            int coefficient = vector[row];
            for (int column = 0; column < product.length; column++) {
                product[column] += coefficient * Byte.toUnsignedInt(matrix[row][column]);
            }
        }
        return product;
    }

    private static byte[][] cloneMatrix(byte[][] matrix) {
        byte[][] copy = new byte[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }
}
