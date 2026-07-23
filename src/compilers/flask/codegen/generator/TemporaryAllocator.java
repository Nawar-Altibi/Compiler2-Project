package compilers.flask.codegen.generator;

import java.util.BitSet;

/** Deterministic allocator for compiler-hidden {@code PyValue} slots. */
public final class TemporaryAllocator {
    private final BitSet active = new BitSet();
    private int slotCount;

    /** Allocates the lowest currently-free slot. */
    public int allocate() {
        int slot = active.nextClearBit(0);
        active.set(slot);
        slotCount = Math.max(slotCount, slot + 1);
        return slot;
    }

    /** Releases a lexical allocation after all of its control-flow paths were emitted. */
    public void release(int slot) {
        if (slot < 0 || slot >= slotCount || !active.get(slot)) {
            throw new IllegalStateException("Temporary slot is not active: " + slot);
        }
        active.clear(slot);
    }

    public boolean isActive(int slot) {
        return slot >= 0 && active.get(slot);
    }

    /** Number of slots required by the finalized frame, including reused slots. */
    public int getSlotCount() {
        return slotCount;
    }

    public boolean hasActiveSlots() {
        return !active.isEmpty();
    }
}
