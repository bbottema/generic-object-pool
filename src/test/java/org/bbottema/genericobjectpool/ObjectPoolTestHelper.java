package org.bbottema.genericobjectpool;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract Test Case for Pooled Objects
 */
@UtilityClass
class ObjectPoolTestHelper {

	@NotNull
	static Allocator<String> createAllocator(final String key) {
		return new Allocator<String>() {
			@NotNull
			public String allocate() {
				return "This is a Test : " + key;
			}
		};
	}
}