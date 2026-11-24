package net.ed1thy.emage.render;

import net.ed1thy.emage.model.MapFrameUpdate;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public interface FrameSink {

    boolean enqueueUpdate(@NotNull UUID uuid, @NotNull MapFrameUpdate update, int tier);

    double animationDrainSeconds(@NotNull UUID uuid, long extraBytes);

    long stillBudget(@NotNull UUID uuid, int tier);

    long animationBudget(@NotNull UUID uuid);

    long animationFullBudget(@NotNull UUID uuid, boolean lowPriority);

    void spendAnimationBudget(@NotNull UUID uuid, long bytes);
}
