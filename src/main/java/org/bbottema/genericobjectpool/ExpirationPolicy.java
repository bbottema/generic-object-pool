package org.bbottema.genericobjectpool;

import lombok.NoArgsConstructor;
import lombok.Value;
import lombok.experimental.NonFinal;
import org.jetbrains.annotations.NotNull;

import static lombok.AccessLevel.PRIVATE;

public interface ExpirationPolicy<T> {
	boolean hasExpired(@NotNull PoolableObject<T> poolableObject);
	
	@NonFinal
	@Value
	@NoArgsConstructor(access = PRIVATE)
	class NeverExpirePolicy<T> implements ExpirationPolicy<T> {
		private static final NeverExpirePolicy<?> NEVER_EXPIRE_POLICY = new NeverExpirePolicy<>();
		
		@SuppressWarnings("unchecked")
		static <T> NeverExpirePolicy<T> getInstance() {
			return (NeverExpirePolicy<T>) NEVER_EXPIRE_POLICY;
		}
		
		@Override
		public boolean hasExpired(@NotNull PoolableObject<T> poolableObject) {
			return false;
		}
	}
}