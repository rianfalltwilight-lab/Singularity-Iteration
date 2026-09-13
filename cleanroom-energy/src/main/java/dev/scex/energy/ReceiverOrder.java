// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** Independent recipient priority policy fitted to public R14 fanout observations. */
public final class ReceiverOrder {
    private ReceiverOrder() { }

    /**
     * Preserve registration priority every fourth world tick. Otherwise choose a
     * uniform permutation of eligible recipients, using the caller's generator.
     * Ineligible entries remain in the complete result after eligible entries so
     * packet accounting retains stable receiver IDs. They consume no randomness.
     * This models the observed marginals and fixed phase, not an original PRNG.
     */
    public static int[] create(int[] registrationOrder, boolean[] eligible,
                               long worldTime, RandomGenerator random) {
        Objects.requireNonNull(registrationOrder, "registrationOrder");
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(random, "random");
        if (worldTime < 0 || registrationOrder.length != eligible.length) {
            throw new IllegalArgumentException("Invalid time or receiver vectors");
        }
        int size = eligible.length, count = 0;
        boolean[] seen = new boolean[size];
        for (int receiver : registrationOrder) {
            if (receiver < 0 || receiver >= size || seen[receiver]) {
                throw new IllegalArgumentException("Registration order must be a complete permutation");
            }
            seen[receiver] = true;
            if (eligible[receiver]) count++;
        }
        int[] result = new int[size];
        int active = 0, inactive = count;
        for (int receiver : registrationOrder) {
            if (eligible[receiver]) result[active++] = receiver;
            else result[inactive++] = receiver;
        }
        if (worldTime % 4 != 0) {
            for (int end = count - 1; end > 0; end--) {
                int other = random.nextInt(end + 1);
                int saved = result[end]; result[end] = result[other]; result[other] = saved;
            }
        }
        return result;
    }
}
