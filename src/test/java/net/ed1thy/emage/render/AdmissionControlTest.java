package net.ed1thy.emage.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionControlTest {

    private static AdmissionControl.Candidate gif(int id, double priority, double demandMb, boolean was) {
        return new AdmissionControl.Candidate(id, priority, demandMb * 1_048_576.0, was);
    }

    @Test
    void theGifInFocusAlwaysAnimatesButADemandingOneCannotTakeTheWholeBudgetFromTheOthers() {
        Map<Integer, Double> admitted = AdmissionControl.select(List.of(gif(1, 1.0, 50, false), gif(2, 0.3, 1, false)), 8 * 1_048_576.0);

        assertTrue(admitted.containsKey(1));
        assertTrue(admitted.containsKey(2), "a small gif must still play next to a huge one");
        assertTrue(admitted.get(1) < 8 * 1_048_576.0, "the huge gif cannot take more than the budget: " + admitted.get(1));
        assertTrue(admitted.get(2) >= 1_048_576.0, "the small gif gets what it needs: " + admitted.get(2));
    }

    @Test
    void aSingleDemandingGifMayUseTheWholeBudget() {
        Map<Integer, Double> admitted = AdmissionControl.select(List.of(gif(1, 1.0, 50, false)), 8 * 1_048_576.0);

        assertTrue(admitted.get(1) >= 8 * 1_048_576.0 && admitted.get(1) <= 8 * 1_048_576.0 * 1.2, "a lone gif may use the whole budget: " + admitted.get(1));
    }

    @Test
    void cheapGifsAreAdmittedUntilTheBudgetIsUsedAndExpensiveOnesWait() {
        Set<Integer> admitted = AdmissionControl.select(List.of(
                gif(1, 1.0, 2, false), gif(2, 1.0, 2, false), gif(3, 1.0, 2, false), gif(4, 1.0, 2, false), gif(5, 1.0, 40, false), gif(6, 1.0, 2, false)),
                8 * 1_048_576.0).keySet();

        assertEquals(Set.of(1, 2, 3, 4, 6), admitted);
    }

    @Test
    void priorityDecidesWhoGetsTheBudgetAndRunningGifsAreNotDroppedForTinyDifferences() {
        Set<Integer> first = AdmissionControl.select(List.of(gif(1, 0.9, 6, false), gif(2, 0.7, 6, false), gif(3, 0.8, 6, false)), 6 * 1_048_576.0).keySet();
        assertEquals(Set.of(1, 3), first);

        Set<Integer> second = AdmissionControl.select(List.of(gif(1, 0.9, 6, false), gif(2, 0.8, 6, true), gif(3, 0.85, 6, false)), 6 * 1_048_576.0).keySet();
        assertEquals(Set.of(1, 2), second, "the gif that is already animating keeps its place when priorities are close");
    }

    @Test
    void nothingIsAdmittedWhenThereAreNoCandidates() {
        assertTrue(AdmissionControl.select(List.of(), 1_000_000).isEmpty());
    }
}
