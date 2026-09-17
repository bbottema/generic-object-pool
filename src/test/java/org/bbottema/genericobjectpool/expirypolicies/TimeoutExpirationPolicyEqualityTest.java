package org.bbottema.genericobjectpool.expirypolicies;

import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Timeout policies of different types must stay distinct even when configured with the same threshold or bounds.
 * They derive equality from the timeout value in their common superclass, so without type-aware equality two
 * different rules collapse into one wherever they are held by identity: the {@link Set} handed to
 * {@link CombinedExpirationPolicies}, and the {@link PoolableObject#getExpiriesMs()} map each policy resolves its
 * own threshold from. The failure is silent - the configuration is accepted and one rule simply never runs.
 */
public class TimeoutExpirationPolicyEqualityTest {

	private PoolableObject<Integer> mockPO;
	private Map<ExpirationPolicy, Long> expiriesMs;

	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setup() {
		mockPO = mock(PoolableObject.class);
		// A real map, not a mock: the point of these tests is which keys it ends up holding.
		expiriesMs = new HashMap<>();
		when(mockPO.getExpiriesMs()).thenReturn(expiriesMs);
	}

	@Test
	public void identicalThresholdsOfDifferentTypesAreNotEqualAndBothSurviveASet() {
		ExpirationPolicy<Integer> creation = new TimeoutSinceCreationExpirationPolicy<>(50, MILLISECONDS);
		ExpirationPolicy<Integer> allocation = new TimeoutSinceLastAllocationExpirationPolicy<>(50, MILLISECONDS);

		assertThat(creation).isNotEqualTo(allocation);
		assertThat(allocation).isNotEqualTo(creation);

		Set<ExpirationPolicy<Integer>> policies = new HashSet<>();
		policies.add(creation);
		policies.add(allocation);
		assertThat(policies).hasSize(2);
	}

	@Test
	public void identicalBoundsOfDifferentSpreadedTypesAreNotEqualAndBothSurviveASet() {
		ExpirationPolicy<Integer> creation = new SpreadedTimeoutSinceCreationExpirationPolicy<>(50, 100, MILLISECONDS);
		ExpirationPolicy<Integer> allocation = new SpreadedTimeoutSinceLastAllocationExpirationPolicy<>(50, 100, MILLISECONDS);

		assertThat(creation).isNotEqualTo(allocation);
		assertThat(allocation).isNotEqualTo(creation);

		Set<ExpirationPolicy<Integer>> policies = new HashSet<>();
		policies.add(creation);
		policies.add(allocation);
		assertThat(policies).hasSize(2);
	}

	@Test
	public void matchingConfigurationsOfTheSameTypeStayEqualWithConsistentHashCodes() {
		ExpirationPolicy<Integer> first = new TimeoutSinceCreationExpirationPolicy<>(50, MILLISECONDS);
		ExpirationPolicy<Integer> second = new TimeoutSinceCreationExpirationPolicy<>(50, MILLISECONDS);
		ExpirationPolicy<Integer> spreadedFirst = new SpreadedTimeoutSinceLastAllocationExpirationPolicy<>(50, 100, MILLISECONDS);
		ExpirationPolicy<Integer> spreadedSecond = new SpreadedTimeoutSinceLastAllocationExpirationPolicy<>(50, 100, MILLISECONDS);

		assertThat(first).isEqualTo(second);
		assertThat(first.hashCode()).isEqualTo(second.hashCode());
		assertThat(spreadedFirst).isEqualTo(spreadedSecond);
		assertThat(spreadedFirst.hashCode()).isEqualTo(spreadedSecond.hashCode());

		// Still one logical rule, so a set keeps one of each pair.
		Set<ExpirationPolicy<Integer>> policies = new HashSet<>();
		policies.add(first);
		policies.add(second);
		policies.add(spreadedFirst);
		policies.add(spreadedSecond);
		assertThat(policies).hasSize(2);

		// A differing threshold is a different rule again.
		assertThat(first).isNotEqualTo(new TimeoutSinceCreationExpirationPolicy<Integer>(60, MILLISECONDS));
	}

	@Test
	public void eachPolicyRegistersItsOwnThresholdUnderItsOwnKey() {
		ExpirationPolicy<Integer> creation = new TimeoutSinceCreationExpirationPolicy<>(50, MILLISECONDS);
		ExpirationPolicy<Integer> allocation = new TimeoutSinceLastAllocationExpirationPolicy<>(70, MILLISECONDS);
		when(mockPO.ageMs()).thenReturn(10L);
		when(mockPO.allocationAgeMs()).thenReturn(10L);

		combined(creation, allocation).hasExpired(mockPO);

		assertThat(expiriesMs).hasSize(2);
		assertThat(expiriesMs).containsEntry(creation, 50L);
		assertThat(expiriesMs).containsEntry(allocation, 70L);
	}

	/**
	 * The deterministic case behind the whole defect: an object created 100 ms ago but claimed 10 ms ago, judged by
	 * an age rule and an idle rule both set to 50 ms. Only the age rule can retire it, and it has to do so whichever
	 * order the two were registered in.
	 */
	@Test
	public void creationAgeStillExpiresARecentlyClaimedObjectWhicheverOrderThePoliciesWereAddedIn() {
		when(mockPO.ageMs()).thenReturn(100L);
		when(mockPO.allocationAgeMs()).thenReturn(10L);

		ExpirationPolicy<Integer> creation = new TimeoutSinceCreationExpirationPolicy<>(50, MILLISECONDS);
		ExpirationPolicy<Integer> allocation = new TimeoutSinceLastAllocationExpirationPolicy<>(50, MILLISECONDS);

		// Allocation rule first: the one that does not fire is consulted before the one that does.
		assertThat(combined(allocation, creation).hasExpired(mockPO)).isTrue();
		assertThat(expiriesMs).hasSize(2);

		expiriesMs.clear();

		assertThat(combined(creation, allocation).hasExpired(mockPO)).isTrue();
		// Both are evaluated even once one has expired, so both register their threshold.
		assertThat(expiriesMs).hasSize(2);
	}

	@Test
	public void anIdleRuleAloneDoesNotExpireARecentlyClaimedObject() {
		when(mockPO.ageMs()).thenReturn(100L);
		when(mockPO.allocationAgeMs()).thenReturn(10L);

		ExpirationPolicy<Integer> allocation = new TimeoutSinceLastAllocationExpirationPolicy<>(50, MILLISECONDS);

		assertThat(allocation.hasExpired(mockPO)).isFalse();
	}

	/**
	 * @return the two policies combined in the given order, which a {@link LinkedHashSet} preserves.
	 */
	private CombinedExpirationPolicies<Integer> combined(ExpirationPolicy<Integer> first, ExpirationPolicy<Integer> second) {
		Set<ExpirationPolicy<Integer>> ordered = new LinkedHashSet<>();
		ordered.add(first);
		ordered.add(second);
		return new CombinedExpirationPolicies<>(ordered);
	}
}
