/*
 * Copyright (C) 2019 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bbottema.genericobjectpool;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class SimpleMultipleObjectPoolTest {
	
	private static final int MAX_ITEMS_PER_KEY = 800;
	private static final int OVER_LIMIT_DELTA = 25;
	private static final int OVER_LIMIT_BLOCK_THREAD_COUNT = MAX_ITEMS_PER_KEY + OVER_LIMIT_DELTA;
	
	private GenericObjectPool<String> pool1, pool2, pool3;

	@Before
	public void setup() {
		pool1 = new GenericObjectPool<>(PoolConfig.<String>builder().maxPoolsize(MAX_ITEMS_PER_KEY).build(), ObjectPoolTestHelper.createAllocator("a"));
		pool2 = new GenericObjectPool<>(PoolConfig.<String>builder().maxPoolsize(MAX_ITEMS_PER_KEY).build(), ObjectPoolTestHelper.createAllocator("b"));
		pool3 = new GenericObjectPool<>(PoolConfig.<String>builder().maxPoolsize(MAX_ITEMS_PER_KEY).build(), ObjectPoolTestHelper.createAllocator("c"));
	}

	@Test
	public void maxAllocatedTestAgainstSameKey() throws Exception {
		ExecutionContext context = ExecutionContext.builder().build();
		executeAndWait(createThreadedExecution(pool2, OVER_LIMIT_BLOCK_THREAD_COUNT, context), context);
		assertThat(context.getTimeoutCount().get()).isEqualTo(OVER_LIMIT_DELTA);
	}
	
	@Test
	public void allocationSizeShouldNotGrowAfterInvalidate() throws Exception {
		maxAllocatedTestAgainstSameKey();
		
		ExecutionContext executionContext1 = ExecutionContext.builder().failIfUnableToClaim(true).sleepTime(20).build();
		executeAndWait(createThreadedExecution(pool2, MAX_ITEMS_PER_KEY, executionContext1), executionContext1);
		
		// Make sure we are fully Allocated
		PoolMetrics metrics = pool2.getPoolMetrics();
		assertThat(metrics.getCurrentlyAllocated()).isEqualTo(MAX_ITEMS_PER_KEY);
		assertThat(metrics.getTotalAllocated()).isEqualTo(MAX_ITEMS_PER_KEY);
		assertThat(metrics.getTotalClaimed()).isEqualTo(MAX_ITEMS_PER_KEY + MAX_ITEMS_PER_KEY);
		
		// Run again and invalidate vs release
		ExecutionContext executionContext2 = ExecutionContext.builder().failIfUnableToClaim(true).reusable(false).sleepTime(20).build();
		executeAndWait(createThreadedExecution(pool2, MAX_ITEMS_PER_KEY, executionContext2), executionContext2);
		metrics = pool1.getPoolMetrics();
		assertThat(metrics.getCurrentlyAllocated()).isZero();
		assertThat(metrics.getTotalAllocated()).isZero();
		assertThat(metrics.getTotalClaimed()).isZero();
	}
	
	@Test
	public void allocationsAgainstMultipleKeysFailIfTimeoutOccurs() throws Exception {
		maxAllocatedTestAgainstSameKey();

		ExecutionContext context = ExecutionContext.builder().failIfUnableToClaim(true).build();
		Set<Thread> threads = createThreadedExecution(pool2, MAX_ITEMS_PER_KEY, context);
		threads.addAll(createThreadedExecution(pool3, MAX_ITEMS_PER_KEY, context));
		executeAndWait(threads, context);
	}
	
	@Test
	public void highConcurrencyVolumeTest() throws Exception {
		maxAllocatedTestAgainstSameKey();

		final int claimExtraCount = 100;
		ExecutionContext context = ExecutionContext.builder().maxWaitTime(Long.MAX_VALUE).sleepTime(10).build();
		executeAndWait(createThreadedExecution(pool2, claimExtraCount, context), context);
		
		assertThat(context.getTimeoutCount().get()).isZero();

		PoolMetrics metrics = pool2.getPoolMetrics();
		assertThat(metrics.getCurrentlyAllocated()).isEqualTo(MAX_ITEMS_PER_KEY);
		assertThat(metrics.getTotalAllocated()).isEqualTo(MAX_ITEMS_PER_KEY);
		assertThat(metrics.getTotalClaimed()).isEqualTo(MAX_ITEMS_PER_KEY + claimExtraCount);
	}
	
	@Test
	public void testPoolsDontAffectEachother() throws Exception {
		allocationsAgainstMultipleKeysFailIfTimeoutOccurs();

		PoolMetrics metrics = pool1.getPoolMetrics();
		assertThat(metrics).isNotNull();
		assertThat(metrics.getCurrentlyClaimed()).isZero();
		assertThat(metrics.getCurrentlyWaitingCount()).isZero();
		assertThat(metrics.getTotalAllocated()).isEqualTo(0);
		assertThat(metrics.getTotalClaimed()).isEqualTo(0);
	}
	
	@Test
	public void testShutdown() throws Exception {
		testPoolsDontAffectEachother();
		
		pool1.shutdown();
		try {
			pool2.claim();
		} catch (IllegalStateException e) {
			// OK
		} catch (Exception e) {
			fail(e.getMessage(), e);
		}
	}
	
	private Set<Thread> createThreadedExecution(final GenericObjectPool<String> pool, int threadCount, final ExecutionContext context) {
		Set<Thread> threads = new HashSet<>(threadCount);
		for (int i = 0; i < threadCount; i++) {
			threads.add(new Thread(new Runnable() {
				public void run() {
					try {
						validateClaimFromPool(pool, context);
					} catch (Exception ex) {
						context.getAsyncException().set(ex);
					}
				}
			}));
		}
		return threads;
	}
	
	private void executeAndWait(Set<Thread> threads, ExecutionContext context) throws Exception {
		for (Thread t : threads)
			t.start();
		for (Thread t : threads) {
			try {
				t.join();
			} catch (InterruptedException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}
		if (context.getAsyncException().get() != null) {
			throw context.getAsyncException().get();
		}
	}
	
	private void validateClaimFromPool(GenericObjectPool<String> pool, ExecutionContext context) throws Exception {
		validateClaimFromPool(context, pool);
	}

	private void validateClaimFromPool(ExecutionContext context, GenericObjectPool<String> pool) throws Exception {
		PoolableObject<String> obj = null;
		try {
			obj = pool.claim(context.getMaxWaitTime(), TimeUnit.MILLISECONDS);
			if (obj == null) {
				throw new TimeoutException();
			}
			Thread.sleep(context.getSleepTime());
		} catch (TimeoutException e) {
			if (context.isFailIfUnableToClaim()) {
				fail("Unable to claim object : " + e.getMessage(), e);
			} else {
				final PoolMetrics metrics = pool.getPoolMetrics();
				assertThat(metrics.getCurrentlyClaimed()).isEqualTo(MAX_ITEMS_PER_KEY);
				assertThat(metrics.getTotalAllocated()).isEqualTo(MAX_ITEMS_PER_KEY);
				assertThat(metrics.getTotalClaimed()).isEqualTo(MAX_ITEMS_PER_KEY);
			}

			context.getTimeoutCount().incrementAndGet();
		} finally {
			if (obj != null) {
				if (context.isReusable())
					obj.release();
				else
					obj.invalidate();
			}
		}
	}
}