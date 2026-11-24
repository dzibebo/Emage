package net.ed1thy.emage.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AdmissionControl {

    static final double OVERBOOK = 1.3;
    static final double KEEP_BONUS = 1.25;

    record Candidate(int id, double priority, double demand, boolean wasAdmitted) {}

    private AdmissionControl() {}

    static Set<Integer> select(List<Candidate> candidates, double budget) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> -(c.priority() * (c.wasAdmitted() ? KEEP_BONUS : 1.0)))
                .thenComparingDouble(Candidate::demand));
        Set<Integer> admitted = new HashSet<>();
        double used = 0;
        for (Candidate candidate : sorted) {
            if (admitted.isEmpty()) {
                admitted.add(candidate.id());
                used += candidate.demand();
                continue;
            }
            if (used + candidate.demand() <= budget * OVERBOOK) {
                admitted.add(candidate.id());
                used += candidate.demand();
            }
        }
        return admitted;
    }
}
