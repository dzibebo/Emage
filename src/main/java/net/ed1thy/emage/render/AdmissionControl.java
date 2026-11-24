package net.ed1thy.emage.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AdmissionControl {

    static final double OVERBOOK = 1.3;
    static final double KEEP_BONUS = 1.25;
    static final double MAX_SHARE = 0.5;
    static final double HEADROOM = 1.15;

    record Candidate(int id, double priority, double demand, boolean wasAdmitted) {}

    private AdmissionControl() {}

    static Map<Integer, Double> select(List<Candidate> candidates, double budget) {
        Map<Integer, Double> admitted = new LinkedHashMap<>();
        if (candidates.isEmpty()) return admitted;

        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> -(c.priority() * (c.wasAdmitted() ? KEEP_BONUS : 1.0)))
                .thenComparingDouble(Candidate::demand));

        boolean several = candidates.size() > 1;
        double used = 0;
        Map<Integer, Double> cost = new LinkedHashMap<>();
        for (Candidate candidate : sorted) {
            double share = Math.min(candidate.demand(), several ? budget * MAX_SHARE : budget);
            if (admitted.isEmpty() || used + share <= budget * OVERBOOK) {
                admitted.put(candidate.id(), share);
                cost.put(candidate.id(), share);
                used += share;
            }
        }

        double granted = 0;
        for (double value : admitted.values()) granted += value * HEADROOM;
        double leftover = Math.max(0.0, budget - granted);
        List<Integer> capped = new ArrayList<>();
        for (Candidate candidate : sorted) {
            Double share = cost.get(candidate.id());
            if (share != null && candidate.demand() > share * 1.001) capped.add(candidate.id());
        }
        for (Map.Entry<Integer, Double> entry : admitted.entrySet()) {
            double allowance = entry.getValue() * HEADROOM;
            if (!capped.isEmpty() && capped.contains(entry.getKey())) allowance += leftover / capped.size();
            entry.setValue(allowance);
        }
        return admitted;
    }
}
