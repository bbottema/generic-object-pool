package org.bbottema.genericobjectpool;

import lombok.Value;
import lombok.experimental.NonFinal;

@NonFinal@Value
public class PoolMetrics {
	
    private final int claimedCount;
	private final int waitingCount;
	private final int maxObjectsPerKey;
	private final int allocationSize;
	private final long totalAllocated;
	private final long totalClaimed;
	
	PoolMetrics(int claimedCount, int waitingCount, int allocationSize, int maxObjects, long totalAllocated, long totalClaimed) {
		this.claimedCount = claimedCount;
		this.waitingCount = waitingCount;
		this.maxObjectsPerKey = maxObjects;
		this.allocationSize = allocationSize;
		this.totalAllocated = totalAllocated;
		this.totalClaimed = totalClaimed;
	}
}