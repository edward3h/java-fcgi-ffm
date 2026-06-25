/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.posix;

/// Test-only bridge exposing PosixTestSupport's package-private socket setup
/// to integration tests in other packages.
public final class PosixTestSupportBridge {
    private PosixTestSupportBridge() {}

    public static int listenOn(int port) {
        return PosixTestSupport.listenOn(port);
    }
}
