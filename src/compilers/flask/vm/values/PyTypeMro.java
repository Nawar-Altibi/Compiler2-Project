package compilers.flask.vm.values;

import compilers.flask.vm.RuntimeOps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Shared identity-based C3 linearization for {@link PyType} implementations. */
final class PyTypeMro {
    private PyTypeMro() {
    }

    static List<PyType> linearize(PyType owner, List<PyType> bases) {
        List<PyType> result = new ArrayList<>();
        result.add(owner);
        if (bases.isEmpty()) {
            return Collections.unmodifiableList(result);
        }

        List<List<PyType>> sequences = new ArrayList<>(bases.size() + 1);
        for (PyType base : bases) {
            sequences.add(new ArrayList<>(base.getMethodResolutionOrder()));
        }
        sequences.add(new ArrayList<>(bases));

        while (removeEmptySequences(sequences)) {
            PyType candidate = null;
            for (List<PyType> sequence : sequences) {
                PyType head = sequence.get(0);
                if (!appearsInAnyTail(head, sequences)) {
                    candidate = head;
                    break;
                }
            }
            if (candidate == null) {
                throw RuntimeOps.error(
                        "TypeError",
                        "cannot create a consistent method resolution order (MRO)"
                                + " for bases " + baseNames(bases));
            }

            result.add(candidate);
            for (List<PyType> sequence : sequences) {
                if (!sequence.isEmpty() && sequence.get(0) == candidate) {
                    sequence.remove(0);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean removeEmptySequences(List<List<PyType>> sequences) {
        for (Iterator<List<PyType>> iterator = sequences.iterator();
                iterator.hasNext();) {
            if (iterator.next().isEmpty()) {
                iterator.remove();
            }
        }
        return !sequences.isEmpty();
    }

    private static boolean appearsInAnyTail(
            PyType candidate, List<List<PyType>> sequences) {
        for (List<PyType> sequence : sequences) {
            for (int index = 1; index < sequence.size(); index++) {
                if (sequence.get(index) == candidate) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String baseNames(List<PyType> bases) {
        List<String> names = new ArrayList<>(bases.size());
        for (PyType base : bases) {
            names.add(base.getName());
        }
        return names.toString();
    }
}
