
package io.github.p4grecomp;

/**
 * Extracts the player's game and prepares a local Maxwell shader cache in a private staging directory.
 */
final class Importer {
    static {
        System.loadLibrary("p4gimport");
    }

    static native int run(
        int nspFd,
        String nspName,
        int updateFd,
        String updateName,
        String keysPath,
        String titleKeysPath,
        String dataDir,
        String workDir,
        int jobs
    );

    static native int[] progress();
    static native void reset();
    static native void cancel();
    static native long[] bytes();

    static native String error();

    private Importer() {}
}
