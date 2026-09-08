import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolConfig;
import org.bbottema.genericobjectpool.PoolableObject;

import java.util.concurrent.TimeUnit;

/** Compiled against 2.4.3, then run unchanged against the candidate JAR. */
public final class LegacyAllocatorClient {
	public static void main(final String[] args) throws Exception {
		final GenericObjectPool<String> pool = new GenericObjectPool<>(PoolConfig.<String>builder().maxPoolsize(1).build(),
				new Allocator<String>() {
					@Override public String allocate() { return "legacy"; }
				});
		try {
			final PoolableObject<String> first = pool.claim(2, TimeUnit.SECONDS);
			if (first == null || !"legacy".equals(first.getAllocatedObject())) {
				throw new AssertionError("Legacy claim failed");
			}
			first.release();
			final PoolableObject<String> reused = pool.claimMatching(value -> true, 2, TimeUnit.SECONDS);
			if (reused != first) {
				throw new AssertionError("Legacy matching claim did not reuse the object");
			}
			reused.invalidate();
		} finally {
			pool.shutdown().get(5, TimeUnit.SECONDS);
		}
		System.out.println("Previously compiled allocator and claim API: OK");
	}
}
