import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

public final class CorrectnessTest {
    private CorrectnessTest() {
    }

    public static void main(String[] args) {
        Parameters parameters = Parameters.test(32);
        Map<String, byte[]> dataset = DataOwner.deterministicDataset(256, parameters.valueBytes);
        DataOwner.Setup setup = DataOwner.preprocess(dataset, parameters);
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

        System.out.println("CorrectnessTest: PASS");
        System.out.println("query_bytes=" + query.serializedSizeBytes());
        System.out.println("response_bytes=" + honestResponse.serializedSizeBytes());
        System.out.println("server_state_bytes=" + setup.server.serializedStateBytes());
        System.out.println("client_state_bytes=" + setup.clientState.serializedSizeBytes());
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
