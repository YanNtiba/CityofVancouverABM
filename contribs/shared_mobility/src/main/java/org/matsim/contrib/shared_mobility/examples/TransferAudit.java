package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class TransferAudit {
	private final Map<Id<Person>, Boolean> eligibleAtPickup = new ConcurrentHashMap<>();
	public void mark(Id<Person> personId, boolean eligible) { eligibleAtPickup.put(personId, eligible); }
	public boolean take(Id<Person> personId) { return Boolean.TRUE.equals(eligibleAtPickup.remove(personId)); }
}
