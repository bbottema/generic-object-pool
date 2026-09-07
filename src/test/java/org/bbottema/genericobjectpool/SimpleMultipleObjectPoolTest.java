package org.bbottema.genericobjectpool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bbottema.genericobjectpool.ObjectPoolTestHelper.createAllocator;
import static org.bbottema.genericobjectpool.PoolTestResources.await;
import static org.bbottema.genericobjectpool.PoolTestResources.result;

public class SimpleMultipleObjectPoolTest {

	private static final int MAX_ITEMS_PER_KEY = 8;
	private final PoolTestResources resources = new PoolTestResources();
	private GenericObjectPool<String> pool1;
	private GenericObjectPool<String> pool2;
	private GenericObjectPool<String> pool3;

	@BeforeEach
	public void setup() {
		pool1 = resources.pool(PoolConfig.<String>builder().maxPoolsize(MAX_ITEMS_PER_KEY).build(), createAllocator("a"));
		pool2 = resources.pool(PoolConfig.<String>builder().maxPoolsize(MAX_ITEMS_PER_KEY).build(), createAllocator("b"));
		pool3 = resources.pool(PoolConfig.<String>builder().maxPoolsize(MAX_ITEMS_PER_KEY).build(), createAllocator("c"));
	}

	@AfterEach
	public void cleanUp() throws Exception {
		resources.close();
	}

	@Test
	public void maxAllocatedTestAgainstSameKey() throws Exception {
		assertOverflowTimesOut(pool2);
	}

	@Test
	public void allocationSizeShouldNotGrowAfterInvalidate() throws Exception {
		final List<PoolableObject<String>> held = fill(pool2);
		final List<Future<PoolableObject<String>>> waiting = new ArrayList<>();
		for (int i = 0; i < MAX_ITEMS_PER_KEY; i++) {
			waiting.add(resources.workers.submit(() -> resources.remember(pool2.claim(3, TimeUnit.SECONDS))));
		}
		await("replacement callers are blocked", () -> pool2.getPoolMetrics().getCurrentlyWaitingCount() == MAX_ITEMS_PER_KEY);
		for (final PoolableObject<String> lease : held) {
			lease.invalidate();
		}
		for (final Future<PoolableObject<String>> worker : waiting) {
			final PoolableObject<String> replacement = result(worker);
			assertThat(replacement).isNotNull().isNotIn(held);
		}
		final PoolMetrics metrics = pool2.getPoolMetrics();
		assertThat(metrics.getCurrentlyAllocated()).isEqualTo(MAX_ITEMS_PER_KEY);
		assertThat(metrics.getCurrentlyClaimed()).isEqualTo(MAX_ITEMS_PER_KEY);
		assertThat(metrics.getTotalAllocated()).isEqualTo(2 * MAX_ITEMS_PER_KEY);
		assertThat(metrics.getTotalClaimed()).isEqualTo(2 * MAX_ITEMS_PER_KEY);
		assertThat(metrics.getCurrentlyWaitingCount()).isZero();
	}

	@Test
	public void allocationsAgainstMultipleKeysFailIfTimeoutOccurs() throws Exception {
		assertOverflowTimesOut(pool2);
		assertOverflowTimesOut(pool3);
	}

	private void assertOverflowTimesOut(final GenericObjectPool<String> pool) throws Exception {
		final List<PoolableObject<String>> held = fill(pool);
		final List<Future<PoolableObject<String>>> waiting = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			waiting.add(resources.workers.submit(() -> resources.remember(pool.claim(200, TimeUnit.MILLISECONDS))));
		}
		await("overflow callers are blocked", () -> pool.getPoolMetrics().getCurrentlyWaitingCount() == 4);
		for (final Future<PoolableObject<String>> worker : waiting) {
			assertThat(result(worker)).isNull();
		}
		final PoolMetrics metrics = pool.getPoolMetrics();
		assertThat(metrics.getCurrentlyClaimed()).isEqualTo(MAX_ITEMS_PER_KEY);
		assertThat(metrics.getTotalAllocated()).isEqualTo(MAX_ITEMS_PER_KEY);
		assertThat(metrics.getCurrentlyWaitingCount()).isZero();
		for (final PoolableObject<String> lease : held) {
			lease.release();
		}
	}

	@Test
	public void highConcurrencyVolumeTest() throws Exception {
		final Set<PoolableObject<String>> active = ConcurrentHashMap.newKeySet();
		final List<PoolableObject<String>> held = fill(pool2);
		final List<Future<?>> waiting = new ArrayList<>();
		for (int i = 0; i < 32; i++) {
			waiting.add(resources.workers.submit(() -> {
				final PoolableObject<String> lease = resources.remember(pool2.claim(3, TimeUnit.SECONDS));
				assertThat(lease).isNotNull();
				assertThat(active.add(lease)).as("a lease has exactly one owner").isTrue();
				try {
					assertThat(pool2.getPoolMetrics().getCurrentlyAllocated()).isLessThanOrEqualTo(MAX_ITEMS_PER_KEY);
					assertThat(pool2.getPoolMetrics().getCurrentlyClaimed()).isLessThanOrEqualTo(MAX_ITEMS_PER_KEY);
				} finally {
					active.remove(lease);
					lease.release();
				}
				return null;
			}));
		}
		await("all concurrent callers are blocked", () -> pool2.getPoolMetrics().getCurrentlyWaitingCount() == 32);
		for (final PoolableObject<String> lease : held) {
			lease.release();
		}
		for (final Future<?> worker : waiting) {
			result(worker);
		}
		assertThat(active).isEmpty();
		assertThat(pool2.getPoolMetrics().getTotalClaimed()).isEqualTo(MAX_ITEMS_PER_KEY + 32);
		assertThat(pool2.getPoolMetrics().getCurrentlyClaimed()).isZero();
	}

	@Test
	public void workerAssertionFailuresReachTheTestThread() {
		final Future<?> worker = resources.workers.submit(() -> {
			throw new AssertionError("scripted worker failure");
		});
		assertThatThrownBy(() -> result(worker)).hasCauseInstanceOf(AssertionError.class);
	}

	@Test
	public void testPoolsDontAffectEachother() throws Exception {
		resources.remember(pool2.claim()).invalidate();
		resources.remember(pool3.claim()).release();
		final PoolMetrics metrics = pool1.getPoolMetrics();
		assertThat(metrics.getCurrentlyClaimed()).isZero();
		assertThat(metrics.getCurrentlyWaitingCount()).isZero();
		assertThat(metrics.getTotalAllocated()).isZero();
		assertThat(metrics.getTotalClaimed()).isZero();
	}

	@Test
	public void testShutdown() throws Exception {
		result(pool1.shutdown());
		assertThatThrownBy(pool1::claim).isInstanceOf(IllegalStateException.class);
		assertThat(resources.remember(pool2.claim())).isNotNull();
		assertThat(resources.remember(pool3.claim())).isNotNull();
	}

	private List<PoolableObject<String>> fill(final GenericObjectPool<String> pool) throws InterruptedException {
		final List<PoolableObject<String>> held = new ArrayList<>();
		for (int i = 0; i < MAX_ITEMS_PER_KEY; i++) {
			held.add(resources.remember(pool.claim(2, TimeUnit.SECONDS)));
			assertThat(held.get(i)).isNotNull();
		}
		return held;
	}
}
