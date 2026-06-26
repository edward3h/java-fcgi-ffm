/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

/// The FastCGI record types this implementation needs (FCGI_GET_VALUES and
/// FCGI_UNKNOWN_TYPE management records are intentionally not handled - see
/// FcgiConnection's class doc for why that's an accepted limitation here).
public enum RecordType {
    BEGIN_REQUEST(1),
    ABORT_REQUEST(2),
    END_REQUEST(3),
    PARAMS(4),
    STDIN(5),
    STDOUT(6),
    STDERR(7),
    DATA(8),
    GET_VALUES(9),
    GET_VALUES_RESULT(10),
    UNKNOWN_TYPE(11);

    public final int code;

    RecordType(int code) {
        this.code = code;
    }

    public static RecordType fromCode(int code) {
        for (var type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown FastCGI record type: " + code);
    }
}
