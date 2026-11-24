package net.ed1thy.emage.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionControlTest {

    private static AdmissionControl.Candidate gif(int id, double priority, double demandMb, boolean was) {
        return new AdmissionControl.Candidate(id, priority, demandMb * 1_048_576.0, was);
    }

    @Test
    void theGifInFocusAlwaysAnimatesEvenWhenItAloneIsTooExpensive() {
        Set<Integer> admitted = AdmissionControl.select(List.of(gif(1, 1.0, 50, false), gif(2, 0.3, 1, false)), 8 * 1_048_576.0);

        assertTrue(admitted.contains(1));
        assertFalse(admitted.contains(2), "nothing else fits next to a gif that needs far more than the budget");
    }

    @Test
    void cheapGifsAreAdmittedUntilTheBudgetIsUsedAndExpensiveOnesWait() {
        Set<Integer> admitted = AdmissionControl.select(List.of(
                gif(1, 1.0, 2, false), gif(2, 1.0, 2, false), gif(3, 1.0, 2, false), gif(4, 1.0, 2, false), gif(5, 1.0, 40, false), gif(6, 1.0, 2, false)),
                8 * 1_048_576.0);

        assertEquals(Set.of(1, 2, 3, 4, 6), admitted);
    }

    @Test
    void priorityDecidesWhoGetsTheBudgetAndRunningGifsAreNotDroppedForTinyDifferences() {
        Set<Integer> first = AdmissionControl.select(List.of(gif(1, 0.9, 6, false), gif(2, 0.7, 6, false)), 6 * 1_048_576.0);
        assertEquals(Set.of(1), first);

        Set<Integer> second = AdmissionControl.select(List.of(gif(1, 0.9, 6, false), gif(2, 0.8, 6, true)), 6 * 1_048_576.0);
        assertEquals(Set.of(2), second, "the gif that is already animating keeps its place when priorities are close");
    }

    @Test
    void nothingIsAdmittedWhenThereAreNoCandidates() {
        assertTrue(AdmissionControl.select(List.of(), 1_000_000).isEmpty());
    }
}
