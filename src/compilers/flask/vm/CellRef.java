package compilers.flask.vm;

import java.util.Objects;

/** Internal operand-stack reference to a shared closure cell. */
public final class CellRef implements VmStackValue {
    private final Cell cell;

    public CellRef(Cell cell) {
        this.cell = Objects.requireNonNull(cell, "cell");
    }

    public Cell getCell() {
        return cell;
    }

    @Override
    public String toString() {
        return "<cell-ref>";
    }
}
