package org.bbottema.genericobjectpool;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.NonFinal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Builder
@NonFinal@Value
@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "Generated code")
public class PoolConfig<T> {
	/**
	 * Determines how large the pool is allowed to grow. Can be unbounded by using {@link Integer#MAX_VALUE}.
	 */
	private final int maxPoolsize;
	/**
	 * Determines how many objects should be kept allocated even without claims. For unbounded pools, be careful not to provide a large
	 * number, or else the pool will run out of memory.
	 */
	private final int corePoolsize;
	/**
	 * Optional custom thread factory, in case you nee dto manager your own thread production.
	 * <p>
	 * It will be used instead of {@link Executors#defaultThreadFactory()} to create the auto allocator/deallocater thread (auto allocates if if core size > 0).
	 */
	@NotNull private final ThreadFactory threadFactory;
	@NotNull private final ExpirationPolicy<T> expirationPolicy;
	
	@SuppressWarnings("unused")
	private PoolConfig(int maxPoolsize, int corePoolsize, @Nullable ThreadFactory threadFactory, @Nullable ExpirationPolicy<T> expirationPolicy) {
		this.maxPoolsize = maxPoolsize;
		this.corePoolsize = corePoolsize;
		this.threadFactory = (threadFactory != null) ? threadFactory : Executors.defaultThreadFactory();
		this.expirationPolicy = (expirationPolicy != null) ? expirationPolicy : ExpirationPolicy.NeverExpirePolicy.<T>getInstance();
		
		if (maxPoolsize <= 0) {
			throw new IllegalArgumentException("Pool size should have a max size of at least one");
		}
		if (corePoolsize > maxPoolsize) {
			throw new IllegalArgumentException("Core pool size cannot be bigger than the pool's max size");
		}
	}
}