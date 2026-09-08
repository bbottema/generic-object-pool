package consumer;

import org.bbottema.genericobjectpool.AllocationContext;
import org.bbottema.genericobjectpool.Allocator;
import org.bbottema.genericobjectpool.ClaimControl;
import org.bbottema.genericobjectpool.ClaimOptions;
import org.bbottema.genericobjectpool.GenericObjectPool;
import org.bbottema.genericobjectpool.PoolConfig;
import org.bbottema.genericobjectpool.PoolableObject;

import java.util.concurrent.TimeUnit;

/** Runs on both the classpath and module path without a test framework. */
public final class PoolApiConsumer {
	public static void main(final String[] args) throws Exception {
		final ClaimControl control = new ClaimControl();
		final GenericObjectPool<String> pool = new GenericObjectPool<>(PoolConfig.<String>builder().maxPoolsize(1).build(),
				new Allocator<String>() {
					@Override public String allocate() { return "legacy"; }
					@Override public String allocate(final AllocationContext context) {
						context.throwIfCancellationRequested();
						return "controlled";
					}
				});
		try {
			final PoolableObject<String> lease = pool.claim(ClaimOptions.withTimeout(2, TimeUnit.SECONDS).withClaimControl(control));
			if (lease == null || !"controlled".equals(lease.getAllocatedObject())) {
				throw new AssertionError("Context-aware claim failed");
			}
			control.requestCancellation();
			lease.invalidate();
			lease.getDisposalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
		} finally {
			pool.shutdown().get(5, TimeUnit.SECONDS);
		}
		System.out.println("Opt-in claim API: OK");
	}
}
