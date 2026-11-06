package org.tron.core.services.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.tron.api.GrpcAPI.NumberMessage;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.core.config.args.Args;
import org.tron.core.services.http.servlets.GetBlockByIdServlet;
import org.tron.core.services.http.servlets.GetBlockByNumServlet;
import org.tron.core.services.http.servlets.JsonFormat;
import org.tron.core.services.http.servlets.PostParams;
import org.tron.core.services.http.servlets.RateLimiterServlet;
import org.tron.core.services.ratelimiter.RateLimiterContainer;

public class RateLimiterServletFormInputTest extends BaseTest {

  private static final String FORM = "application/x-www-form-urlencoded";
  private static final String JSON = "application/json";

  @Resource
  private GetBlockByNumServlet getBlockByNumServlet;
  @Resource
  private GetBlockByIdServlet getBlockByIdServlet;
  @Resource
  private RateLimiterContainer rateLimiterContainer;

  static {
    Args.setParam(new String[]{"--output-directory", dbPath()}, TestConstants.TEST_CONF);
  }

  /** Records the form-input flag around PostParams, optionally failing after conversion. */
  private static class ProbeServlet extends RateLimiterServlet {
    private Boolean atEntry;
    private Boolean afterParams;
    private boolean failAfterParams;
    private boolean ioFailureAfterParams;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      atEntry = JsonFormat.hasFormBody();
      try {
        PostParams.getPostParams(req);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      } finally {
        afterParams = JsonFormat.hasFormBody();
      }
      if (failAfterParams) {
        throw new IllegalStateException("failure after form conversion");
      }
      if (ioFailureAfterParams) {
        throw new IOException("io failure after form conversion");
      }
    }
  }

  /** Merges the converted body, then an equal-content copy, within one request. */
  private static class SecondMergeServlet extends RateLimiterServlet {
    private Long bodyNum;
    private Exception copyError;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
      try {
        PostParams params = PostParams.getPostParams(req);
        NumberMessage.Builder body = NumberMessage.newBuilder();
        JsonFormat.merge(params.getParams(), body, params.isVisible());
        bodyNum = body.getNum();
        String copy = new String(params.getParams().toCharArray());
        JsonFormat.merge(copy, NumberMessage.newBuilder(), params.isVisible());
      } catch (Exception e) {
        copyError = e;
      }
    }
  }

  @Before
  public void before() {
    JsonFormat.clearFormBody();
  }

  @After
  public void after() {
    JsonFormat.clearFormBody();
  }

  private ProbeServlet probe() {
    ProbeServlet servlet = new ProbeServlet();
    ReflectionTestUtils.setField(servlet, RateLimiterServlet.class, "container",
        rateLimiterContainer, RateLimiterContainer.class);
    return servlet;
  }

  private static MockHttpServletRequest post(String contentType, String body) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setContentType(contentType);
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }

  private static String call(RateLimiterServlet servlet, String contentType, String body)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.service(post(contentType, body), response);
    return response.getContentAsString();
  }

  @Test
  public void formNumericFieldParses() throws Exception {
    String form = call(getBlockByNumServlet, FORM, "num=0");

    assertFalse(form, form.contains("Error"));
    assertTrue(form, form.contains("blockID"));
    assertEquals(call(getBlockByNumServlet, JSON, "{\"num\":0}"), form);
    assertFalse(JsonFormat.hasFormBody());
  }

  @Test
  public void onlyConvertedBodyIsLenientWithinRequest() throws Exception {
    SecondMergeServlet servlet = new SecondMergeServlet();
    ReflectionTestUtils.setField(servlet, RateLimiterServlet.class, "container",
        rateLimiterContainer, RateLimiterContainer.class);
    call(servlet, FORM, "num=7");

    assertEquals(Long.valueOf(7L), servlet.bodyNum);
    assertTrue(String.valueOf(servlet.copyError),
        servlet.copyError instanceof JsonFormat.ParseException);
    assertFalse(JsonFormat.hasFormBody());
  }

  @Test
  public void jsonBodyUnderFormContentTypeStaysStrict() throws Exception {
    String response = call(getBlockByNumServlet, FORM, "{\"num\":\"0\"}");

    assertTrue(response, response.contains("Couldn't parse integer"));
  }

  @Test
  public void jsonContentTypeStaysStrict() throws Exception {
    String response = call(getBlockByNumServlet, JSON, "{\"num\":\"0\"}");

    assertTrue(response, response.contains("Couldn't parse integer"));
  }

  @Test
  public void formContentTypeWithParametersIsConverted() throws Exception {
    String expected = call(getBlockByNumServlet, JSON, "{\"num\":0}");
    for (String contentType : new String[]{FORM + "; charset=UTF-8", FORM + ";charset=utf-8",
        "Application/X-WWW-Form-Urlencoded"}) {
      assertEquals(contentType, expected, call(getBlockByNumServlet, contentType, "num=0"));
    }
  }

  @Test
  public void numericBytesValueUnchanged() throws Exception {
    String id = "0000000000000000000000000000000000000000000000000000000000000001";
    assertEquals(call(getBlockByIdServlet, JSON, "{\"value\":\"" + id + "\"}"),
        call(getBlockByIdServlet, FORM, "value=" + id));
  }

  @Test
  public void flagClearedAfterSuccessfulFormRequest() throws Exception {
    ProbeServlet servlet = probe();
    call(servlet, FORM, "num=0");

    assertEquals(Boolean.TRUE, servlet.afterParams);
    assertFalse(JsonFormat.hasFormBody());
  }

  @Test
  public void flagClearedAfterParseFailure() throws Exception {
    call(getBlockByNumServlet, FORM, "num=abc");

    assertFalse(JsonFormat.hasFormBody());
  }

  @Test
  public void flagClearedWhenHandlerThrowsAfterConversion() throws Exception {
    ProbeServlet servlet = probe();
    servlet.failAfterParams = true;
    call(servlet, FORM, "num=0");

    assertEquals(Boolean.TRUE, servlet.afterParams);
    assertFalse(JsonFormat.hasFormBody());
  }

  @Test
  public void flagClearedWhenHandlerPropagatesIoException() {
    ProbeServlet servlet = probe();
    servlet.ioFailureAfterParams = true;
    assertThrows(IOException.class, () -> call(servlet, FORM, "num=0"));

    assertEquals(Boolean.TRUE, servlet.afterParams);
    assertFalse(JsonFormat.hasFormBody());
  }

  @Test
  public void requestStartsStrictEvenWithStaleFlag() throws Exception {
    ProbeServlet servlet = probe();
    JsonFormat.setFormBody("{}");
    call(servlet, JSON, "{\"num\":0}");

    assertEquals(Boolean.FALSE, servlet.atEntry);
    assertEquals(Boolean.FALSE, servlet.afterParams);
  }

  @Test
  public void nonFormInputsDoNotEnableFlag() throws Exception {
    String[][] cases = {{FORM, ""}, {FORM, "{\"num\":0}"}, {JSON, "{\"num\":0}"},
        {JSON, "num=0"}};
    for (String[] c : cases) {
      ProbeServlet servlet = probe();
      call(servlet, c[0], c[1]);
      assertEquals(c[0] + " " + c[1], Boolean.FALSE, servlet.afterParams);
    }
  }
}
