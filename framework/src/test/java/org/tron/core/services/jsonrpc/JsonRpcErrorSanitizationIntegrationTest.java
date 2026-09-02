package org.tron.core.services.jsonrpc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.TronError;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;

public class JsonRpcErrorSanitizationIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SENSITIVE_MARKER = "sensitive-marker";

  private int savedMaxBatchSize;
  private int savedMaxResponseSize;

  @Before
  public void setUp() {
    CommonParameter parameter = CommonParameter.getInstance();
    savedMaxBatchSize = parameter.jsonRpcMaxBatchSize;
    savedMaxResponseSize = parameter.jsonRpcMaxResponseSize;
    parameter.jsonRpcMaxBatchSize = 0;
    parameter.jsonRpcMaxResponseSize = 0;
  }

  @After
  public void tearDown() {
    CommonParameter parameter = CommonParameter.getInstance();
    parameter.jsonRpcMaxBatchSize = savedMaxBatchSize;
    parameter.jsonRpcMaxResponseSize = savedMaxResponseSize;
  }

  @Test
  public void testUnmappedExceptionIsSanitizedOnWire() throws Exception {
    JsonRpcServer server = newServer(new ErrorServiceImpl(), ErrorService.class);

    JsonNode response = handle(server, request("test_unhandled", "[]", 1));

    assertSanitizedInternalError(response, 1);
    assertNoInternalDetails(response.toString());
  }

  @Test
  public void testNonFatalErrorIsSanitizedByRealServer() throws Exception {
    JsonRpcServer server = newServer(new ErrorServiceImpl(), ErrorService.class);

    JsonNode response = handle(server, request("test_assertion", "[]", 1));

    assertSanitizedInternalError(response, 1);
    assertNoInternalDetails(response.toString());
  }

  @Test
  public void testFatalErrorsEscapeRealServer() throws Exception {
    ErrorServiceImpl service = new ErrorServiceImpl();
    JsonRpcServer server = newServer(service, ErrorService.class);

    StackOverflowError direct = Assert.assertThrows(StackOverflowError.class,
        () -> handle(server, request("test_fatal", "[]", 1)));
    Assert.assertSame(service.directFatal, direct);

    TronError tronError = Assert.assertThrows(TronError.class,
        () -> handle(server, request("test_tron_error", "[]", 2)));
    Assert.assertSame(service.tronError, tronError);

    TronJsonRpc mappedService = mock(TronJsonRpc.class);
    StackOverflowError wrappedFatal = new StackOverflowError("wrapped-fatal-marker");
    when(mappedService.getLogs(any(TronJsonRpc.FilterRequest.class)))
        .thenThrow(new ExecutionException(wrappedFatal));
    JsonRpcServer mappedServer = newServer(mappedService, TronJsonRpc.class);

    StackOverflowError wrapped = Assert.assertThrows(StackOverflowError.class,
        () -> handle(mappedServer, request("eth_getLogs", "[{}]", 2)));
    Assert.assertSame(wrappedFatal, wrapped);
  }

  @Test
  public void testMappedExecutionExceptionUsesFixedMessage() throws Exception {
    TronJsonRpc service = mock(TronJsonRpc.class);
    when(service.getLogs(any(TronJsonRpc.FilterRequest.class)))
        .thenThrow(new ExecutionException(new NullPointerException(SENSITIVE_MARKER)));
    JsonRpcServer server = newServer(service, TronJsonRpc.class);

    JsonNode response = handle(server, request("eth_getLogs", "[{}]", 1));

    assertMappedInternalError(response, 1);
    assertNoInternalDetails(response.toString());
  }

  @Test
  public void testMappedInterruptedExceptionUsesFixedMessage() throws Exception {
    Thread.interrupted();
    try {
      TronJsonRpc service = mock(TronJsonRpc.class);
      when(service.getLogs(any(TronJsonRpc.FilterRequest.class))).thenAnswer(invocation -> {
        Thread.currentThread().interrupt();
        throw new InterruptedException();
      });
      JsonRpcServer server = newServer(service, TronJsonRpc.class);

      JsonNode response = handle(server, request("eth_getLogs", "[{}]", 1));

      assertMappedInternalError(response, 1);
      Assert.assertFalse(response.toString().contains("InterruptedException"));
      Assert.assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void testGetFilterLogsAsyncFailuresUseFixedMessage() throws Exception {
    TronJsonRpc service = mock(TronJsonRpc.class);
    when(service.getFilterLogs("0xdeadbeef"))
        .thenThrow(new ExecutionException(new NullPointerException(SENSITIVE_MARKER)))
        .thenThrow(new InterruptedException());
    JsonRpcServer server = newServer(service, TronJsonRpc.class);

    JsonNode executionResponse = handle(server,
        request("eth_getFilterLogs", "[\"0xdeadbeef\"]", 1));
    JsonNode interruptedResponse = handle(server,
        request("eth_getFilterLogs", "[\"0xdeadbeef\"]", 2));

    assertMappedInternalError(executionResponse, 1);
    assertMappedInternalError(interruptedResponse, 2);
    assertNoInternalDetails(executionResponse.toString());
    Assert.assertFalse(interruptedResponse.toString().contains("InterruptedException"));
  }

  @Test
  public void testChainIdentityKeepsDocumentedCodeAndSanitizesDetails() throws Exception {
    TronJsonRpc service = mock(TronJsonRpc.class);
    // Shaped like production: the exception carries a cause, so the response must withhold the
    // exception message, the cause message and both type names.
    when(service.ethChainId()).thenThrow(
        new JsonRpcInternalException(SENSITIVE_MARKER, new RuntimeException(SENSITIVE_MARKER)));
    when(service.getNetVersion()).thenThrow(
        new JsonRpcInternalException(SENSITIVE_MARKER, new RuntimeException(SENSITIVE_MARKER)));
    JsonRpcServer server = newServer(service, TronJsonRpc.class);

    JsonNode chainId = handle(server, request("eth_chainId", "[]", 1));
    JsonNode netVersion = handle(server, request("net_version", "[]", 2));

    // -32001 is documented as JSON_RPC_UNDERLYING_INTERNAL_ERROR for chain identity lookups,
    // so the code is kept while the underlying message and exception type are withheld.
    assertChainIdentityError(chainId, 1);
    assertChainIdentityError(netVersion, 2);
    assertNoInternalDetails(chainId.toString());
    assertNoInternalDetails(netVersion.toString());
  }

  @Test
  public void testMappedBusinessMessageIsPreserved() throws Exception {
    TronJsonRpc service = mock(TronJsonRpc.class);
    when(service.getFilterLogs("0xdeadbeef"))
        .thenThrow(new ItemNotFoundException("filter not found"));
    JsonRpcServer server = newServer(service, TronJsonRpc.class);

    JsonNode response = handle(server,
        request("eth_getFilterLogs", "[\"0xdeadbeef\"]", 1));

    JsonNode error = response.get("error");
    Assert.assertEquals(-32000, error.get("code").asInt());
    Assert.assertEquals("filter not found", error.get("message").asText());
    Assert.assertEquals("{}", error.get("data").asText());
  }

  @Test
  public void testServletBatchIsolatesInvalidElement() throws Exception {
    TestableServlet servlet = newServlet(new ErrorServiceImpl());

    MockHttpServletResponse response = post(servlet,
        "[null," + request("test_ok", "[]", 2) + "]");
    JsonNode body = MAPPER.readTree(response.getContentAsByteArray());

    Assert.assertTrue(body.isArray());
    Assert.assertEquals(2, body.size());
    Assert.assertEquals(-32600, body.get(0).get("error").get("code").asInt());
    Assert.assertTrue(body.get(0).get("id").isNull());
    Assert.assertEquals("ok", body.get(1).get("result").asText());
    Assert.assertEquals(2, body.get(1).get("id").asInt());
  }

  @Test
  public void testServletBatchIsolatesUnhandledException() throws Exception {
    TestableServlet servlet = newServlet(new ErrorServiceImpl());

    MockHttpServletResponse response = post(servlet,
        "[" + request("test_unhandled", "[]", 1) + ","
            + request("test_ok", "[]", 2) + "]");
    JsonNode body = MAPPER.readTree(response.getContentAsByteArray());

    Assert.assertTrue(body.isArray());
    Assert.assertEquals(2, body.size());
    assertSanitizedInternalError(body.get(0), 1);
    Assert.assertEquals("ok", body.get(1).get("result").asText());
    Assert.assertEquals(2, body.get(1).get("id").asInt());
    assertNoInternalDetails(body.toString());
  }

  @Test
  public void testServletSingleRequestPreservesTransportContract() throws Exception {
    TestableServlet servlet = newServlet(new ErrorServiceImpl());

    MockHttpServletResponse response = post(servlet,
        request("test_ok", "[]", 1));
    JsonNode body = MAPPER.readTree(response.getContentAsByteArray());

    Assert.assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    Assert.assertEquals("application/json-rpc", response.getContentType());
    Assert.assertEquals("ok", body.get("result").asText());
    Assert.assertEquals(1, body.get("id").asInt());
  }

  @Test
  public void testServletSingleNotificationReturnsEmptyBody() throws Exception {
    TestableServlet servlet = newServlet(new ErrorServiceImpl());

    MockHttpServletResponse response = post(servlet,
        "{\"jsonrpc\":\"2.0\",\"method\":\"test_ok\",\"params\":[]}");

    Assert.assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    Assert.assertEquals("application/json-rpc", response.getContentType());
    Assert.assertEquals(0, response.getContentAsByteArray().length);
  }

  @Test
  public void testServletRejectsNonNullScalarParamsAsInvalidRequest() throws Exception {
    TestableServlet servlet = newServlet(new ErrorServiceImpl());
    String[] scalarParams = {"5", "\"value\"", "true"};

    for (int i = 0; i < scalarParams.length; i++) {
      int id = i + 1;
      MockHttpServletResponse response = post(servlet,
          request("test_ok", scalarParams[i], id));
      JsonNode body = MAPPER.readTree(response.getContentAsByteArray());

      Assert.assertEquals(HttpServletResponse.SC_OK, response.getStatus());
      Assert.assertEquals("application/json-rpc", response.getContentType());
      assertInvalidRequest(body);
      Assert.assertEquals(MAPPER.getNodeFactory().numberNode(id), body.get("id"));
    }
  }

  @Test
  public void testServletScalarParamsApplyRequestIdRules() throws Exception {
    TestableServlet servlet = newServlet(new ErrorServiceImpl());

    MockHttpServletResponse stringIdResponse = post(servlet,
        "{\"jsonrpc\":\"2.0\",\"method\":\"test_ok\",\"params\":5,"
            + "\"id\":\"request-1\"}");
    assertInvalidRequestResponse(stringIdResponse,
        MAPPER.getNodeFactory().textNode("request-1"));

    MockHttpServletResponse missingIdResponse = post(servlet,
        "{\"jsonrpc\":\"2.0\",\"method\":\"test_ok\",\"params\":5}");
    assertInvalidRequestResponse(missingIdResponse, MAPPER.getNodeFactory().nullNode());

    MockHttpServletResponse nullIdResponse = post(servlet,
        "{\"jsonrpc\":\"2.0\",\"method\":\"test_ok\",\"params\":5,\"id\":null}");
    assertInvalidRequestResponse(nullIdResponse, MAPPER.getNodeFactory().nullNode());

    MockHttpServletResponse invalidIdResponse = post(servlet,
        "{\"jsonrpc\":\"2.0\",\"method\":\"test_ok\",\"params\":5,\"id\":true}");
    assertInvalidRequestResponse(invalidIdResponse, MAPPER.getNodeFactory().nullNode());
  }

  @Test
  public void testServletValidatesScalarParamsBeforeMethodLookup() throws Exception {
    TestableServlet servlet = newServlet(new ErrorServiceImpl());

    MockHttpServletResponse scalarResponse = post(servlet,
        request("test_unknown", "5", 1));
    JsonNode scalarBody = MAPPER.readTree(scalarResponse.getContentAsByteArray());
    assertInvalidRequest(scalarBody);
    Assert.assertEquals(MAPPER.getNodeFactory().numberNode(1), scalarBody.get("id"));

    MockHttpServletResponse structuredResponse = post(servlet,
        request("test_unknown", "[]", 2));
    JsonNode structuredBody = MAPPER.readTree(structuredResponse.getContentAsByteArray());
    JsonNode structuredError = structuredBody.get("error");
    Assert.assertEquals(MAPPER.getNodeFactory().numberNode(-32601),
        structuredError.get("code"));
    Assert.assertEquals(MAPPER.getNodeFactory().textNode("method not found"),
        structuredError.get("message"));
    Assert.assertFalse(structuredError.has("data"));
    Assert.assertEquals(MAPPER.getNodeFactory().numberNode(2), structuredBody.get("id"));
  }

  @Test
  public void testServletForwardsNullArrayAndObjectParamsToDispatch() throws Exception {
    TestableServlet servlet = newServlet(new ErrorServiceImpl());

    MockHttpServletResponse noArgResponse = post(servlet,
        request("test_ok", "null", 1));
    JsonNode noArgBody = MAPPER.readTree(noArgResponse.getContentAsByteArray());
    Assert.assertEquals(MAPPER.getNodeFactory().textNode("ok"), noArgBody.get("result"));
    Assert.assertEquals(MAPPER.getNodeFactory().numberNode(1), noArgBody.get("id"));

    MockHttpServletResponse oneArgResponse = post(servlet,
        request("test_echo", "[]", 2));
    JsonNode oneArgBody = MAPPER.readTree(oneArgResponse.getContentAsByteArray());
    JsonNode error = oneArgBody.get("error");
    Assert.assertEquals(MAPPER.getNodeFactory().numberNode(-32602), error.get("code"));
    Assert.assertEquals(MAPPER.getNodeFactory().textNode("method parameters invalid"),
        error.get("message"));
    Assert.assertEquals(MAPPER.getNodeFactory().numberNode(2), oneArgBody.get("id"));

    MockHttpServletResponse objectResponse = post(servlet,
        request("test_ok", "{}", 3));
    JsonNode objectBody = MAPPER.readTree(objectResponse.getContentAsByteArray());
    Assert.assertEquals(MAPPER.getNodeFactory().textNode("ok"), objectBody.get("result"));
    Assert.assertEquals(MAPPER.getNodeFactory().numberNode(3), objectBody.get("id"));
  }

  @Test
  public void testFatalErrorEscapesServlet() {
    ErrorServiceImpl service = new ErrorServiceImpl();
    TestableServlet servlet = newServlet(service);
    MockHttpServletResponse response = new MockHttpServletResponse();

    StackOverflowError thrown = Assert.assertThrows(StackOverflowError.class,
        () -> post(servlet, request("test_fatal", "[]", 1), response));

    Assert.assertSame(service.directFatal, thrown);
    assertBareInternalServerError(response);
  }

  @Test
  public void testMappedFatalErrorEscapesServlet() throws Exception {
    TronJsonRpc service = mock(TronJsonRpc.class);
    StackOverflowError fatal = new StackOverflowError("wrapped-fatal-marker");
    when(service.getLogs(any(TronJsonRpc.FilterRequest.class)))
        .thenThrow(new ExecutionException(fatal));
    TestableServlet servlet = newServlet(service, TronJsonRpc.class);
    MockHttpServletResponse response = new MockHttpServletResponse();

    StackOverflowError thrown = Assert.assertThrows(StackOverflowError.class,
        () -> post(servlet, request("eth_getLogs", "[{}]", 1), response));

    Assert.assertSame(fatal, thrown);
    assertBareInternalServerError(response);
  }

  @Test
  public void testFatalErrorDiscardsAccumulatedBatchResponse() {
    ErrorServiceImpl service = new ErrorServiceImpl();
    TestableServlet servlet = newServlet(service);
    MockHttpServletResponse response = new MockHttpServletResponse();

    StackOverflowError thrown = Assert.assertThrows(StackOverflowError.class,
        () -> post(servlet,
            "[" + request("test_ok", "[]", 1) + ","
                + request("test_fatal", "[]", 2) + "]", response));

    Assert.assertSame(service.directFatal, thrown);
    assertBareInternalServerError(response);
  }

  private static JsonRpcServer newServer(Object service, Class<?> serviceInterface) {
    JsonRpcServer server = new JsonRpcServer(service, serviceInterface);
    server.setErrorResolver(JsonRpcErrorResolver.INSTANCE);
    server.setShouldLogInvocationErrors(false);
    return server;
  }

  private static TestableServlet newServlet(ErrorServiceImpl service) {
    return newServlet(service, ErrorService.class);
  }

  private static TestableServlet newServlet(Object service, Class<?> serviceInterface) {
    TestableServlet servlet = new TestableServlet();
    servlet.setRpcServer(newServer(service, serviceInterface));
    return servlet;
  }

  private static JsonNode handle(JsonRpcServer server, String json) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    server.handleRequest(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), output);
    return MAPPER.readTree(output.toByteArray());
  }

  private static MockHttpServletResponse post(TestableServlet servlet, String body)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    post(servlet, body, response);
    return response;
  }

  private static void post(TestableServlet servlet, String body,
      MockHttpServletResponse response) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jsonrpc");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    servlet.callDoPost(request, response);
  }

  private static String request(String method, String params, int id) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method
        + "\",\"params\":" + params + ",\"id\":" + id + "}";
  }

  private static void assertSanitizedInternalError(JsonNode response, int id) {
    JsonNode error = response.get("error");
    Assert.assertEquals(-32603, error.get("code").asInt());
    Assert.assertEquals("Internal error", error.get("message").asText());
    Assert.assertFalse(error.has("data"));
    Assert.assertEquals(id, response.get("id").asInt());
  }

  private static void assertInvalidRequest(JsonNode response) {
    Assert.assertTrue(response.isObject());
    Assert.assertEquals(MAPPER.getNodeFactory().textNode("2.0"), response.get("jsonrpc"));
    JsonNode error = response.get("error");
    Assert.assertEquals(MAPPER.getNodeFactory().numberNode(-32600), error.get("code"));
    Assert.assertEquals(MAPPER.getNodeFactory().textNode("Invalid Request"),
        error.get("message"));
    Assert.assertFalse(error.has("data"));
  }

  private static void assertInvalidRequestResponse(MockHttpServletResponse response,
      JsonNode expectedId) throws Exception {
    Assert.assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    Assert.assertEquals("application/json-rpc", response.getContentType());
    JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
    assertInvalidRequest(body);
    Assert.assertEquals(expectedId, body.get("id"));
  }

  private static void assertMappedInternalError(JsonNode response, int id) {
    JsonNode error = response.get("error");
    Assert.assertEquals(-32000, error.get("code").asInt());
    Assert.assertEquals("Internal error", error.get("message").asText());
    Assert.assertEquals("{}", error.get("data").asText());
    Assert.assertEquals(id, response.get("id").asInt());
  }

  private static void assertChainIdentityError(JsonNode response, int id) {
    JsonNode error = response.get("error");
    Assert.assertEquals(-32001, error.get("code").asInt());
    Assert.assertEquals("Chain identity unavailable", error.get("message").asText());
    Assert.assertEquals("{}", error.get("data").asText());
    Assert.assertEquals(id, response.get("id").asInt());
  }

  private static void assertNoInternalDetails(String response) {
    Assert.assertFalse(response.contains(SENSITIVE_MARKER));
    Assert.assertFalse(response.contains("java."));
    Assert.assertFalse(response.contains("NullPointerException"));
    Assert.assertFalse(response.contains("RuntimeException"));
  }

  private static void assertBareInternalServerError(MockHttpServletResponse response) {
    Assert.assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
    Assert.assertEquals(0, response.getContentAsByteArray().length);
    Assert.assertTrue(response.isCommitted());
  }

  public interface ErrorService {

    @JsonRpcMethod("test_ok")
    String ok();

    @JsonRpcMethod("test_echo")
    String echo(String value);

    @JsonRpcMethod("test_unhandled")
    String unhandled();

    @JsonRpcMethod("test_assertion")
    String assertion();

    @JsonRpcMethod("test_fatal")
    String fatal();

    @JsonRpcMethod("test_tron_error")
    String tronError();
  }

  public static class ErrorServiceImpl implements ErrorService {

    private final StackOverflowError directFatal =
        new StackOverflowError("direct-fatal-marker");
    private final TronError tronError =
        new TronError("tron-fatal-marker", TronError.ErrCode.API_SERVER_INIT);

    @Override
    public String ok() {
      return "ok";
    }

    @Override
    public String echo(String value) {
      return value;
    }

    @Override
    public String unhandled() {
      throw new RuntimeException(SENSITIVE_MARKER);
    }

    @Override
    public String assertion() {
      throw new AssertionError(SENSITIVE_MARKER);
    }

    @Override
    public String fatal() {
      throw directFatal;
    }

    @Override
    public String tronError() {
      throw tronError;
    }
  }

  private static class TestableServlet extends JsonRpcServlet {

    void callDoPost(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      doPost(request, response);
    }
  }
}
