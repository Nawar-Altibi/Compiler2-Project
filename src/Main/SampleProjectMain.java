package Main;

import java.util.Arrays;

/**
 * IntelliJ-friendly entry point for the rubric sample project.
 *
 * <p>Run this class with no arguments to generate
 * {@code Tests/generation/sample_project}. To generate another project, pass
 * its directory as the first program argument. Options such as {@code --quiet}
 * may be passed on their own and are applied to the sample project.</p>
 */
public final class SampleProjectMain {
    private static final String SAMPLE_PROJECT = "Tests/generation/sample_project";

    private SampleProjectMain() {
    }

    public static void main(String[] args) {
        String[] effectiveArgs = effectiveArgs(args == null ? new String[0] : args);
        int exitCode = UnifiedMain.run(effectiveArgs, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static String[] effectiveArgs(String[] args) {
        if (args.length == 0) {
            return new String[] {SAMPLE_PROJECT};
        }
        if (args[0].startsWith("--")) {
            String[] withSample = new String[args.length + 1];
            withSample[0] = SAMPLE_PROJECT;
            System.arraycopy(args, 0, withSample, 1, args.length);
            return withSample;
        }
        return Arrays.copyOf(args, args.length);
    }
}
