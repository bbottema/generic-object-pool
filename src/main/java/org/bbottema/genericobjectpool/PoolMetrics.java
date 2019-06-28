package org.bbottema.genericobjectpool;

import lombok.Value;
import lombok.experimental.NonFinal;

@NonFinal@Value
public class PoolMetrics {
	
    private final int claimedCount;
	private final int waitingCount;
	private final int maxObjectsPerKey;
    private final int allocationSize;

	PoolMetrics(int claimedCount, int waitingCount, int allocationSize, int maxObjects) {
		this.claimedCount = claimedCount;
		this.waitingCount = waitingCount;
		this.maxObjectsPerKey = maxObjects;
		this.allocationSize = allocationSize;
	}
}