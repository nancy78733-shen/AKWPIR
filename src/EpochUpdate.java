import java.util.ArrayList;
import java.util.List;

/** Shared fixed-shape incremental-update arithmetic. */
final class EpochUpdate {
    static final class ProofRangePatch {
        final int level;
        final int startRow;
        final int endRow;
        final byte[] oldDigest;
        final byte[] newDigest;

        ProofRangePatch(
                int level,
                int startRow,
                int endRow,
                byte[] oldDigest,
                byte[] newDigest) {
            this.level = level;
            this.startRow = startRow;
            this.endRow = endRow;
            this.oldDigest = oldDigest.clone();
            this.newDigest = newDigest.clone();
        }
    }

    private EpochUpdate() {
    }

    static ProofRangePatch[] proofRangePatches(
            int updatedRow,
            int rowCount,
            byte[][] oldNodes,
            byte[][] newNodes) {
        List<ProofRangePatch> patches = new ArrayList<>();
        for (int level = 0; level < oldNodes.length; level++) {
            int subtreeSize = 1 << level;
            int nodeIndex = updatedRow >>> level;
            int sibling = nodeIndex ^ 1;
            int start = sibling * subtreeSize;
            int end = Math.min(rowCount, start + subtreeSize);
            if (start < end) {
                patches.add(new ProofRangePatch(
                        level,
                        start,
                        end,
                        oldNodes[level],
                        newNodes[level]));
            }
        }
        return patches.toArray(new ProofRangePatch[0]);
    }

    static int[][] dataHintDelta(
            int[][] publicMatrix,
            int updatedRow,
            byte[] oldRecord,
            byte[] newRecord) {
        if (oldRecord.length != newRecord.length) {
            throw new IllegalArgumentException("record lengths differ");
        }
        int[][] delta = new int[publicMatrix.length][oldRecord.length];
        for (int n = 0; n < publicMatrix.length; n++) {
            int coefficient = publicMatrix[n][updatedRow];
            for (int column = 0; column < oldRecord.length; column++) {
                int difference = Byte.toUnsignedInt(newRecord[column])
                        - Byte.toUnsignedInt(oldRecord[column]);
                delta[n][column] = coefficient * difference;
            }
        }
        return delta;
    }

    static int[][] proofHintDelta(
            int[][] publicMatrix,
            ProofRangePatch[] patches) {
        int[][] delta = new int[publicMatrix.length]
                [patches.length * Parameters.TAG_BYTES];
        for (int patchIndex = 0; patchIndex < patches.length; patchIndex++) {
            ProofRangePatch patch = patches[patchIndex];
            int destination = patchIndex * Parameters.TAG_BYTES;
            for (int n = 0; n < publicMatrix.length; n++) {
                int coefficientSum = 0;
                for (int row = patch.startRow; row < patch.endRow; row++) {
                    coefficientSum += publicMatrix[n][row];
                }
                for (int offset = 0; offset < Parameters.TAG_BYTES; offset++) {
                    int difference = Byte.toUnsignedInt(patch.newDigest[offset])
                            - Byte.toUnsignedInt(patch.oldDigest[offset]);
                    delta[n][destination + offset] = coefficientSum * difference;
                }
            }
        }
        return delta;
    }
}
