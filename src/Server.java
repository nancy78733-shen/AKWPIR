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

        public Response(int[] data, int[] proof) {
            this.data = data.clone();
            this.proof = proof.clone();
        }

        public long serializedSizeBytes() {
            return (long) (data.length + proof.length) * Integer.BYTES;
        }
    }

    private final byte[][] dataMatrix;
    private final byte[][] proofMatrix;

    Server(byte[][] dataMatrix, byte[][] proofMatrix) {
        this.dataMatrix = cloneMatrix(dataMatrix);
        this.proofMatrix = cloneMatrix(proofMatrix);
    }

    public Response answer(Client.Query query) {
        if (query.coefficients.length != dataMatrix.length) {
            throw new IllegalArgumentException("query row dimension does not match server state");
        }
        return new Response(
                multiply(query.coefficients, dataMatrix),
                multiply(query.coefficients, proofMatrix));
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
