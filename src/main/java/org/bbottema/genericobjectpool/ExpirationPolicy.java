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