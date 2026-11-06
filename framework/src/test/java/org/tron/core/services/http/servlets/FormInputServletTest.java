package org.tron.core.services.http.servlets;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.BalanceContract.DelegateResourceContract;
import org.tron.protos.contract.BalanceContract.FreezeBalanceV2Contract;
import org.tron.protos.contract.Common.ResourceCode;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract.ABI.Entry;

public class FormInputServletTest extends BaseHttpTest {

  private final String owner = ByteArray.toHexString(new ECKey().getAddress());
  private final String receiver = ByteArray.toHexString(new ECKey().getAddress());
  private FreezeBalanceV2Servlet freezeServlet;
  private DelegateResourceServlet delegateServlet;
  private DeployContractServlet deployServlet;

  @Override
  protected void setUpMocks() throws Exception {
    freezeServlet = new FreezeBalanceV2Servlet();
    delegateServlet = new DelegateResourceServlet();
    deployServlet = new DeployContractServlet();
    injectWallet(freezeServlet);
    injectWallet(delegateServlet);
    injectWallet(deployServlet);
    when(wallet.createTransactionCapsule(any(), any()))
        .thenReturn(new TransactionCapsule(MINIMAL_TX));
  }

  @After
  public void clearFormBody() {
    JsonFormat.clearFormBody();
  }

  private static MockHttpServletRequest formRequest(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setContentType("application/x-www-form-urlencoded");
    request.setContent(body.getBytes(UTF_8));
    request.setCharacterEncoding(UTF_8.name());
    return request;
  }

  @Test
  public void freezeFormFieldsReachContract() throws Exception {
    MockHttpServletResponse response = newResponse();
    freezeServlet.doPost(formRequest("owner_address=" + owner
        + "&frozen_balance=1000000&resource=ENERGY"), response);

    ArgumentCaptor<FreezeBalanceV2Contract> captor =
        ArgumentCaptor.forClass(FreezeBalanceV2Contract.class);
    verify(wallet).createTransactionCapsule(captor.capture(),
        eq(ContractType.FreezeBalanceV2Contract));
    assertEquals(1000000L, captor.getValue().getFrozenBalance());
    assertEquals(ResourceCode.ENERGY, captor.getValue().getResource());
    assertTransactionResponse(response);
  }

  @Test
  public void delegateFormFieldsReachContract() throws Exception {
    MockHttpServletResponse response = newResponse();
    delegateServlet.doPost(formRequest("owner_address=" + owner + "&receiver_address="
        + receiver + "&balance=02000000&resource=ENERGY&lock=true&lock_period=86400"), response);

    ArgumentCaptor<DelegateResourceContract> captor =
        ArgumentCaptor.forClass(DelegateResourceContract.class);
    verify(wallet).createTransactionCapsule(captor.capture(),
        eq(ContractType.DelegateResourceContract));
    DelegateResourceContract contract = captor.getValue();
    assertTrue(addressEquals(contract.getReceiverAddress(), receiver));
    assertEquals(2000000L, contract.getBalance());
    assertTrue(contract.getLock());
    assertEquals(86400L, contract.getLockPeriod());
    assertTransactionResponse(response);
  }

  private MockHttpServletResponse deployWithAbi(String abi) throws Exception {
    MockHttpServletResponse response = newResponse();
    deployServlet.doPost(formRequest("owner_address=" + owner + "&name=C"
        + "&bytecode=608060405234801561001057600080fd5b50"
        + "&abi=" + URLEncoder.encode(abi, UTF_8.name())), response);
    return response;
  }

  @Test
  public void deployFormAbiAcceptsJsonBoolean() throws Exception {
    MockHttpServletResponse response = deployWithAbi(
        "[{\"constant\":true,\"inputs\":[],\"name\":\"f\",\"outputs\":[],\"type\":\"function\"}]");

    ArgumentCaptor<CreateSmartContract> captor = ArgumentCaptor.forClass(CreateSmartContract.class);
    verify(wallet).createTransactionCapsule(captor.capture(),
        eq(ContractType.CreateSmartContract));
    Entry entry = captor.getValue().getNewContract().getAbi().getEntrys(0);
    assertTrue(entry.getConstant());
    assertTransactionResponse(response);
  }

  @Test
  public void deployFormAbiStaysStrict() throws Exception {
    MockHttpServletResponse response = deployWithAbi(
        "[{\"constant\":\"true\",\"inputs\":[],\"name\":\"f\",\"outputs\":[],"
            + "\"type\":\"function\"}]");

    verify(wallet, never()).createTransactionCapsule(any(), any());
    String content = response.getContentAsString();
    assertTrue(content, content.contains("Error"));
    assertFalse(content, content.contains("txID"));
  }
}
