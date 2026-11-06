package org.tron.core.services.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.core.config.args.Args;
import org.tron.core.services.http.servlets.DelegateResourceServlet;
import org.tron.core.services.http.servlets.FreezeBalanceV2Servlet;
import org.tron.core.services.http.servlets.GetBlockByIdServlet;
import org.tron.core.services.http.servlets.GetBlockByNumServlet;
import org.tron.core.services.http.servlets.JsonFormat;
import org.tron.core.services.http.servlets.PostParams;
import org.tron.core.services.http.servlets.RateLimiterServlet;
import org.tron.core.services.ratelimiter.RateLimiterContainer;

public class RateLimiterServletFormInputTest extends BaseTest {

  private static final String FORM = "application/x-www-form-urlencoded";
  private static final String JSON = "application/json";
  private static final String OWNER = "TJCnKsPa7y5okkXvQAidZBzqx3QyQ6sxMW";

  @Resource
  private GetBlockByNumServlet getBlockByNumServlet;
  @Resource
  private GetBlockByIdServlet getBlockByIdServlet;
  @Resource
  private FreezeBalanceV2Servlet freezeBalanceV2Servlet;
  @Resource
  private DelegateResourceServlet delegateResourceServlet;
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

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
      atEntry = JsonFormat.isFormInput();
      try {
        PostParams.getPostParams(req);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      } finally {
        afterParams = JsonFormat.isFormInput();
      }
      if (failAfterParams) {
        throw new IllegalStateException("failure after form conversion");
      }
    }
  }

  @Before
  public void before() {
    JsonFormat.clearFormInput();
  }

  @After
  public void after() {
    JsonFormat.clearFormInput();
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
    assertFalse(JsonFormat.isFormInput());
  }

  @Test
  public void formMatchesJsonForTransactionBuilders() throws Exception {
    String freezeForm = call(freezeBalanceV2Servlet, FORM, "owner_address=" + OWNER
        + "&frozen_balance=1000000&resource=ENERGY&visible=true");
    String freezeJson = call(freezeBalanceV2Servlet, JSON, "{\"owner_address\":\"" + OWNER
        + "\",\"frozen_balance\":1000000,\"resource\":\"ENERGY\",\"visible\":true}");
    assertFalse(freezeForm, freezeForm.contains("parse"));
    assertEquals(freezeJson, freezeForm);

    String delegateForm = call(delegateResourceServlet, FORM, "owner_address=" + OWNER
        + "&receiver_address=" + OWNER + "&balance=1&resource=ENERGY&lock=true&visible=true");
    String delegateJson = call(delegateResourceServlet, JSON, "{\"owner_address\":\"" + OWNER
        + "\",\"receiver_address\":\"" + OWNER
        + "\",\"balance\":1,\"resource\":\"ENERGY\",\"lock\":true,\"visible\":true}");
    assertFalse(delegateForm, delegateForm.contains("parse"));
    assertFalse(delegateForm, delegateForm.contains("Expected \\\"true\\\""));
    assertEquals(delegateJson, delegateForm);
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
    assertFalse(JsonFormat.isFormInput());
  }

  @Test
  public void flagClearedAfterParseFailure() throws Exception {
    call(getBlockByNumServlet, FORM, "num=abc");

    assertFalse(JsonFormat.isFormInput());
  }

  @Test
  public void flagClearedWhenHandlerThrowsAfterConversion() throws Exception {
    ProbeServlet servlet = probe();
    servlet.failAfterParams = true;
    call(servlet, FORM, "num=0");

    assertEquals(Boolean.TRUE, servlet.afterParams);
    assertFalse(JsonFormat.isFormInput());
  }

  @Test
  public void requestStartsStrictEvenWithStaleFlag() throws Exception {
    ProbeServlet servlet = probe();
    JsonFormat.setFormInput(true);
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
