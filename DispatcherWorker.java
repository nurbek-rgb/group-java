package kz.taxi.service;

import java.util.function.Consumer;
import kz.taxi.model.OrderStatus;

public final class DispatcherWorker implements Runnable {
    private final DispatchService service;
    private final OrderQueue queue;
    private final Consumer<RuntimeException> onFailure;
    public DispatcherWorker(DispatchService service, OrderQueue queue, Consumer<RuntimeException> onFailure) {
        this.service = service; this.queue = queue; this.onFailure = onFailure;
    }
    @Override public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                service.expireOffers();
                Long id = queue.poll();
                if (id == null) {
                    service.orders().stream().filter(o -> o.status() == OrderStatus.WAITING).forEach(o -> queue.offer(o.id()));
                } else if (service.tryAssign(id).isEmpty() && service.order(id).status() == OrderStatus.WAITING) {
                    Thread.sleep(50); // Bound retries while no eligible drivers are available.
                    queue.offer(id);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) { onFailure.accept(e); }
    }
}
