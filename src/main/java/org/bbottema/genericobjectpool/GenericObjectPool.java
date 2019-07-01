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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bbottema.genericobjectpool.util.SleepUtil;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.bbottema.genericobjectpool.util.ForeverTimeout.WAIT_FOREVER;

@Slf4j
public class GenericObjectPool<T> {
	
	@NotNull private final Lock lock = new ReentrantLock();
	@NotNull private final LinkedList<PoolableObject<T>> available = new LinkedList<>();
	@NotNull private final LinkedList<PoolableObject<T>> waitingForDeallocation = new LinkedList<>();
	@NotNull private final LinkedList<Condition> objectAvailableConditions = new LinkedList<>();
	
	@NotNull @Getter private final PoolConfig<T> poolConfig;
	@NotNull @Getter private final Allocator<T> allocator;
	
	@Nullable private volatile Future<?> shutdownSequence;
	
	@NotNull private final AtomicInteger currentlyClaimed = new AtomicInteger();
	@NotNull private final AtomicLong totalAllocated = new AtomicLong();
	@NotNull private final AtomicLong totalClaimed = new AtomicLong();
	
	public GenericObjectPool(final PoolConfig<T> poolConfig, @NotNull final Allocator<T> allocator) {
		this.poolConfig = poolConfig;
		this.allocator = allocator;
		poolConfig.getThreadFactory().newThread(new AutoAllocatorDeallocator()).start();
	}
	
	/**
	 * Delegates to {@link #claim(Timeout)} with unlimited timeout.
	 */
	@NotNull
	@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "False positive")
	public PoolableObject<T> claim() throws InterruptedException {
		return requireNonNull(claim(WAIT_FOREVER));
	}
	
	/**
	 * Delegates to {@link #claim(Timeout)}.
	 */
	@Nullable
	public PoolableObject<T> claim(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
		return claim(new Timeout(timeout, timeUnit));
	}
	
	/**
	 * Will claim available object, create a new one if there is room to grow the pool, or else wait until either become true.
	 *
	 * @throws IllegalStateException if you try a new claim while the pool is shut down
	 * @throws InterruptedException  if the pool was waiting and the pool shut down in the mean time
	 */
	@SuppressWarnings("WeakerAccess")
	@Nullable
	public PoolableObject<T> claim(final Timeout timeout) throws InterruptedException, IllegalStateException {
		lock.lock();
		try {
			return claimOrCreateOrWaitUntilAvailable(timeout);
		} finally {
			lock.unlock();
		}
	}

	void releasePoolableObject(final PoolableObject<T> claimedObject) {
		lock.lock();
		try {
			if (isShuttingDown()) {
				invalidatePoolableObject(claimedObject);
			} else if (claimedObject.getCurrentPoolStatus() == PoolableObject.PoolStatus.CLAIMED) {
				allocator.deallocateForReuse(claimedObject.getAllocatedObject());
				currentlyClaimed.decrementAndGet();
				available.addLast(claimedObject);
				claimedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.AVAILABLE);

				Condition waitingClaimer = objectAvailableConditions.poll();
				if (waitingClaimer != null) {
					waitingClaimer.signal();
				}
			}
		} finally {
			lock.unlock();
		}
	}

	void invalidatePoolableObject(final PoolableObject<T> claimedObject) {
		lock.lock();
		try {
			if (claimedObject.getCurrentPoolStatus() == PoolableObject.PoolStatus.CLAIMED) {
				currentlyClaimed.decrementAndGet();
			} else if (claimedObject.getCurrentPoolStatus() == PoolableObject.PoolStatus.AVAILABLE) {
				available.remove(claimedObject);
			}
			if (claimedObject.getCurrentPoolStatus().ordinal() < PoolableObject.PoolStatus.WAITING_FOR_DEALLOCATION.ordinal()) {
				waitingForDeallocation.add(claimedObject);
				claimedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.WAITING_FOR_DEALLOCATION);
			}
		} finally {
			lock.unlock();
		}
	}
	
	@Nullable
	private PoolableObject<T> claimOrCreateOrWaitUntilAvailable(final Timeout timeout) throws InterruptedException, IllegalStateException {
		PoolableObject<T> entry;
		/*
		 *	Try to claim an object or else wait for one to become available and then try again
		 *	in between one becoming available and trying to claim again, it might have been
		 *	snatched away by another thread.
		 */
		do {
			if (isShuttingDown()) {
				throw new IllegalStateException("Pool has been shutdown");
			}
			entry = claimOrCreateNewObjectIfSpaceLeft();
		} while (entry == null && waitForAvailableObjectOrTimeout(timeout));
		return entry;
	}
	
	@Nullable
	private PoolableObject<T> claimOrCreateNewObjectIfSpaceLeft() {
		PoolableObject<T> claimedObject = !available.isEmpty() ? available.removeFirst() : null;
		if (claimedObject != null) {
			allocator.allocateForReuse(claimedObject.getAllocatedObject());
			claimedObject.resetAllocationTimestamp();
			claimedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.CLAIMED);
			currentlyClaimed.incrementAndGet();
			totalClaimed.incrementAndGet();
		} else if (getCurrentlyAllocated() < poolConfig.getMaxPoolsize()) {
			claimedObject = new PoolableObject<>(this, allocator.allocate());
			claimedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.CLAIMED);
			currentlyClaimed.incrementAndGet();
			totalAllocated.incrementAndGet();
			totalClaimed.incrementAndGet();
		}
		return claimedObject;
	}
	
	/**
	 * Adds the current PoolWaitHelper into the waiting list.  The waitingClaimer will wait up until the specified deadline.  If the waitingClaimer is woken up before the specified deadline then true is returned
	 * otherwise false.  The waitingClaimer will always be removed from the wait list regardless of the outcome.
	 *
	 * @param timeout the max timeout to wait for
	 *
	 * @return true if object became available
	 * @throws InterruptedException the interrupted exception
	 */
	private boolean waitForAvailableObjectOrTimeout(final Timeout timeout) throws InterruptedException {
		final Condition objectAvailability = lock.newCondition();
		try {
			objectAvailableConditions.add(objectAvailability);
			final boolean await = objectAvailability.await(timeout.getDuration(), timeout.getTimeUnit());
			if (isShuttingDown()) {
				throw new InterruptedException("Pool is shutting down");
			}
			return await;
		} finally {
			objectAvailableConditions.remove(objectAvailability);
		}
	}

	/**
	 * Shuts down the current Pool stopping Allocations
	 */
	public synchronized Future<?> shutdown() {
		return isShuttingDown()
				? shutdownSequence
				: (shutdownSequence = newSingleThreadExecutor(poolConfig.getThreadFactory()).submit(new ShutdownSequence()));
	}
	
	private boolean isShuttingDown() {
		return shutdownSequence != null;
	}

	/**
	 * Gets the allocation size.
	 *
	 * @return the allocation size
	 */
	@SuppressWarnings("WeakerAccess")
	public int getCurrentlyAllocated() {
		return available.size() + currentlyClaimed.get();
	}

	/**
	 * @see PoolMetrics
	 */
	@NotNull
	public PoolMetrics getPoolMetrics() {
		lock.lock();
		try {
			return new PoolMetrics(
					currentlyClaimed.get(),
					objectAvailableConditions.size(),
					getCurrentlyAllocated(),
					poolConfig.getCorePoolsize(),
					poolConfig.getMaxPoolsize(),
					totalAllocated.get(),
					totalClaimed.get());
		} finally {
			lock.unlock();
		}
	}
	
	/**
	 * <ol>
	 *     <li>Automatically allocates objects until core pool size is met. Initially fills up the pool and when object are deallocated.</li>
	 *     <li>Automatically plan deallocation for expired objects</li>
	 *     <li>Automatically deallocates one object every loop</li>
	 * </ol>
	 */
	private class AutoAllocatorDeallocator implements Runnable {
		@Override
		@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH", justification = "False positive")
		public void run() {
			//noinspection ConstantConditions
			while (shutdownSequence == null || !shutdownSequence.isDone() || !waitingForDeallocation.isEmpty()) {
				allocatedCorePoolAndDeallocateOneOrPlanDeallocations();
			}
			log.debug("AutoAllocatorDeallocator finished");
		}
		
		private void allocatedCorePoolAndDeallocateOneOrPlanDeallocations() {
			boolean deallocatedAnObject = false;
			lock.lock();
			try {
				while (getCurrentlyAllocated() < poolConfig.getCorePoolsize() && !isShuttingDown()) {
					available.addLast(new PoolableObject<>(GenericObjectPool.this, allocator.allocate()));
					totalAllocated.incrementAndGet();
				}
				if (!waitingForDeallocation.isEmpty()) {
					deallocate(waitingForDeallocation.remove());
					deallocatedAnObject = true;
				}
			} finally {
				lock.unlock();
			}
			if (!deallocatedAnObject) {
				scheduleDeallocations(); // execute outside of lock, so very big pools won't hog CPU
			}
			SleepUtil.sleep(isShuttingDown() ? 0 : deallocatedAnObject ? 50 : 10);
		}

		private void deallocate(final PoolableObject<T> invalidatedObject) {
			allocator.deallocate(invalidatedObject.getAllocatedObject());
			invalidatedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.DEALLOCATED);
			invalidatedObject.dereferenceObject();
		}
		
		private void scheduleDeallocations() {
			final ExpirationPolicy<T> expirationPolicy = poolConfig.getExpirationPolicy();
			for (PoolableObject<T> expiredObject : gatherExpiredObjects(expirationPolicy)) {
				invalidateExpiredObject(expirationPolicy, expiredObject);
			}
		}
		
		private List<PoolableObject<T>> gatherExpiredObjects(ExpirationPolicy<T> expirationPolicy) {
			final List<PoolableObject<T>> expiredObjects = new ArrayList<>();
			lock.lock();
			try {
				for (final PoolableObject<T> poolableObject : available) {
					if (expirationPolicy.hasExpired(poolableObject)) {
						expiredObjects.add(poolableObject);
					}
				}
			} finally {
				lock.unlock();
			}
			return expiredObjects;
		}
		
		private void invalidateExpiredObject(ExpirationPolicy<T> expirationPolicy, PoolableObject<T> expiredObject) {
			lock.lock();
			try {
				if (expirationPolicy.hasExpired(expiredObject)) {
					expiredObject.invalidate();
				}
			} finally {
				lock.unlock();
			}
		}
	}
	
	private class ShutdownSequence implements Runnable {
		
		@Override
		public void run() {
			initiateShutdown();
			waitUntilShutDown();
			log.info("Simple Object Pool shutdown complete");
		}
		
		private void initiateShutdown() {
			lock.lock();
			try {
				while (!available.isEmpty()) {
					available.remove().invalidate();
				}
				for (Condition condition : objectAvailableConditions) {
					condition.signal();
				}
			} finally {
				lock.unlock();
			}
		}
		
		private void waitUntilShutDown() {
			while (currentlyClaimed.get() > 0 ||
					objectAvailableConditions.size() > 0 ||
					available.size() > 0 ||
					waitingForDeallocation.size() > 0) {
				SleepUtil.sleep(10);
			}
		}
	}
}