/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testavaje;

import io.avaje.jsonb.Json;
import java.util.List;
import org.ethelred.kiwiproc.annotation.DAO;
import org.ethelred.kiwiproc.annotation.SqlQuery;
import org.ethelred.kiwiproc.annotation.SqlUpdate;
import org.jspecify.annotations.Nullable;

@DAO
public interface CounterDAO {
    // record component named "v" (not "value") and the matching SQL columns
    // below aliased to "v" too, to avoid a kiwiproc 0.11 codegen bug where a
    // result column named "value" collides with an internal generated variable
    // name (https://github.com/edward3h/kiwiproc/issues/379); @Json.Property
    // restores "value" as the wire-format JSON field name.
    @Json
    record Counter(String name, @Json.Property("value") int v) {}

    @SqlQuery("SELECT name, value AS v FROM counter ORDER BY name")
    List<Counter> findAll();

    @SqlUpdate("UPDATE counter SET value = value + 1 WHERE name = :name")
    boolean incrementByName(String name);

    @SqlQuery("SELECT name, value AS v FROM counter WHERE name = :name")
    @Nullable Counter findByName(String name);
}
