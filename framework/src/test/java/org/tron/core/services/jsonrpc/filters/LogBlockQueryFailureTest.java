package org.tron.core.services.jsonrpc.filters;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.BitSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.tron.core.services.jsonrpc.TronJsonRpc.FilterRequest;
import org.tron.core.store.SectionBloomStore;

public class LogBlockQueryFailureTest {

  private static final long CURRENT_MAX_BLOCK_NUM = 100L;
  private static final String SENSITIVE_MARKER = "worker-sensitive-marker";

  @Test
  public void testExecutionFailureLogsCauseAndRethrowsWrapper() throws Exception {
    NullPointerException cause = new NullPointerException(SENSITIVE_MARKER);
    ExecutionException failure = new ExecutionException(cause);
    Future<BitSet> future = futureThrowing(failure);
    LogBlockQuery query = newQuery(future);
    ListAppender<ILoggingEvent> appender = attachApiAppender();

    try {
      ExecutionException thrown = Assert.assertThrows(ExecutionException.class,
          query::getPossibleBlock);

      Assert.assertSame(failure, thrown);
      Assert.assertTrue(hasThrowable(appender, NullPointerException.class, SENSITIVE_MARKER));
    } finally {
      detachApiAppender(appender);
    }
  }

  @Test
  public void testInterruptionRestoresFlagAndRethrows() throws Exception {
    Thread.interrupted();
    InterruptedException failure = new InterruptedException();
    Future<BitSet> future = futureThrowing(failure);
    LogBlockQuery query = newQuery(future);
    ListAppender<ILoggingEvent> appender = attachApiAppender();

    try {
      InterruptedException thrown = Assert.assertThrows(InterruptedException.class,
          query::getPossibleBlock);

      Assert.assertSame(failure, thrown);
      Assert.assertTrue(Thread.currentThread().isInterrupted());
      Assert.assertTrue(hasThrowable(appender, InterruptedException.class, null));
    } finally {
      Thread.interrupted();
      detachApiAppender(appender);
    }
  }

  private static LogBlockQuery newQuery(Future<BitSet> future) throws Exception {
    ExecutorService executor = mock(ExecutorService.class);
    when(executor.submit(ArgumentMatchers.<Callable<BitSet>>any())).thenReturn(future);
    SectionBloomStore store = mock(SectionBloomStore.class);
    LogFilterWrapper wrapper = new LogFilterWrapper(
        new FilterRequest("0x0", "0x1",
            "0x1111111111111111111111111111111111111111", null, null),
        CURRENT_MAX_BLOCK_NUM, null, false);
    return new LogBlockQuery(wrapper, store, CURRENT_MAX_BLOCK_NUM, executor);
  }

  @SuppressWarnings("unchecked")
  private static Future<BitSet> futureThrowing(Exception failure) throws Exception {
    Future<BitSet> future = mock(Future.class);
    when(future.get()).thenThrow(failure);
    return future;
  }

  private static ListAppender<ILoggingEvent> attachApiAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger("API")).addAppender(appender);
    return appender;
  }

  private static void detachApiAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger("API")).detachAppender(appender);
    appender.stop();
  }

  private static boolean hasThrowable(ListAppender<ILoggingEvent> appender,
      Class<? extends Throwable> type, String message) {
    for (ILoggingEvent event : appender.list) {
      IThrowableProxy throwable = event.getThrowableProxy();
      if (throwable != null && type.getName().equals(throwable.getClassName())
          && (message == null || message.equals(throwable.getMessage()))) {
        return true;
      }
    }
    return false;
  }
}
