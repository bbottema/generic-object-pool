package org.bbottema.genericobjectpool;

import lombok.experimental.UtilityClass;

@UtilityClass
final class SleepUtil {
	static void sleep(final int durationMs) {
		try {
			Thread.sleep(durationMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}