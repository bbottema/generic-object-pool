package org.bbottema.genericobjectpool;

import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.bbottema.genericobjectpool.ObjectPoolTestHelper.createAllocator;

public class SimpleSingleObjectPoolTest {

	private SimpleObjectPool<String> pool1, pool2;
	
	@Before
	public void setup() {
		pool1 = new SimpleObjectPool<>(PoolConfig.<String>builder().maxPoolsize(1).build(), createAllocator("a"));
		pool2 = new SimpleObjectPool<>(PoolConfig.<String>builder().maxPoolsize(1).build(), createAllocator("b"));
	}
	
	@Test
	public void waitForObjectWithTimeoutTest() throws Exception {
		singleClaimAndRelease();

		PoolableObject<String> obj = pool2.claim();
		if (obj == null) {
			throw new TimeoutException();
		}
		try {
			ExecutorService es = Executors.newSingleThreadExecutor();
			Future<?> f = es.submit(new Runnable() {
				public void run() {
					try {
						PoolableObject<String> obj = pool2.claim(500, TimeUnit.MILLISECONDS);
						if (obj == null) {
							throw new TimeoutException();
						}
						obj.release();
						fail("Object was obtained");
					} catch (Exception e) {
						if (!(e instanceof TimeoutException)) {
							fail(e.getMessage(), e);
						}
					}
				}
				
			});
			
			obj.release();
			assertThat(pool2.claim()).isNotNull();
			
			// block until thread is done
			f.get();
		} finally {
			obj.release();
		}
	}
	
	@Test
	public void manyThreadsBlockingUntilObtained() throws Exception {
		waitForObjectWithTimeoutTest();
		
		Runnable r = new Runnable() {
			public void run() {
				PoolableObject<String> obj = null;
				try {
					obj = pool2.claim();
					assertThat(obj).isNotNull();
					Thread.sleep(20);
					assertThat(pool2.claim()).isNotNull();
				} catch (Exception e) {
					fail("Failed to obtain Object: " + Thread.currentThread().getName(), e);
				} finally {
					if (obj != null)
						obj.release();
				}
			}
		};
		ExecutorService es = Executors.newFixedThreadPool(10);
		for (int i = 0; i < 10; i++)
			es.submit(r);
		es.shutdown();
		es.awaitTermination(2, TimeUnit.SECONDS);
		verifyMetrics();
	}
	
	@Test
	public void singleClaimAndRelease() throws Exception {
		PoolableObject<String> obj = pool2.claim();
		assertThat(obj).isNotNull();
		pool2.releasePoolableObject(obj);
		verifyMetrics();
	}
	
	private void verifyMetrics() {
		PoolMetrics metrics = pool1.getPoolMetrics();
		assertThat(metrics).isNotNull();
		assertThat(metrics.getClaimedCount()).isZero();
		assertThat(metrics.getWaitingCount()).isZero();
	}
	
	@Test
	public void testMetrics() throws Exception {
		manyThreadsBlockingUntilObtained();

		PoolMetrics metrics = pool1.getPoolMetrics();
		assertThat(metrics).isNotNull();
		assertThat(metrics.getClaimedCount()).isZero();
		assertThat(metrics.getWaitingCount()).isZero();
	}
	
	/**
	 * Tests shutting down the pool and not allowing any more allocations
	 */
	@Test(expected = IllegalStateException.class)
	public void testShutdown() throws Exception {
		testMetrics();
		
		pool1.shutdown();
		pool2.shutdown();

		try {
			pool2.claim();
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			fail(e.getMessage(), e);
		}
	}
	
	/**
	 * Tests the Allocator Lifecycle to insure the pool is calling allocator during each phase within the objects lifecycle
	 */
	@Test
	public void testObjectLifecycle() throws Exception {
		TestLifecycleAllocator allocator = new TestLifecycleAllocator();
		SimpleObjectPool<Boolean> pool = new SimpleObjectPool<>(PoolConfig.<Boolean>builder().maxPoolsize(1).build(), allocator);

		PoolableObject<Boolean> obj = pool.claim();
		if (obj == null) {
			throw new TimeoutException();
		}
		obj.release();
		obj = pool.claim();
		if (obj == null) {
			throw new TimeoutException();
		}
		obj.invalidate();
		
		assertThat(allocator.lifecycleCount).isEqualTo(3);
		TimeUnit.MILLISECONDS.sleep(100);
		assertThat(allocator.lifecycleCount).isEqualTo(4);
	}
	
	static class TestLifecycleAllocator extends Allocator<Boolean> {
		int lifecycleCount;
		
		@NotNull
		public Boolean allocate() {
			lifecycleCount++;
			return true;
		}
		
		public void allocateForReuse(@NotNull Boolean object) {
			lifecycleCount++;
		}
		
		public void deallocateForReuse(@NotNull Boolean object) {
			lifecycleCount++;
		}
		
		public void deallocate(@NotNull Boolean object) {
			lifecycleCount++;
		}
	}
}