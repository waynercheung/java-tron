package org.tron.core.jsonrpc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcBasicServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.entity.NodeInfo;
import org.tron.common.entity.PeerInfo;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.db.Manager;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerManager;
import org.tron.core.net.peer.TronState;
import org.tron.core.services.NodeInfoService;
import org.tron.core.services.jsonrpc.TronJsonRpc;
import org.tron.core.services.jsonrpc.TronJsonRpcImpl;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.BlockHeader;

public class EthSyncingStatusTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long HEAD_NUM = 1000L;
  private static final long BEGIN_SYNC_NUM = 900L;

  private List<PeerConnection> peers;
  private NodeInfoService nodeInfoService;
  private Wallet wallet;
  private TronJsonRpcImpl tronJsonRpc;
  private JsonRpcBasicServer server;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    Field field = PeerManager.class.getDeclaredField("peers");
    field.setAccessible(true);
    peers = (List<PeerConnection>) field.get(null);
    peers.clear();

    nodeInfoService = mock(NodeInfoService.class);
    when(nodeInfoService.getNodeInfo()).thenReturn(new NodeInfo().setPeerList(new ArrayList<>()));
    wallet = mock(Wallet.class);
    tronJsonRpc = new TronJsonRpcImpl(nodeInfoService, wallet, mock(Manager.class));
    server = new JsonRpcBasicServer(MAPPER, tronJsonRpc, TronJsonRpc.class);
    setHead(System.currentTimeMillis() - 60_000L);
  }

  @After
  public void tearDown() {
    peers.clear();
    tronJsonRpc.close();
  }

  @Test
  public void testNoPeersReturnsFalse() throws Exception {
    assertFalseResult(ethSyncing());
  }

  @Test
  public void testHandshakingPeerReturnsFalse() throws Exception {
    addPeer(TronState.INIT, false);
    assertFalseResult(ethSyncing());
  }

  @Test
  public void testSyncCompletedPeerReturnsFalse() throws Exception {
    addPeer(TronState.SYNC_COMPLETED, false);
    addPeer(TronState.SYNCING, true);
    assertFalseResult(ethSyncing());
  }

  @Test
  public void testSyncingPeerReturnsProgress() throws Exception {
    addPeer(TronState.SYNC_COMPLETED, false);
    addPeer(TronState.SYNCING, false);

    JsonNode result = ethSyncing();
    Assert.assertTrue(result.isObject());
    Assert.assertEquals(ByteArray.toJsonHex(BEGIN_SYNC_NUM),
        result.get("startingBlock").asText());
    Assert.assertEquals(ByteArray.toJsonHex(HEAD_NUM), result.get("currentBlock").asText());
    Assert.assertEquals(ByteArray.toJsonHex(HEAD_NUM + 20),
        result.get("highestBlock").asText());
  }

  @Test
  public void testSyncingPeerReturnsProgressWhenHeadIsRecent() throws Exception {
    setHead(System.currentTimeMillis());
    addPeer(TronState.SYNCING, false);

    JsonNode result = ethSyncing();
    Assert.assertTrue(result.isObject());
    Assert.assertEquals(result.get("currentBlock").asText(),
        result.get("highestBlock").asText());
  }

  private void setHead(long timestamp) {
    Block block = Block.newBuilder().setBlockHeader(BlockHeader.newBuilder()
        .setRawData(BlockHeader.raw.newBuilder().setNumber(HEAD_NUM).setTimestamp(timestamp)))
        .build();
    when(wallet.getNowBlock()).thenReturn(block);
  }

  private void addPeer(TronState state, boolean disconnected) {
    PeerConnection peer = mock(PeerConnection.class);
    when(peer.getTronState()).thenReturn(state);
    when(peer.isNeedSyncFromPeer()).thenReturn(state != TronState.SYNC_COMPLETED);
    when(peer.isDisconnect()).thenReturn(disconnected);
    peers.add(peer);

    List<PeerInfo> peerInfos = new ArrayList<>();
    for (int i = 0; i < peers.size(); i++) {
      peerInfos.add(new PeerInfo());
    }
    NodeInfo nodeInfo = new NodeInfo().setPeerList(peerInfos).setBeginSyncNum(BEGIN_SYNC_NUM);
    when(nodeInfoService.getNodeInfo()).thenReturn(nodeInfo);
  }

  private JsonNode ethSyncing() throws Exception {
    String request = "{\"jsonrpc\":\"2.0\",\"method\":\"eth_syncing\",\"params\":[],\"id\":1}";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    server.handleRequest(
        new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), output);
    JsonNode response = MAPPER.readTree(output.toByteArray());
    Assert.assertNull(response.get("error"));
    return response.get("result");
  }

  private static void assertFalseResult(JsonNode result) {
    Assert.assertTrue(result.isBoolean());
    Assert.assertFalse(result.asBoolean());
  }
}
