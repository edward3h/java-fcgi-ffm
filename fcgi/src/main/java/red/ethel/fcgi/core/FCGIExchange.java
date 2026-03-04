/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/// The basic low-level interface to use CGI or FCGI
public record FCGIExchange(Map<String, String> env, InputStream in, OutputStream out) {}
