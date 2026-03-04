/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

/// A wrapper exception specific to FCGI library.
public class FCGIException extends RuntimeException {
    public FCGIException(Throwable e) {
        super(e);
    }

    public FCGIException(String s, int err) {
        super(s + " failed with " + err);
    }

    public FCGIException(String s) {
        super(s);
    }

    public FCGIException(String s, Throwable e) {
        super(s, e);
    }
}
