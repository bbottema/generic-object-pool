package org.bbottema.genericobjectpool;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bbottema.genericobjectpool.util.SleepUtil;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.bbottema.genericobjectpool.util.ForeverTimeout.WAIT_FOREVER;

@Slf4j
public class GenericObjectPool<T> {

	private static final int DEALLOCATION_WAIT_MS = 100;
	private static final int MATCHING_CLAIM_RECHECK_INTERVAL_MS = 10;
	
	@NotNull private final Lock claimLock = new ReentrantLock();
	@NotNull private final Lock deallocateLock = new ReentrantLock();
	@NotNull private final LinkedList<PoolableObject<T>> available = new LinkedList<>();
	@NotNull private final LinkedList<PoolableObject<T>> waitingForDeallocation = new LinkedList<>();
	@NotNull private final LinkedList<Condition> objectAvailableConditions = new LinkedList<>();
	@NotNull private final Condition objectWaitingForDeallocation = deallocateLock.newCondition();
	
	@NotNull @Getter private final PoolConfig<T> poolConfig;
	@NotNull @Getter private final Allocator<T> allocator;
	
	@Nullable private volatile Future<Void> shutdownSequence;
	
	@NotNull private final AtomicInteger currentlyClaimed = new AtomicInteger();
	@NotNull private final AtomicLong totalAllocated = new AtomicLong();
	@NotNull private final AtomicLong totalClaimed = new AtomicLong();
	
	public GenericObjectPool(final PoolConfig<T> poolConfig, @NotNull final Allocator<T> allocator) {
		this.poolConfig = poolConfig;
		this.allocator = allocator;
		poolConfig.getThreadFactory().newThread(new AutoAllocator()).start();
		poolConfig.getThreadFactory().newThread(new AutoDeallocator()).start();
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
		claimLock.lock();
		try {
			return claimOrCreateOrWaitUntilAvailable(timeout);
		} finally {
			claimLock.unlock();
		}
	}

	/**
	 * Delegates to {@link #claimMatching(Predicate, Timeout)}.
	 */
	@Nullable
	public PoolableObject<T> claimMatching(@NotNull final Predicate<PoolableObject<T>> predicate, final long timeout, final TimeUnit timeUnit) throws InterruptedException {
		return claimMatching(predicate, new Timeout(timeout, timeUnit));
	}

	/**
	 * Claims an already available object matching the predicate, or waits until one becomes available or matches due to time passing.
	 * <p>
	 * This method does not allocate new objects. Keep the predicate fast and side-effect free; it is evaluated while the pool claim lock is held.
	 */
	@SuppressWarnings("WeakerAccess")
	@Nullable
	public PoolableObject<T> claimMatching(@NotNull final Predicate<PoolableObject<T>> predicate, final Timeout timeout) throws InterruptedException, IllegalStateException {
		requireNonNull(predicate, "predicate");
		claimLock.lock();
		try {
			return claimMatchingOrWaitUntilAvailable(predicate, timeout);
		} finally {
			claimLock.unlock();
		}
	}

	void releasePoolableObject(final PoolableObject<T> claimedObject) {
		claimLock.lock();
		try {
			if (isShuttingDown()) {
				invalidatePoolableObject(claimedObject);
			} else if (claimedObject.getCurrentPoolStatus() == PoolableObject.PoolStatus.CLAIMED) {
				allocator.deallocateForReuse(claimedObject.getAllocatedObject());
				currentlyClaimed.decrementAndGet();
				claimedObject.resetAvailableTimestamp();
				claimedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.AVAILABLE);
				available.addLast(claimedObject);

				signalAllWaitingClaimers();
			}
		} finally {
			claimLock.unlock();
		}
	}

	void invalidatePoolableObject(final PoolableObject<T> claimedObject) {
		claimLock.lock();
		try {
			if (claimedObject.getCurrentPoolStatus() == PoolableObject.PoolStatus.CLAIMED) {
				currentlyClaimed.decrementAndGet();
			} else if (claimedObject.getCurrentPoolStatus() == PoolableObject.PoolStatus.AVAILABLE) {
				available.remove(claimedObject);
			}
		} finally {
			claimLock.unlock();
		}

		if (claimedObject.getCurrentPoolStatus().ordinal() < PoolableObject.PoolStatus.WAITING_FOR_DEALLOCATION.ordinal()) {
			addObjectForDeallocation(claimedObject);
		}
	}

	private void addObjectForDeallocation(final PoolableObject<T> claimedObject) {
		deallocateLock.lock();
		try {
			waitingForDeallocation.add(claimedObject);
			claimedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.WAITING_FOR_DEALLOCATION);
			objectWaitingForDeallocation.signal();
		} finally {
			deallocateLock.unlock();
		}
	}

	private PoolableObject<T> getObjectForDeallocation() {
		PoolableObject<T> poolableObject = null;
		deallocateLock.lock();
		try {
			if (!waitingForDeallocation.isEmpty()) {
				poolableObject = waitingForDeallocation.remove();
			}
		} finally {
			deallocateLock.unlock();
		}
		return poolableObject;
	}

	@Nullable
	private PoolableObject<T> waitForObjectForDeallocation() {
		PoolableObject<T> poolableObject = null;
		deallocateLock.lock();
		try {
			if (waitingForDeallocation.isEmpty() && !isShuttingDown()) {
				final boolean deallocationAvailable = objectWaitingForDeallocation.await(DEALLOCATION_WAIT_MS, TimeUnit.MILLISECONDS);
				if (!deallocationAvailable && waitingForDeallocation.isEmpty()) {
					return null;
				}
			}
			if (!waitingForDeallocation.isEmpty()) {
				poolableObject = waitingForDeallocation.remove();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			deallocateLock.unlock();
		}
		return poolableObject;
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
	private PoolableObject<T> claimMatchingOrWaitUntilAvailable(final Predicate<PoolableObject<T>> predicate, final Timeout timeout) throws InterruptedException, IllegalStateException {
		final long deadlineMs = calculateDeadlineMs(timeout);
		PoolableObject<T> entry;
		do {
			if (isShuttingDown()) {
				throw new IllegalStateException("Pool has been shutdown");
			}
			entry = claimAvailableObjectMatching(predicate);
		} while (entry == null && waitForMatchingObjectOrTimeout(deadlineMs));
		return entry;
	}
	
	@Nullable
	private PoolableObject<T> claimOrCreateNewObjectIfSpaceLeft() {
		PoolableObject<T> claimedObject = !available.isEmpty() ? available.removeFirst() : null;
		if (claimedObject != null) {
			prepareClaimedObjectForReuse(claimedObject);
		} else if (getCurrentlyAllocated() < poolConfig.getMaxPoolsize()) {
			claimedObject = new PoolableObject<>(this, allocator.allocate());
			claimedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.CLAIMED);
			currentlyClaimed.incrementAndGet();
			totalAllocated.incrementAndGet();
			totalClaimed.incrementAndGet();
		}
		return claimedObject;
	}

	@Nullable
	private PoolableObject<T> claimAvailableObjectMatching(final Predicate<PoolableObject<T>> predicate) {
		for (Iterator<PoolableObject<T>> iterator = available.iterator(); iterator.hasNext(); ) {
			final PoolableObject<T> poolableObject = iterator.next();
			if (predicate.test(poolableObject)) {
				iterator.remove();
				prepareClaimedObjectForReuse(poolableObject);
				return poolableObject;
			}
		}
		return null;
	}

	private void prepareClaimedObjectForReuse(final PoolableObject<T> claimedObject) {
		allocator.allocateForReuse(claimedObject.getAllocatedObject());
		claimedObject.resetAllocationTimestamp();
		claimedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.CLAIMED);
		currentlyClaimed.incrementAndGet();
		totalClaimed.incrementAndGet();
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
		return waitForAvailableObjectOrTimeout(timeout.getDuration(), timeout.getTimeUnit());
	}

	private boolean waitForAvailableObjectOrTimeout(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
		final Condition objectAvailability = claimLock.newCondition();
		try {
			objectAvailableConditions.add(objectAvailability);
			final boolean await = objectAvailability.await(timeout, timeUnit);
			if (isShuttingDown()) {
				throw new InterruptedException("Pool is shutting down");
			}
			return await;
		} finally {
			objectAvailableConditions.remove(objectAvailability);
		}
	}

	private boolean waitForMatchingObjectOrTimeout(final long deadlineMs) throws InterruptedException {
		final long remainingMs = deadlineMs - System.currentTimeMillis();
		if (remainingMs <= 0) {
			return false;
		}
		final long waitMs = Math.min(remainingMs, MATCHING_CLAIM_RECHECK_INTERVAL_MS);
		return waitForAvailableObjectOrTimeout(waitMs, TimeUnit.MILLISECONDS) || System.currentTimeMillis() < deadlineMs;
	}

	private long calculateDeadlineMs(final Timeout timeout) {
		final long now = System.currentTimeMillis();
		final long durationMs = timeout.getDurationMs();
		return durationMs >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationMs;
	}

	/**
	 * Shuts down the current Pool stopping new allocations and triggering deallocations on all other available objects. Waits for
	 * claimed objects to become available.
	 */
	public synchronized Future<Void> shutdown() {
		if (!isShuttingDown()) {
			ExecutorService executorService = newSingleThreadExecutor(poolConfig.getThreadFactory());
			shutdownSequence = executorService.submit(new ShutdownSequence(), null);
			executorService.shutdown();
		}
		return shutdownSequence;
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
		claimLock.lock();
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
			claimLock.unlock();
		}
	}

	private void deallocate(final PoolableObject<T> invalidatedObject) {
		try {
			allocator.deallocate(invalidatedObject.getAllocatedObject());
		} catch (Exception e) {
			log.error("error deallocating object already removed from the pool, ignoring it from now on...", e);
		}
		invalidatedObject.setCurrentPoolStatus(PoolableObject.PoolStatus.DEALLOCATED);
		invalidatedObject.dereferenceObject();
	}

	private void scheduleDeallocations() {
		final ExpirationPolicy<T> expirationPolicy = poolConfig.getExpirationPolicy();
		if (expirationPolicy == ExpirationPolicy.NeverExpirePolicy.getInstance()) {
			return;
		}
		int objectsInvalidated = invalidateExpiredObjects(getExpiredObjects(expirationPolicy));
        if (objectsInvalidated > 0) {
			log.trace("{} objects invalidated as per expiration policy!", objectsInvalidated);
        }
	}

	private int invalidateExpiredObjects(List<PoolableObject<T>> expiredObjects) {
		int invalidatedObjects = 0;
		claimLock.lock();
		try {
			for (final PoolableObject<T> poolableObject : expiredObjects) {
				poolableObject.invalidate();
				invalidatedObjects++;
			}
		} finally {
			claimLock.unlock();
		}
		return invalidatedObjects;
	}

	private List<PoolableObject<T>> getExpiredObjects(ExpirationPolicy<T> expirationPolicy){
		List<PoolableObject<T>> expiredObjects = new ArrayList<>();
		claimLock.lock();
		try {
			for (final PoolableObject<T> poolableObject : available) {
				if (expirationPolicy.hasExpired(poolableObject)) {
					expiredObjects.add(poolableObject);
				}
			}
		} finally {
			claimLock.unlock();
		}
		return expiredObjects;
	}

	/**
	 * <ol>
	 *     <li>Automatically plan deallocation for expired objects</li>
	 *     <li>Automatically deallocates one object every loop</li>
	 * </ol>
	 */
	private class AutoDeallocator implements Runnable {

		@Override
		@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH", justification = "False positive")
		public void run() {
			//noinspection ConstantConditions
			while (shutdownSequence == null || !shutdownSequence.isDone() || !waitingForDeallocation.isEmpty()) {
				deallocateOneOrPlanDeallocations();
			}
			log.debug("AutoDeallocator finished");
		}

		private void deallocateOneOrPlanDeallocations() {
			final boolean shouldScheduleDeallocations = poolConfig.getExpirationPolicy() != ExpirationPolicy.NeverExpirePolicy.getInstance();
			PoolableObject<T> poolableObject = shouldScheduleDeallocations ? getObjectForDeallocation() : waitForObjectForDeallocation();
			final boolean deallocatedAnObject = poolableObject != null;
			if (poolableObject != null) {
				deallocate(poolableObject);
			}
			if (!deallocatedAnObject && shouldScheduleDeallocations) {
				scheduleDeallocations();
			}
			SleepUtil.sleep(isShuttingDown() ? 0 : deallocatedAnObject ? 50 : shouldScheduleDeallocations ? 10 : 0);
		}
	}

	/**
	 * <ol>
	 * <li>Automatically allocates objects until core pool size is met. Initially fills up the pool and when object are
	 * deallocated.</li>
	 * </ol>
	 */
	private class AutoAllocator implements Runnable {

		@Override
		@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH", justification = "False positive")
		public void run() {
			//noinspection ConstantConditions
			while (shutdownSequence == null || !shutdownSequence.isDone() || !waitingForDeallocation.isEmpty()) {
				allocatedCorePool();
				SleepUtil.sleep(5);
			}
			log.debug("AutoAllocator finished");
		}

		private void allocatedCorePool() {
			claimLock.lock();
			try {
				while (getCurrentlyAllocated() < poolConfig.getCorePoolsize() && !isShuttingDown()) {
					available.addLast(new PoolableObject<>(GenericObjectPool.this, allocator.allocate()));
					totalAllocated.incrementAndGet();
				}
			} catch (Exception e) {
				log.error("Not able to allocate new object! This might be a temporary issue due to external reasons (a server rejecting a connection for example).", e);
			} finally {
				claimLock.unlock();
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
			claimLock.lock();
			try {
				while (!available.isEmpty()) {
					available.remove().invalidate();
				}
				signalAllWaitingClaimers();
			} finally {
				claimLock.unlock();
			}
			signalObjectWaitingForDeallocation();
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

	private void signalAllWaitingClaimers() {
		for (Condition condition : objectAvailableConditions) {
			condition.signal();
		}
	}

	private void signalObjectWaitingForDeallocation() {
		deallocateLock.lock();
		try {
			objectWaitingForDeallocation.signal();
		} finally {
			deallocateLock.unlock();
		}
	}
}
