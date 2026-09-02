package org.tron.core.services.jsonrpc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.tron.core.Wallet;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;

public class TronJsonRpcImplChainIdentityTest {

  @Test
  public void testChainIdentityLogsFailureStateTransitions() throws Exception {
    Wallet wallet = mock(Wallet.class);
    BlockCapsule genesis = mock(BlockCapsule.class);
    when(genesis.getBlockId()).thenReturn(new BlockCapsule.BlockId(new byte[32], 0));
    when(wallet.getBlockCapsuleByNum(0))
        .thenReturn(null)
        .thenReturn(null)
        .thenReturn(genesis)
        .thenReturn(null);

    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    Logger apiLogger = (Logger) LoggerFactory.getLogger("API");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    apiLogger.addAppender(appender);

    try {
      Assert.assertThrows(JsonRpcInternalException.class, rpc::ethChainId);
      Assert.assertThrows(JsonRpcInternalException.class, rpc::ethChainId);
      Assert.assertEquals("0x00000000", rpc.ethChainId());
      Assert.assertThrows(JsonRpcInternalException.class, rpc::ethChainId);

      Assert.assertEquals(2,
          countEvents(appender, Level.WARN, "Chain identity lookup failed"));
      Assert.assertEquals(1,
          countEvents(appender, Level.INFO, "Chain identity lookup recovered"));

      ILoggingEvent firstFailure = findEvent(
          appender, Level.WARN, "Chain identity lookup failed");
      Assert.assertNotNull(firstFailure);
      Assert.assertNotNull(firstFailure.getThrowableProxy());
    } finally {
      apiLogger.detachAppender(appender);
      appender.stop();
      rpc.close();
    }
  }

  private static ILoggingEvent findEvent(ListAppender<ILoggingEvent> appender, Level level,
      String marker) {
    for (ILoggingEvent event : appender.list) {
      if (event.getLevel() == level && event.getFormattedMessage().contains(marker)) {
        return event;
      }
    }
    return null;
  }

  private static int countEvents(ListAppender<ILoggingEvent> appender, Level level,
      String marker) {
    int count = 0;
    for (ILoggingEvent event : appender.list) {
      if (event.getLevel() == level && event.getFormattedMessage().contains(marker)) {
        count++;
      }
    }
    return count;
  }
}
