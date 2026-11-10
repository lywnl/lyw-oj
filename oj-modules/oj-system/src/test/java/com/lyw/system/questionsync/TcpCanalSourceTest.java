package com.lyw.system.questionsync;

import com.alibaba.otter.canal.client.impl.SimpleCanalConnector;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.lyw.system.questionsync.SyncFixtures.properties;

class TcpCanalSourceTest {
    @Test void nonReturningConnectionTimesOutAndCannotSubscribeAfterCancellation() throws Exception {
        SyncProperties properties = properties(); properties.setTimeoutMillis(100);
        SimpleCanalConnector connector = mock(SimpleCanalConnector.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), ended = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
            ended.countDown(); return null;
        }).when(connector).connect();
        TcpCanalSource source = new TcpCanalSource(properties, () -> connector);
        try {
            long start = System.nanoTime();
            assertThrows(IllegalStateException.class, source::open);
            assertTrue(System.nanoTime() - start < 2_000_000_000L);
            assertTrue(entered.await(1, TimeUnit.SECONDS)); verify(connector, atLeastOnce()).disconnect();
            release.countDown(); assertTrue(ended.await(1, TimeUnit.SECONDS));
            verify(connector, never()).subscribe(anyString()); verify(connector, never()).rollback();
        } finally { release.countDown(); source.close(); }
    }
    @Test void subscriptionUsesCanalCompatibleSingleTableRegex() {
        SyncProperties properties = properties(); properties.setDatabase("lywoj_it");
        SimpleCanalConnector connector = mock(SimpleCanalConnector.class);
        TcpCanalSource source = new TcpCanalSource(properties, () -> connector);
        source.open();
        verify(connector).subscribe("lywoj_it\\.tb_question"); verify(connector).rollback(); source.close();
    }
    @Test void lateFactoryThatIgnoresInterruptCannotCreateANewConnectionAfterTimeout() throws Exception {
        SyncProperties properties = properties(); properties.setTimeoutMillis(100);
        SimpleCanalConnector connector = mock(SimpleCanalConnector.class);
        CountDownLatch release = new CountDownLatch(1), discarded = new CountDownLatch(1);
        doAnswer(call -> { discarded.countDown(); return null; }).when(connector).disconnect();
        TcpCanalSource source = new TcpCanalSource(properties, () -> {
            // 模拟不能被中断取消、且清除了中断标记的 DNS/地址解析实现。
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
            return connector;
        });
        try {
            assertThrows(IllegalStateException.class, source::open);
            release.countDown(); assertTrue(discarded.await(1, TimeUnit.SECONDS));
            verify(connector, never()).connect(); verify(connector, never()).subscribe(anyString()); verify(connector, never()).rollback();
        } finally { release.countDown(); source.close(); }
    }
}
