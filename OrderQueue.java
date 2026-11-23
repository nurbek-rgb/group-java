package kz.taxi.service;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class OrderQueue {
    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    public void offer(long orderId) { if (queued.add(orderId)) queue.offer(orderId); }
    public Long poll() throws InterruptedException {
        Long id = queue.poll(100, TimeUnit.MILLISECONDS);
        if (id != null) queued.remove(id);
        return id;
    }
}
