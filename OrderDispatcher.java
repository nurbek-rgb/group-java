package kz.taxi.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kz.taxi.model.OrderStatus;

public final class OrderDispatcher implements AutoCloseable {
    private final ExecutorService workers;
    private final OrderQueue queue = new OrderQueue();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    public OrderDispatcher(DispatchService service, int workerCount) {
        if (workerCount < 1 || workerCount > 100) throw new IllegalArgumentException("Ағын саны 1–100 болуы керек");
        workers = Executors.newFixedThreadPool(workerCount);
        service.orders().stream().filter(o -> o.status() == OrderStatus.WAITING).forEach(o -> queue.offer(o.id()));
        for (int i = 0; i < workerCount; i++) workers.submit(new DispatcherWorker(service, queue, error -> {
            failure.compareAndSet(null, error);
            workers.shutdownNow();
        }));
    }
    public void enqueue(long orderId) { checkHealth(); queue.offer(orderId); }
    public void checkHealth() {
        if (failure.get() != null) throw new IllegalStateException("Диспетчер тоқтады", failure.get());
    }
    @Override public void close() {
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Диспетчер уақытында тоқтамады");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Тоқтату үзілді", e); }
        checkHealth();
    }
}
