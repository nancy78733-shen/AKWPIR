import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {
    }

    static SecureRandom rng() {
        return RNG;
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static byte[] leafHash(byte[] serializedRecord) {
        byte[] domainSeparated = new byte[1 + serializedRecord.length];
        domainSeparated[0] = 0;
        System.arraycopy(serializedRecord, 0, domainSeparated, 1, serializedRecord.length);
        return sha256(domainSeparated);
    }

    static byte[] nodeHash(byte[] left, byte[] right) {
        byte[] input = new byte[1 + left.length + right.length];
        input[0] = 1;
        System.arraycopy(left, 0, input, 1, left.length);
        System.arraycopy(right, 0, input, 1 + left.length, right.length);
        return sha256(input);
    }

    static int keyedBucket(byte[] mappingKey, String keyword, int bucketCount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mappingKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(keyword.getBytes(StandardCharsets.UTF_8));
            long nonNegative = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
            return (int) (nonNegative % bucketCount);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static byte[] keywordTag(String keyword) {
        return sha256(keyword.getBytes(StandardCharsets.UTF_8));
    }

    static int sampleDiscreteGaussian(double sigma) {
        return (int) Math.rint(RNG.nextGaussian() * sigma);
    }

    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    static int[] randomVector(int length) {
        int[] vector = new int[length];
        for (int i = 0; i < length; i++) {
            vector[i] = RNG.nextInt();
        }
        return vector;
    }

    static int[][] randomMatrix(int rows, int columns) {
        int[][] matrix = new int[rows][columns];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                matrix[r][c] = RNG.nextInt();
            }
        }
        return matrix;
    }

    static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    static byte[] copy(byte[] value) {
        return Arrays.copyOf(value, value.length);
    }
}
