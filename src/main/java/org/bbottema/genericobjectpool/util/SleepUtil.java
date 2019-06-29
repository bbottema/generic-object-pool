package org.bbottema.genericobjectpool.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class SleepUtil {
	public static void sleep(final int durationMs) {
		try {
			Thread.sleep(durationMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}