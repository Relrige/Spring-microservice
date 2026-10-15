package ua.edu.ukma.springers.voltstore.delivery.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs tasks on separate threads that all start at the same moment, to provoke races in integration tests. */
public final class Concurrently {

    private Concurrently() {
    }

    /**
     * Runs {@code copies} copies of the task and returns each result or thrown exception.
     */
    public static List<Object> run(int copies, Callable<?> task) throws Exception {
        Callable<?>[] tasks = new Callable<?>[copies];
        Arrays.fill(tasks, task);
        return run(tasks);
    }

    /**
     * Starts all tasks at the same moment and returns each task's result or thrown exception, in task order.
     */
    public static List<Object> run(Callable<?>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch ready = new CountDownLatch(tasks.length);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
