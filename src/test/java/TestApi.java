import lombok.RequiredArgsConstructor;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.PoolConfig;
import org.bbottema.genericobjectpool.PoolMetrics;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.SimpleObjectPool;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceLastAllocationExpirationPolicy;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestApi {
    
    @Test
    public void testPublicAPI() throws Exception {
        final PoolConfig<AtomicReference<Integer>> poolConfig = PoolConfig.<AtomicReference<Integer>>builder()
                .maxPoolsize(3)
                .build();
        SimpleObjectPool<AtomicReference<Integer>> pool = new SimpleObjectPool<>(poolConfig, new MyAllocator());
        
        PoolableObject<AtomicReference<Integer>> obj1 = pool.claim();
        PoolableObject<AtomicReference<Integer>> obj2 = pool.claim();
        PoolableObject<AtomicReference<Integer>> obj3 = pool.claim();
        
        PoolableObject<AtomicReference<Integer>> obj4 = pool.claim(100, TimeUnit.MILLISECONDS);
        
        assertThat(obj1).isNotNull();
        assertThat(obj2).isNotNull();
        assertThat(obj3).isNotNull();
        assertThat(obj4).isNull();
        
        obj1.release();
        obj4 = pool.claim(100, TimeUnit.MILLISECONDS);
        PoolableObject<AtomicReference<Integer>> obj5 = pool.claim(100, TimeUnit.MILLISECONDS);
        
        assertThat(obj4).isNotNull();
        assertThat(obj5).isNull();
        
        obj3.invalidate();
        obj5 = pool.claim(100, TimeUnit.MILLISECONDS);
        
        assertThat(obj5).isNotNull();
    }
    
    @Test
    public void testShutdownWithWaitingThreads() throws Exception {
        final SimpleObjectPool<AtomicReference<Integer>> pool = new SimpleObjectPool<>(PoolConfig.<AtomicReference<Integer>>builder().maxPoolsize(1).build(), new MyAllocator());
        
        ExecutorService es = Executors.newSingleThreadExecutor();
        
        AtomicReference<PoolableObject<AtomicReference<Integer>>> claimedPoolable1 = new AtomicReference<>();
        AtomicReference<PoolableObject<AtomicReference<Integer>>> claimedPoolable2 = new AtomicReference<>();
    
        AtomicReference<Future<?>> claimer1Ref = claimInNewThread(pool, es, claimedPoolable1);
    
        AtomicReference<Future<?>> claimer2Ref = claimInNewThread(pool, es, claimedPoolable2);
    
        while (!claimer1Ref.get().isDone()) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        
        assertThat(claimer2Ref.get()).isNotCancelled();
        assertThat(claimer2Ref.get()).isNotDone();
        
        assertThat(claimedPoolable1.get()).isNotNull();
        assertThat(claimedPoolable1.get().getAllocatedObject()).isNotNull();
        assertThat(requireNonNull(claimedPoolable1.get().getAllocatedObject()).get()).isNotNull();
        assertThat(claimedPoolable2.get()).isNull();
        
        final Future<?> shutdownResult = pool.shutdown();
        TimeUnit.MILLISECONDS.sleep(100);
        
        assertThat(claimer1Ref.get()).as("claimer1").isDone();
        assertThat(claimer2Ref.get()).as("claimer2").isCancelled();
        
        assertThat(shutdownResult).isNotDone();
        assertThat(shutdownResult).isNotCancelled();
        
        assertThatThrownBy(new FutureGet(shutdownResult, 100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        claimedPoolable1.get().release();
        shutdownResult.get(100, TimeUnit.MILLISECONDS);
        assertThat(shutdownResult).isDone();
        
        // verify the object was deallocated
        assertThat(claimedPoolable1.get().getAllocatedObject()).isNull();
        assertThat(claimedPoolable2.get()).isNull();
    
        assertAllMetricsZero(pool);
    }
    
    @Test
    public void testShutdownWithAvailableObjects() throws Exception {
        final SimpleObjectPool<AtomicReference<Integer>> pool = new SimpleObjectPool<>(PoolConfig.<AtomicReference<Integer>>builder().maxPoolsize(2).build(), new MyAllocator());
        
        ExecutorService es = Executors.newSingleThreadExecutor();
        
        AtomicReference<PoolableObject<AtomicReference<Integer>>> claimedPoolable1 = new AtomicReference<>();
        AtomicReference<PoolableObject<AtomicReference<Integer>>> claimedPoolable2 = new AtomicReference<>();
    
        AtomicReference<Future<?>> claimer1Ref = claimInNewThread(pool, es, claimedPoolable1);
        AtomicReference<Future<?>> claimer2Ref = claimInNewThread(pool, es, claimedPoolable2);
    
        
        while (!claimer1Ref.get().isDone() || !claimer2Ref.get().isDone()) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
    
        final PoolableObject<AtomicReference<Integer>> poolableObj1 = claimedPoolable1.get();
        final PoolableObject<AtomicReference<Integer>> poolableObj2 = claimedPoolable2.get();
        
        assertThat(poolableObj1).isNotNull();
        assertThat(poolableObj1.getAllocatedObject()).isNotNull();
        assertThat(poolableObj1.getAllocatedObject().get()).isNotNull();
        assertThat(poolableObj2).isNotNull();
        assertThat(poolableObj2.getAllocatedObject()).isNotNull();
        assertThat(poolableObj2.getAllocatedObject().get()).isNotNull();
    
        poolableObj1.release();
        
        final Future<?> shutdownResult = pool.shutdown();
        TimeUnit.MILLISECONDS.sleep(100);
        
        assertThat(claimer1Ref.get()).as("claimer1").isDone();
        assertThat(claimer2Ref.get()).as("claimer2").isDone();
    
        assertThat(shutdownResult).isNotDone();
        assertThat(poolableObj1.getAllocatedObject()).isNull();
        assertThat(poolableObj2.getAllocatedObject()).isNotNull();
        
        poolableObj2.release();
    
        shutdownResult.get(100, TimeUnit.MILLISECONDS);
        assertThat(shutdownResult).isDone();
        
        // verify the object was deallocated
        assertThat(poolableObj1.getAllocatedObject()).isNull();
        assertThat(poolableObj2.getAllocatedObject()).isNull();
    
        assertAllMetricsZero(pool);
    }
    
    private void assertAllMetricsZero(SimpleObjectPool<AtomicReference<Integer>> pool) {
        final PoolMetrics poolMetrics = pool.getPoolMetrics();
        assertThat(poolMetrics.getClaimedCount()).isZero();
        assertThat(poolMetrics.getAllocationSize()).isZero();
        assertThat(poolMetrics.getWaitingCount()).isZero();
    }
    
    @NotNull
    private AtomicReference<Future<?>> claimInNewThread(SimpleObjectPool<AtomicReference<Integer>> pool, ExecutorService es, AtomicReference<PoolableObject<AtomicReference<Integer>>> claimedPoolable1) {
        AtomicReference<Future<?>> claimer1Ref = new AtomicReference<>();
        claimer1Ref.set(es.submit(new ConcurrentClaimer(pool, claimedPoolable1, claimer1Ref)));
        return claimer1Ref;
    }
    
    static class MyAllocator extends Allocator<AtomicReference<Integer>> {
        private AtomicInteger counter = new AtomicInteger();

        @NotNull
        @Override
        public AtomicReference<Integer> allocate() {
            return new AtomicReference<>(counter.incrementAndGet());
        }
    }
    
    @RequiredArgsConstructor
    private static class ConcurrentClaimer implements Runnable {
        @NotNull private final SimpleObjectPool<AtomicReference<Integer>> pool;
        @NotNull private final AtomicReference<PoolableObject<AtomicReference<Integer>>> claimedPoolable;
        @NotNull private final AtomicReference<Future<?>> claimer1Ref;
        
        public void run() {
            try {
                claimedPoolable.set(pool.claim());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                claimer1Ref.get().cancel(false);
            }
        }
    }
    
    @RequiredArgsConstructor
    private static class FutureGet implements ThrowingCallable {
        private final Future<?> shutdownResult;
        private final long timeout;
        private final TimeUnit timeUnit;
        
        @Override
        public void call() throws Throwable {
            shutdownResult.get(timeout, timeUnit);
        }
    }
}
