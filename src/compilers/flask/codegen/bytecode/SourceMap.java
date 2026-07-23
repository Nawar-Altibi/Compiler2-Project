package compilers.flask.codegen.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Dense immutable instruction-offset to source-location map. */
public final class SourceMap {
    private final List<InstructionLocation> locations;

    public SourceMap(List<InstructionLocation> locations) {
        Objects.requireNonNull(locations, "locations");
        List<InstructionLocation> copy = new ArrayList<>(locations.size());
        for (InstructionLocation location : locations) {
            copy.add(Objects.requireNonNull(location, "instruction location"));
        }
        this.locations = Collections.unmodifiableList(copy);
    }

    public int size() {
        return locations.size();
    }

    public InstructionLocation get(int offset) {
        return locations.get(offset);
    }

    public List<InstructionLocation> getLocations() {
        return locations;
    }
}
