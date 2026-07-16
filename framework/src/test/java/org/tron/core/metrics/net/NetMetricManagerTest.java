package org.tron.core.metrics.net;

import java.util.ArrayList;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.metrics.MetricsKey;
import org.tron.core.metrics.MetricsUtil;
import org.tron.core.net.TronNetDelegate;
import org.tron.protos.Protocol;

public class NetMetricManagerTest {

  // Regression test for getNetProtoInfo(): each of the four traffic meters
  // (tcpIn/tcpOut/udpIn/udpOut) must be mapped to its own proto field. A former
  // copy-paste bug wrote udpInTraffic into the tcpOutTraffic field, overwriting
  // tcpOut and leaving udpIn unset. The four meters are seeded with mutually
  // distinct counts so that any such cross-wiring is detected.
  @Test
  public void testGetNetProtoInfoTrafficMapping() {
    NetMetricManager netMetricManager = new NetMetricManager();
    TronNetDelegate tronNetDelegate = Mockito.mock(TronNetDelegate.class);
    Mockito.doReturn(new ArrayList<>()).when(tronNetDelegate).getActivePeer();
    ReflectUtils.setFieldValue(netMetricManager, "tronNetDelegate", tronNetDelegate);

    // The Codahale registry is static and accumulates across the whole test JVM,
    // so capture baselines first, then mark distinct deltas. The four final
    // counts are therefore guaranteed to differ from one another, which is what
    // makes a field swap (or an unset field) observable.
    long baseTcpIn = MetricsUtil.getMeter(MetricsKey.NET_TCP_IN_TRAFFIC).getCount();
    long baseTcpOut = MetricsUtil.getMeter(MetricsKey.NET_TCP_OUT_TRAFFIC).getCount();
    long baseUdpIn = MetricsUtil.getMeter(MetricsKey.NET_UDP_IN_TRAFFIC).getCount();
    long baseUdpOut = MetricsUtil.getMeter(MetricsKey.NET_UDP_OUT_TRAFFIC).getCount();
    long base = Math.max(Math.max(baseTcpIn, baseTcpOut), Math.max(baseUdpIn, baseUdpOut));

    long tcpInCount = base + 11L;
    long tcpOutCount = base + 22L;
    long udpInCount = base + 33L;
    long udpOutCount = base + 44L;
    MetricsUtil.getMeter(MetricsKey.NET_TCP_IN_TRAFFIC).mark(tcpInCount - baseTcpIn);
    MetricsUtil.getMeter(MetricsKey.NET_TCP_OUT_TRAFFIC).mark(tcpOutCount - baseTcpOut);
    MetricsUtil.getMeter(MetricsKey.NET_UDP_IN_TRAFFIC).mark(udpInCount - baseUdpIn);
    MetricsUtil.getMeter(MetricsKey.NET_UDP_OUT_TRAFFIC).mark(udpOutCount - baseUdpOut);

    Protocol.MetricsInfo.NetInfo netInfo = netMetricManager.getNetProtoInfo();

    // Each traffic field must reflect its own meter, one-to-one.
    Assert.assertEquals(tcpInCount, netInfo.getTcpInTraffic().getCount());
    Assert.assertEquals(tcpOutCount, netInfo.getTcpOutTraffic().getCount());
    Assert.assertEquals(udpInCount, netInfo.getUdpInTraffic().getCount());
    Assert.assertEquals(udpOutCount, netInfo.getUdpOutTraffic().getCount());
  }
}
