package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.Constant;

public class JsonRpcServletTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TestableServlet servlet;
  private JsonRpcServer mockRpcServer;
  private int savedMaxBatchSize;
  private int savedMaxResponseSize;

  @Before
  public void setUp() throws Exception {
    servlet = new TestableServlet();
    mockRpcServer = mock(JsonRpcServer.class);
    servlet.setRpcServer(mockRpcServer);
    savedMaxBatchSize = CommonParameter.getInstance().jsonRpcMaxBatchSize;
    savedMaxResponseSize = CommonParameter.getInstance().jsonRpcMaxResponseSize;
  }

  @After
  public void tearDown() {
    CommonParameter.getInstance().jsonRpcMaxBatchSize = savedMaxBatchSize;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = savedMaxResponseSize;
  }

  // --- parse error paths ---

  @Test
  public void invalidJson_returnsParseError() throws Exception {
    MockHttpServletResponse resp = doPost("not {{ valid json");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse(body.isArray());
    assertEquals(-32700, body.get("error").get("code").asInt());
    // A non-constraint JsonProcessingException keeps the generic message (else branch).
    assertEquals("JSON parse error", body.get("error").get("message").asText());
    assertEquals("2.0", body.get("jsonrpc").asText());
    assertTrue(body.get("id").isNull());
  }

  @Test
  public void emptyBody_returnsParseError() throws Exception {
    MockHttpServletResponse resp = doPost("");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertEquals(-32700, body.get("error").get("code").asInt());
  }

  // --- batch size limit ---

  @Test
  public void batchExceedsLimit_returnsExceedLimitAsArray() throws Exception {
    CommonParameter.getInstance().jsonRpcMaxBatchSize = 2;
    MockHttpServletResponse resp = doPost("[{\"id\":1},{\"id\":2},{\"id\":3}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("batch error response must be a JSON array", body.isArray());
    assertEquals(1, body.size());
    assertEquals(-32005, body.get(0).get("error").get("code").asInt());
  }

  @Test
  public void batchWithinLimit_proceedsToRpcServer() throws Exception {
    CommonParameter.getInstance().jsonRpcMaxBatchSize = 5;
    byte[] singleResp = "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":1}"
        .getBytes(StandardCharsets.UTF_8);
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(singleResp);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1},{\"id\":2}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertTrue("batch response must be a JSON array", body.isArray());
    assertEquals("each sub-request must produce a response", 2, body.size());
    assertEquals("ok", body.get(0).get("result").asText());
  }

  @Test
  public void emptyBatch_returnsInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("[]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse("empty-batch error response must be a single object, not an array", body.isArray());
    assertEquals(-32600, body.get("error").get("code").asInt());
    assertEquals("2.0", body.get("jsonrpc").asText());
    assertTrue(body.get("id").isNull());
  }

  @Test
  public void invalidRequestIdTypes_returnInvalidRequestWithoutDispatch() throws Exception {
    String[] invalidIds = {"true", "{}", "[]"};

    for (String id : invalidIds) {
      MockHttpServletResponse resp = doPost(
          "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\","
              + "\"params\":[],\"id\":" + id + "}");
      assertEquals(200, resp.getStatus());
      assertEquals("application/json-rpc", resp.getContentType());
      assertInvalidRequestWithNullId(MAPPER.readTree(resp.getContentAsByteArray()));
    }

    verifyNoInteractions(mockRpcServer);
  }

  @Test
  public void nullRequestId_isNotRejectedByServletValidation() throws Exception {
    int[] callCount = {0};
    doAnswer(inv -> {
      callCount[0]++;
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    doPost("{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\","
        + "\"params\":[],\"id\":null}");

    assertEquals("a null id is valid JSON-RPC input and must reach dispatch", 1, callCount[0]);
  }

  @Test
  public void structuredAndAbsentParams_reachRpcServer() throws Exception {
    List<JsonNode> dispatchedRequests = new ArrayList<>();
    doAnswer(inv -> {
      dispatchedRequests.add(MAPPER.readTree((InputStream) inv.getArgument(0)));
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    String[] requests = {
        "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\",\"id\":1}",
        "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\","
            + "\"params\":null,\"id\":2}",
        "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\","
            + "\"params\":[],\"id\":3}",
        "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\","
            + "\"params\":{},\"id\":4}"
    };
    for (String request : requests) {
      doPost(request);
    }

    assertEquals("all supported params shapes must reach jsonrpc4j",
        requests.length, dispatchedRequests.size());
    for (int i = 0; i < requests.length; i++) {
      assertEquals("forwarded request must be unchanged at index " + i,
          MAPPER.readTree(requests[i]), dispatchedRequests.get(i));
    }
  }

  @Test
  public void singleScalarParams_isRejectedBeforeDispatch() throws Exception {
    MockHttpServletResponse resp = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\","
            + "\"params\":5,\"id\":42}");

    assertEquals(200, resp.getStatus());
    assertEquals("application/json-rpc", resp.getContentType());
    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertEquals(MAPPER.getNodeFactory().textNode("2.0"), body.get("jsonrpc"));
    assertEquals(MAPPER.getNodeFactory().numberNode(-32600),
        body.get("error").get("code"));
    assertEquals(MAPPER.getNodeFactory().textNode("Invalid Request"),
        body.get("error").get("message"));
    assertFalse(body.get("error").has("data"));
    assertEquals(MAPPER.getNodeFactory().numberNode(42), body.get("id"));
    verifyNoInteractions(mockRpcServer);
  }

  @Test
  public void batchInvalidRequestId_isIsolatedFromValidSiblings() throws Exception {
    int[] callCount = {0};
    doAnswer(inv -> {
      JsonNode request = MAPPER.readTree((InputStream) inv.getArgument(0));
      OutputStream out = inv.getArgument(1);
      out.write(("{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":"
          + request.get("id") + "}").getBytes(StandardCharsets.UTF_8));
      callCount[0]++;
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("["
        + "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\",\"params\":[],\"id\":1},"
        + "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\",\"params\":[],\"id\":true},"
        + "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\",\"params\":[],\"id\":\"two\"}"
        + "]");

    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertTrue(body.isArray());
    assertEquals(3, body.size());
    assertEquals("ok", body.get(0).get("result").asText());
    assertEquals(1, body.get(0).get("id").asInt());
    assertInvalidRequestWithNullId(body.get(1));
    assertEquals("ok", body.get(2).get("result").asText());
    assertEquals("two", body.get(2).get("id").asText());
    assertEquals("only valid requests should reach jsonrpc4j", 2, callCount[0]);
  }

  @Test
  public void batchScalarParams_isIsolatedFromValidSibling() throws Exception {
    int[] callCount = {0};
    JsonNode[] dispatchedRequest = {null};
    doAnswer(inv -> {
      dispatchedRequest[0] = MAPPER.readTree((InputStream) inv.getArgument(0));
      OutputStream out = inv.getArgument(1);
      out.write(("{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":"
          + dispatchedRequest[0].get("id") + "}").getBytes(StandardCharsets.UTF_8));
      callCount[0]++;
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("["
        + "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\",\"params\":5,\"id\":1},"
        + "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\",\"params\":[],\"id\":2}"
        + "]");

    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertTrue(body.isArray());
    assertEquals(2, body.size());
    assertEquals(MAPPER.getNodeFactory().numberNode(-32600),
        body.get(0).get("error").get("code"));
    assertEquals(MAPPER.getNodeFactory().textNode("Invalid Request"),
        body.get(0).get("error").get("message"));
    assertFalse(body.get(0).get("error").has("data"));
    assertEquals(MAPPER.getNodeFactory().numberNode(1), body.get(0).get("id"));
    assertEquals(MAPPER.getNodeFactory().textNode("ok"), body.get(1).get("result"));
    assertEquals(MAPPER.getNodeFactory().numberNode(2), body.get(1).get("id"));
    assertEquals("only the valid sibling should reach jsonrpc4j", 1, callCount[0]);
    assertEquals(MAPPER.getNodeFactory().numberNode(2), dispatchedRequest[0].get("id"));
    assertTrue(dispatchedRequest[0].get("params").isArray());
  }

  @Test
  public void batchScalarParamsWithoutId_returnsErrorAndDispatchesValidNotification()
      throws Exception {
    List<JsonNode> dispatchedRequests = new ArrayList<>();
    doAnswer(inv -> {
      dispatchedRequests.add(MAPPER.readTree((InputStream) inv.getArgument(0)));
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("["
        + "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\",\"params\":5},"
        + "{\"jsonrpc\":\"2.0\",\"method\":\"web3_clientVersion\",\"params\":[]}"
        + "]");

    assertEquals(200, resp.getStatus());
    assertEquals("application/json-rpc", resp.getContentType());
    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertTrue(body.isArray());
    assertEquals(1, body.size());
    assertInvalidRequestWithNullId(body.get(0));
    assertEquals("only the valid notification should reach jsonrpc4j",
        1, dispatchedRequests.size());
    assertFalse(dispatchedRequests.get(0).has("id"));
    assertTrue(dispatchedRequests.get(0).get("params").isArray());
  }

  @Test
  public void batchLimitDisabled_largeBatchAllowed() throws Exception {
    CommonParameter.getInstance().jsonRpcMaxBatchSize = 0;
    // write nothing — simulates notifications (no response expected)
    doAnswer(inv -> 0).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 500; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{}");
    }
    sb.append("]");
    MockHttpServletResponse resp = doPost(sb.toString());
    assertEquals(200, resp.getStatus());
    assertEquals("all-notification batch must return empty body per JSON-RPC 2.0 §6",
        0, resp.getContentLength());
    assertEquals("", resp.getContentAsString());
  }

  // --- rpcServer.handleRequest exceptions ---

  @Test
  public void rpcServerThrowsRuntimeException_returnsInternalError() throws Exception {
    doThrow(new RuntimeException("server exploded")).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));
    MockHttpServletResponse resp = doPost("{\"method\":\"eth_blockNumber\",\"id\":42}");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse(body.isArray());
    assertEquals(-32603, body.get("error").get("code").asInt());
    assertEquals("Internal error", body.get("error").get("message").asText());
    assertEquals(42, body.get("id").asInt());
  }

  @Test
  public void rpcServerThrowsIOException_returnsInternalError() throws Exception {
    doThrow(new IOException("server exploded")).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("{\"method\":\"eth_blockNumber\",\"id\":42}");

    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertEquals(-32603, body.get("error").get("code").asInt());
    assertEquals("Internal error", body.get("error").get("message").asText());
    assertEquals(42, body.get("id").asInt());
  }

  @Test
  public void notificationIOException_returnsEmptyResponse() throws Exception {
    doThrow(new IOException("server exploded")).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("{\"method\":\"eth_blockNumber\"}");

    assertEquals(200, resp.getStatus());
    assertEquals("application/json-rpc", resp.getContentType());
    assertEquals(0, resp.getContentAsByteArray().length);
  }

  @Test
  public void batchRpcServerThrows_internalErrorIsArray() throws Exception {
    doThrow(new RuntimeException("boom")).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));
    MockHttpServletResponse resp = doPost(
        "[{\"method\":\"eth_blockNumber\",\"id\":\"request-1\"}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("batch internal error must be an array", body.isArray());
    assertEquals(-32603, body.get(0).get("error").get("code").asInt());
    assertEquals("Internal error", body.get(0).get("error").get("message").asText());
    assertEquals("request-1", body.get(0).get("id").asText());
  }

  @Test
  public void batchRpcServerThrowsIOException_internalErrorIsArray() throws Exception {
    doThrow(new IOException("boom")).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost(
        "[{\"method\":\"eth_blockNumber\",\"id\":\"request-1\"}]");

    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertTrue(body.isArray());
    assertEquals(-32603, body.get(0).get("error").get("code").asInt());
    assertEquals("Internal error", body.get(0).get("error").get("message").asText());
    assertEquals("request-1", body.get(0).get("id").asText());
  }

  @Test
  public void fatalError_commitsBare500AndRethrowsOriginal() throws Exception {
    StackOverflowError fatal = new StackOverflowError("fatal-marker");
    doThrow(fatal).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));
    MockHttpServletResponse response = new MockHttpServletResponse();

    StackOverflowError thrown = assertThrows(StackOverflowError.class,
        () -> doPost("{\"method\":\"eth_blockNumber\",\"id\":1}", response));

    assertSame(fatal, thrown);
    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
    assertEquals(0, response.getContentAsByteArray().length);
    assertTrue(response.isCommitted());
  }

  @Test
  public void cleanupIOException_doesNotReplaceOriginalFatalError() throws Exception {
    assertCleanupFailureDoesNotReplaceFatal(new IOExceptionOnFlushResponse());
  }

  @Test
  public void cleanupError_doesNotReplaceOriginalFatalError() throws Exception {
    assertCleanupFailureDoesNotReplaceFatal(new ErrorOnFlushResponse());
  }

  @Test
  public void batchMalformedRpcServerResponse_preservesRequestId() throws Exception {
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write("not-json".getBytes(StandardCharsets.UTF_8));
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost(
        "[{\"method\":\"eth_blockNumber\",\"id\":42}]");

    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertTrue(body.isArray());
    assertEquals(-32603, body.get(0).get("error").get("code").asInt());
    assertEquals("Internal error", body.get(0).get("error").get("message").asText());
    assertEquals(42, body.get(0).get("id").asInt());
  }

  // --- response size limit ---

  @Test
  public void responseTooLarge_returnsSingleErrorObject() throws Exception {
    int limit = 50;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(new byte[limit + 1]);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("{\"method\":\"eth_getLogs\",\"id\":1}");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse(body.isArray());
    assertEquals(-32003, body.get("error").get("code").asInt());
  }

  @Test
  public void batchResponseTooLarge_returnsErrorArray() throws Exception {
    int limit = 50;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(new byte[limit + 1]);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"method\":\"eth_getLogs\"}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("batch response-too-large must be an array", body.isArray());
    assertEquals(-32003, body.get(0).get("error").get("code").asInt());
  }

  @Test
  public void batchShortCircuitsOnOverflow() throws Exception {
    int limit = 50;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    int[] callCount = {0};
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      callCount[0]++;
      if (callCount[0] == 1) {
        out.write("{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
      } else {
        out.write(new byte[limit]); // triggers overflow when added to accumulated size
      }
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1},{\"id\":2},{\"id\":3}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("overflow response must be an array", body.isArray());
    // Geth-compatible: previous successes are preserved; overflow item and remaining
    // unexecuted items each get a -32003 error with their original id.
    assertEquals(3, body.size());
    assertEquals("ok", body.get(0).get("result").asText());
    assertEquals(-32003, body.get(1).get("error").get("code").asInt());
    assertEquals(2, body.get(1).get("id").asInt());
    assertEquals(-32003, body.get(2).get("error").get("code").asInt());
    assertEquals(3, body.get(2).get("id").asInt());
    assertEquals("third sub-request must not be executed after overflow", 2, callCount[0]);
  }

  @Test
  public void batchInvalidRequestId_afterOverflowStillReturnsInvalidRequest() throws Exception {
    int limit = 50;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    int[] callCount = {0};
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(new byte[limit + 1]);
      callCount[0]++;
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("["
        + "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getLogs\",\"id\":1},"
        + "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getLogs\",\"id\":{}}"
        + "]");

    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertTrue(body.isArray());
    assertEquals(2, body.size());
    assertEquals(-32003, body.get(0).get("error").get("code").asInt());
    assertEquals(1, body.get(0).get("id").asInt());
    assertInvalidRequestWithNullId(body.get(1));
    assertEquals("invalid requests must not be dispatched after overflow", 1, callCount[0]);
  }

  // --- normal path ---

  @Test
  public void normalRequest_commitsRpcServerResponse() throws Exception {
    byte[] rpcResp = "{\"result\":\"0x1\"}".getBytes(StandardCharsets.UTF_8);
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(rpcResp);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("{\"method\":\"eth_blockNumber\",\"id\":1}");
    assertEquals(200, resp.getStatus());
    assertArrayEquals(rpcResp, resp.getContentAsByteArray());
  }

  // --- Content-Type header: must be application/json-rpc (no charset suffix) ---

  @Test
  public void errorResponse_contentTypeIsApplicationJsonRpc() throws Exception {
    MockHttpServletResponse resp = doPost("not valid json");
    assertEquals("application/json-rpc", resp.getContentType());
  }

  @Test
  public void batchResponse_contentTypeIsApplicationJsonRpc() throws Exception {
    byte[] singleResp = "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":1}"
        .getBytes(StandardCharsets.UTF_8);
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(singleResp);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1}]");
    assertEquals("application/json-rpc", resp.getContentType());
  }

  @Test
  public void allNotificationBatch_contentTypeIsApplicationJsonRpc() throws Exception {
    // notification: rpcServer returns 0 bytes → empty batchResult → early return path
    doAnswer(inv -> 0).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"method\":\"eth_blockNumber\"}]");
    assertEquals(200, resp.getStatus());
    assertEquals(0, resp.getContentLength());
    assertEquals("application/json-rpc", resp.getContentType());
  }

  // --- Primitive root node → Invalid Request (-32600), id must be JSON null ---

  @Test
  public void primitiveRootNull_returnsInvalidRequestWithJsonNullId() throws Exception {
    MockHttpServletResponse resp = doPost("null");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse(body.isArray());
    assertEquals("2.0", body.get("jsonrpc").asText());
    assertEquals(-32600, body.get("error").get("code").asInt());
    assertTrue("id must be JSON null, not the string \"null\"", body.get("id").isNull());
    assertFalse("id must not be a string", body.get("id").isTextual());
  }

  @Test
  public void primitiveRootBoolean_returnsInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("true");
    assertEquals(200, resp.getStatus());
    assertEquals(-32600,
        MAPPER.readTree(resp.getContentAsString()).get("error").get("code").asInt());
  }

  @Test
  public void primitiveRootNumber_returnsInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("123");
    assertEquals(200, resp.getStatus());
    assertEquals(-32600,
        MAPPER.readTree(resp.getContentAsString()).get("error").get("code").asInt());
  }

  @Test
  public void primitiveRootString_returnsInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("\"hello\"");
    assertEquals(200, resp.getStatus());
    assertEquals(-32600,
        MAPPER.readTree(resp.getContentAsString()).get("error").get("code").asInt());
  }

  // --- Non-object element inside a batch → Invalid Request per element ---

  @Test
  public void batchWithNestedArray_returnsInvalidRequestArray() throws Exception {
    MockHttpServletResponse resp = doPost("[[]]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("response must be a JSON array", body.isArray());
    assertEquals(1, body.size());
    assertEquals(-32600, body.get(0).get("error").get("code").asInt());
    assertTrue("id in batch error must be JSON null", body.get(0).get("id").isNull());
  }

  @Test
  public void batchWithMixedObjectAndArray_objectProcessedArrayRejected() throws Exception {
    byte[] singleResp = "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":1}"
        .getBytes(StandardCharsets.UTF_8);
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(singleResp);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1}, []]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("response must be a JSON array", body.isArray());
    assertEquals(2, body.size());
    assertEquals("ok", body.get(0).get("result").asText());
    assertEquals(-32600, body.get(1).get("error").get("code").asInt());
  }

  @Test
  public void batchWithNumericAndStringElements_allGetInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("[42, \"foo\", true]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("response must be a JSON array", body.isArray());
    assertEquals(3, body.size());
    for (int i = 0; i < 3; i++) {
      assertEquals(-32600, body.get(i).get("error").get("code").asInt());
    }
  }

  // --- StreamReadConstraints: maxNestingDepth and maxTokenCount must be enforced ---

  @Test
  public void excessivelyNestedRequest_returnsParseError() throws Exception {
    int limit = Constant.MAX_NESTING_DEPTH;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i <= limit; i++) {
      sb.append('[');
    }
    sb.append('0');
    for (int i = 0; i <= limit; i++) {
      sb.append(']');
    }

    MockHttpServletResponse resp = doPost(sb.toString());
    assertEquals(200, resp.getStatus());
    JsonNode error = MAPPER.readTree(resp.getContentAsString()).get("error");
    assertEquals(-32700, error.get("code").asInt());
    // StreamConstraintsException message must be surfaced verbatim, not the generic text,
    // so callers can tell which constraint (nesting depth) was hit.
    String message = error.get("message").asText();
    assertNotEquals("JSON parse error", message);
    assertTrue("expected a nesting-depth constraint message, got: " + message,
        message.contains("nesting depth") && message.contains("exceeds the maximum allowed"));
  }

  @Test
  public void tooManyTokens_returnsParseError() throws Exception {
    int limit = Constant.MAX_TOKEN_COUNT;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < limit; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('0');
    }
    sb.append(']');

    MockHttpServletResponse resp = doPost(sb.toString());
    assertEquals(200, resp.getStatus());
    JsonNode error = MAPPER.readTree(resp.getContentAsString()).get("error");
    assertEquals(-32700, error.get("code").asInt());
    // StreamConstraintsException message must be surfaced verbatim, not the generic text,
    // so callers can tell which constraint (token count) was hit.
    String message = error.get("message").asText();
    assertNotEquals("JSON parse error", message);
    assertTrue("expected a token-count constraint message, got: " + message,
        message.contains("Token count") && message.contains("exceeds the maximum allowed"));
  }

  // --- helpers ---

  private static void assertInvalidRequestWithNullId(JsonNode response) {
    assertEquals(MAPPER.getNodeFactory().textNode("2.0"), response.get("jsonrpc"));
    assertEquals(MAPPER.getNodeFactory().numberNode(-32600),
        response.get("error").get("code"));
    assertEquals(MAPPER.getNodeFactory().textNode("Invalid Request"),
        response.get("error").get("message"));
    assertFalse(response.get("error").has("data"));
    assertTrue(response.get("id").isNull());
  }

  private MockHttpServletResponse doPost(String body) throws Exception {
    MockHttpServletResponse resp = new MockHttpServletResponse();
    doPost(body, resp);
    return resp;
  }

  private void doPost(String body, HttpServletResponse response) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jsonrpc");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    servlet.callDoPost(request, response);
  }

  private void assertCleanupFailureDoesNotReplaceFatal(HttpServletResponse response)
      throws Exception {
    StackOverflowError fatal = new StackOverflowError("original-fatal-marker");
    doThrow(fatal).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    StackOverflowError thrown = assertThrows(StackOverflowError.class,
        () -> doPost("{\"method\":\"eth_blockNumber\",\"id\":1}", response));

    assertSame(fatal, thrown);
  }

  private static class IOExceptionOnFlushResponse extends HttpServletResponseWrapper {

    IOExceptionOnFlushResponse() {
      super(new MockHttpServletResponse());
    }

    @Override
    public void flushBuffer() throws IOException {
      throw new IOException("cleanup-io-marker");
    }
  }

  private static class ErrorOnFlushResponse extends HttpServletResponseWrapper {

    ErrorOnFlushResponse() {
      super(new MockHttpServletResponse());
    }

    @Override
    public void flushBuffer() throws IOException {
      throw new OutOfMemoryError("cleanup-error-marker");
    }
  }

  private static class TestableServlet extends JsonRpcServlet {

    void callDoPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      doPost(req, resp);
    }
  }
}
