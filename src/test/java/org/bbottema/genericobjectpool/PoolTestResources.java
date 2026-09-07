package org.bbottema.genericobjectpool;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps concurrency tests bounded and cleans up leases, workers and pool threads even after an assertion fails. */
public final class PoolTestResources implements AutoCloseable {

	private final List<ExecutorService> workerPools = new ArrayList<>();
	public final ExecutorService workers = register(Executors.newCachedThreadPool());
	private final List<GenericObjectPool<?>> pools = new ArrayList<>();
	private final Queue<PoolableObject<?>> leases = new ConcurrentLinkedQueue<>();

	public ExecutorService singleWorker() {
		return register(Executors.newSingleThreadExecutor());
	}

	private ExecutorService register(final ExecutorService executor) {
		workerPools.add(executor);
		return executor;
	}

	public <T> GenericObjectPool<T> pool(final PoolConfig<T> config, final Allocator<T> allocator) {
		final GenericObjectPool<T> pool = new GenericObjectPool<>(config, allocator);
		pools.add(pool);
		return pool;
	}

	public <T> PoolableObject<T> remember(final PoolableObject<T> lease) {
		if (lease != null) {
			leases.add(lease);
		}
		return lease;
	}

	public static void await(final String description, final BooleanSupplier condition) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(2);
		}
		assertThat(condition.getAsBoolean()).as(description).isTrue();
	}

	public static <T> T result(final Future<T> future) throws Exception {
		return future.get(5, TimeUnit.SECONDS);
	}

	@Override
	public void close() throws Exception {
		final List<Future<Void>> shutdowns = new ArrayList<>();
		for (final GenericObjectPool<?> pool : pools) {
			shutdowns.add(pool.shutdown());
		}
		for (final ExecutorService executor : workerPools) {
			executor.shutdownNow();
		}
		for (final ExecutorService executor : workerPools) {
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("test workers stopped").isTrue();
		}
		for (final PoolableObject<?> lease : leases) {
			lease.invalidate();
		}
		for (final Future<Void> shutdown : shutdowns) {
			result(shutdown);
		}
	}
}
