import java.util.Objects;

/**
 * Public parameters for the reference AKWPIR implementation.
 *
 * Arithmetic is performed modulo q = 2^32 by Java int overflow. Plaintext
 * coefficients are bytes in Z_256 and Delta = q / 256 = 2^24.
 */
public final class Parameters {
    public static final int TAG_BYTES = 32;
    public static final int FLAG_BYTES = 1;
    public static final int DELTA = 1 << 24;

    public final int lweDimension;
    public final double errorSigma;
    public final int valueBytes;

    public Parameters(int lweDimension, double errorSigma, int valueBytes) {
        if (lweDimension <= 0) {
            throw new IllegalArgumentException("lweDimension must be positive");
        }
        if (!(errorSigma > 0.0)) {
            throw new IllegalArgumentException("errorSigma must be positive");
        }
        if (valueBytes <= 0) {
            throw new IllegalArgumentException("valueBytes must be positive");
        }
        this.lweDimension = lweDimension;
        this.errorSigma = errorSigma;
        this.valueBytes = valueBytes;
    }

    public int recordBytes() {
        return FLAG_BYTES + TAG_BYTES + valueBytes;
    }

    public static Parameters production(int valueBytes) {
        return new Parameters(2048, 3.2, valueBytes);
    }

    public static Parameters test(int valueBytes) {
        return new Parameters(256, 3.2, valueBytes);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Parameters)) {
            return false;
        }
        Parameters that = (Parameters) other;
        return lweDimension == that.lweDimension
                && Double.compare(errorSigma, that.errorSigma) == 0
                && valueBytes == that.valueBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lweDimension, errorSigma, valueBytes);
    }
}
