/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

/// Test-only entry point that builds an FcgiService against an arbitrary
/// already-listening fd, bypassing the fd-0/CGI-detection logic in create().
public final class FcgiServiceTestFactory {
    private FcgiServiceTestFactory() {}

    public static FcgiService createOnFd(int listenFd) {
        return FcgiService.forListenFd(listenFd);
    }
}
