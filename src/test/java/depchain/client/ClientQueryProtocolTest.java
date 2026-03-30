package depchain.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientQueryProtocolTest {

    @Test
    void queryRequest_roundTrip_parseRequest() {
        long requestId = 42L;
        byte kind = ClientProtocol.QUERY_DEP_BALANCE;
        String payload = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

        byte[] wire = ClientProtocol.encodeQueryRequest(requestId, kind, payload);
        ClientProtocol.Request parsed = ClientProtocol.parseRequest(wire);
        assertNotNull(parsed);
        assertEquals(requestId, parsed.getRequestId());
        assertEquals(ClientProtocol.TYPE_QUERY, parsed.getProtocolKind());
        assertEquals(kind, parsed.getQueryKind());
        assertEquals(payload, parsed.getString());
        assertTrue(ClientProtocol.verifyRequest(parsed));
    }

    @Test
    void queryResponse_roundTrip_parseQueryResponse() {
        long requestId = 7L;
        boolean success = true;
        int headHeight = 12;
        String headHash = "abc123";
        byte[] returnData = new byte[] {1, 2, 3, 4};

        byte[] wire =
            ClientProtocol.encodeQueryResponse(requestId, success, headHeight, headHash, returnData);
        ClientProtocol.QueryResponse parsed = ClientProtocol.parseQueryResponse(wire);
        assertNotNull(parsed);
        assertEquals(requestId, parsed.getRequestId());
        assertEquals(success, parsed.isSuccess());
        assertEquals(headHeight, parsed.getHeadHeight());
        assertEquals(headHash, parsed.getHeadHash());
        assertArrayEquals(returnData, parsed.getReturnData());
    }
}

