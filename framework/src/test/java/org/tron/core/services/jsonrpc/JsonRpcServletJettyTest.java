package org.tron.core.services.jsonrpc;

import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.lang.reflect.Field;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpEntity;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.common.TestConstants;
import org.tron.common.application.HttpService;
import org.tron.common.utils.PublicMethod;
import org.tron.core.config.args.Args;
import org.tron.core.services.http.RateLimiterServlet;
import org.tron.core.services.ratelimiter.RateLimiterContainer;

public class JsonRpcServletJettyTest {

  private static final String FATAL_MARKER = "fatal-response-marker";

  @ClassRule
  public static final TemporaryFolder TEMPORARY_FOLDER = new TemporaryFolder();

  private static TestJsonRpcHttpService httpService;
  private static CloseableHttpClient client;
  private static URI endpoint;
  private static FatalServiceImpl fatalService;

  @BeforeClass
  public static void setUpClass() throws Exception {
    Args.setParam(new String[]{"-d", TEMPORARY_FOLDER.newFolder().toString()},
        TestConstants.TEST_CONF);

    fatalService = new FatalServiceImpl();
    JsonRpcServer rpcServer = new JsonRpcServer(fatalService, FatalService.class);
    rpcServer.setErrorResolver(JsonRpcErrorResolver.INSTANCE);
    rpcServer.setShouldLogInvocationErrors(false);

    TestJsonRpcServlet servlet = new TestJsonRpcServlet(rpcServer);
    Field containerField = RateLimiterServlet.class.getDeclaredField("container");
    containerField.setAccessible(true);
    containerField.set(servlet, new RateLimiterContainer());

    int port = PublicMethod.chooseRandomPort();
    httpService = new TestJsonRpcHttpService(port, servlet);
    httpService.start().get(10, TimeUnit.SECONDS);
    endpoint = new URI(String.format("http://localhost:%d/jsonrpc", port));
    client = HttpClients.custom().disableAutomaticRetries().build();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    try {
      if (client != null) {
        client.close();
      }
    } finally {
      try {
        if (httpService != null) {
          httpService.stop();
        }
      } finally {
        Args.clearParam();
      }
    }
  }

  @Test
  public void fatalErrorDoesNotReachJettyDefaultErrorPage() throws Exception {
    HttpPost request = new HttpPost(endpoint);
    request.setHeader("Accept", "application/json");
    request.setEntity(new StringEntity(
        "{\"jsonrpc\":\"2.0\",\"method\":\"test_fatal\",\"params\":[],\"id\":1}",
        ContentType.APPLICATION_JSON));

    try (CloseableHttpResponse response = client.execute(request)) {
      HttpEntity entity = response.getEntity();
      byte[] body = entity == null ? new byte[0] : EntityUtils.toByteArray(entity);
      String text = new String(body, StandardCharsets.UTF_8);

      Assert.assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          response.getStatusLine().getStatusCode());
      Assert.assertEquals(0, body.length);
      Assert.assertFalse(text.contains(FATAL_MARKER));
      Assert.assertFalse(text.contains(StackOverflowError.class.getName()));
    } catch (NoHttpResponseException | ConnectionClosedException | SocketException expected) {
      // A fatal Error may close the connection after the best-effort empty 500 is committed.
    }

    Assert.assertTrue("the request must reach the JSON-RPC method", fatalService.invoked.get());
  }

  public interface FatalService {

    @JsonRpcMethod("test_fatal")
    String fatal();
  }

  private static class FatalServiceImpl implements FatalService {

    private final AtomicBoolean invoked = new AtomicBoolean();

    @Override
    public String fatal() {
      invoked.set(true);
      throw new StackOverflowError(FATAL_MARKER);
    }
  }

  private static class TestJsonRpcServlet extends JsonRpcServlet {

    private final JsonRpcServer testRpcServer;

    TestJsonRpcServlet(JsonRpcServer testRpcServer) {
      this.testRpcServer = testRpcServer;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
      setRpcServer(testRpcServer);
    }
  }

  private static class TestJsonRpcHttpService extends HttpService {

    private final JsonRpcServlet servlet;

    TestJsonRpcHttpService(int port, JsonRpcServlet servlet) {
      this.port = port;
      this.contextPath = "/";
      this.servlet = servlet;
    }

    @Override
    protected void addServlet(ServletContextHandler context) {
      context.addServlet(new ServletHolder(servlet), "/jsonrpc");
    }
  }
}
