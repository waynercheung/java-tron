package org.tron.core.admission;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/** At most one rejection log line per interval, with the number of rejections since the last. */
public final class AdmissionRejectLog {

  public static final long DEFAULT_INTERVAL_MS = 10_000;

  private final long intervalMillis;
  private final AtomicLong pending = new AtomicLong();
  private final AtomicLong lastLogMillis;

  public AdmissionRejectLog(long intervalMillis) {
    this.intervalMillis = intervalMillis;
    this.lastLogMillis = new AtomicLong(Long.MIN_VALUE / 2);
  }

  // Returns the count to log now, or 0. nowMillis is from a monotonic clock.
  long record(long nowMillis) {
    pending.incrementAndGet();
    long last = lastLogMillis.get();
    if (nowMillis - last >= intervalMillis && lastLogMillis.compareAndSet(last, nowMillis)) {
      return pending.getAndSet(0);
    }
    return 0;
  }

  /** Logs at DEBUG with the count appended as the last argument; nothing is counted otherwise. */
  public void debug(Logger logger, String format, Object... args) {
    if (!logger.isDebugEnabled()) {
      return;
    }
    long count = record(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    if (count > 0) {
      Object[] all = Arrays.copyOf(args, args.length + 1);
      all[args.length] = count;
      logger.debug(format, all);
    }
  }
}
