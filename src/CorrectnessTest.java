import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

public final class CorrectnessTest {
    private CorrectnessTest() {
    }

    public static void main(String[] args) {
        Parameters parameters = Parameters.test(32);
        Map<String, byte[]> dataset = DataOwner.deterministicDataset(256, parameters.valueBytes);
        DataOwner.Setup setup = DataOwner.preprocess(dataset, parameters, 1);
        Client client = new Client(setup.clientState);

        String keyword = "keyword-00000127";
        Client.Query query = client.query(keyword);
        Server.Response honestResponse = setup.server.answer(query);
        Client.Result honest = client.reconstruct(query, honestResponse);
        require(honest.status == Client.Status.FOUND, "honest retrieval was not FOUND");
        require(Arrays.equals(dataset.get(keyword), honest.value), "honest value mismatch");

        Client.Query missingQuery = client.query("keyword-that-does-not-exist");
        Client.Result missing = client.reconstruct(
                missingQuery,
                setup.server.answer(missingQuery));
        require(missing.status == Client.Status.NOT_FOUND, "missing keyword was not NOT_FOUND");

        int[] tamperedData = honestResponse.data.clone();
        tamperedData[0] += Parameters.DELTA;
        Client.Result tamperedDataResult = client.reconstruct(
                query,
                new Server.Response(tamperedData, honestResponse.proof));
        require(
                tamperedDataResult.status == Client.Status.REJECTED,
                "tampered data was not rejected");

        int[] tamperedProof = honestResponse.proof.clone();
        tamperedProof[0] += Parameters.DELTA;
        Client.Result tamperedProofResult = client.reconstruct(
                query,
                new Server.Response(honestResponse.data, tamperedProof));
        require(
                tamperedProofResult.status == Client.Status.REJECTED,
                "tampered proof was not rejected");

        Client.Query staleQuery = client.query(keyword);
        Server.Response staleResponse = setup.server.answer(staleQuery);
        byte[] replacement = "updated-value-for-epoch-one-0000"
                .getBytes(StandardCharsets.UTF_8);
        require(replacement.length == parameters.valueBytes, "bad test update length");
        DataOwner.UpdateBundle modification = setup.ownerState.updateValue(keyword, replacement);
        setup.server.applyUpdate(modification.serverUpdate);
        client.applyUpdate(modification.clientUpdate);
        Client.Query updatedQuery = client.query(keyword);
        Client.Result updated = client.reconstruct(updatedQuery, setup.server.answer(updatedQuery));
        require(updated.status == Client.Status.FOUND, "updated record was not FOUND");
        require(Arrays.equals(replacement, updated.value), "updated value mismatch");
        require(client.reconstruct(staleQuery, staleResponse).status == Client.Status.REJECTED,
                "stale response was not rejected");

        String deletedKeyword = "keyword-00000042";
        DataOwner.UpdateBundle deletion = setup.ownerState.delete(deletedKeyword);
        setup.server.applyUpdate(deletion.serverUpdate);
        client.applyUpdate(deletion.clientUpdate);
        Client.Query deletedQuery = client.query(deletedKeyword);
        require(client.reconstruct(deletedQuery, setup.server.answer(deletedQuery)).status
                        == Client.Status.NOT_FOUND,
                "deleted keyword was not NOT_FOUND");

        String insertedKeyword = "inserted-keyword-0000";
        for (int suffix = 0; !setup.ownerState.hasCapacityFor(insertedKeyword); suffix++) {
            insertedKeyword = "inserted-keyword-" + suffix;
        }
        byte[] insertedValue = "inserted-value-for-epoch-three-0"
                .getBytes(StandardCharsets.UTF_8);
        require(insertedValue.length == parameters.valueBytes, "bad test insert length");
        DataOwner.UpdateBundle insertion = setup.ownerState.insert(insertedKeyword, insertedValue);
        setup.server.applyUpdate(insertion.serverUpdate);
        client.applyUpdate(insertion.clientUpdate);
        Client.Query insertedQuery = client.query(insertedKeyword);
        Client.Result inserted = client.reconstruct(insertedQuery, setup.server.answer(insertedQuery));
        require(inserted.status == Client.Status.FOUND, "inserted record was not FOUND");
        require(Arrays.equals(insertedValue, inserted.value), "inserted value mismatch");
        require(setup.server.epoch() == 3 && setup.clientState.epoch() == 3
                        && setup.ownerState.epoch() == 3,
                "epochs did not advance together");

        boolean duplicateRejected = false;
        try {
            setup.server.applyUpdate(insertion.serverUpdate);
        } catch (IllegalStateException expected) {
            duplicateRejected = true;
        }
        require(duplicateRejected, "duplicate update was not rejected");

        DataOwner.Setup noSpareSetup = DataOwner.preprocess(dataset, parameters);
        String overflowKeyword = "overflow-keyword-0";
        for (int suffix = 1; noSpareSetup.ownerState.hasCapacityFor(overflowKeyword); suffix++) {
            overflowKeyword = "overflow-keyword-" + suffix;
        }
        boolean rebuildRequired = false;
        try {
            noSpareSetup.ownerState.insert(overflowKeyword, insertedValue);
        } catch (DataOwner.RebuildRequiredException expected) {
            rebuildRequired = true;
        }
        require(rebuildRequired, "bucket overflow did not require rebuilding");

        System.out.println("CorrectnessTest: PASS");
        System.out.println("query_bytes=" + query.serializedSizeBytes());
        System.out.println("response_bytes=" + honestResponse.serializedSizeBytes());
        System.out.println("server_state_bytes=" + setup.server.serializedStateBytes());
        System.out.println("client_state_bytes=" + setup.clientState.serializedSizeBytes());
        System.out.println("final_epoch=" + setup.clientState.epoch());
        System.out.println("marker="
                + new String("formal-model-aligned".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
