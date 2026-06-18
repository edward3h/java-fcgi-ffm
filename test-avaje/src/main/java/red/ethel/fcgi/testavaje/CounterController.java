/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testavaje;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.Post;
import io.avaje.jex.http.NotFoundException;
import java.util.List;

@Controller
@Path("/db/counters")
class CounterController {
    private final CounterDAO dao;

    CounterController(CounterDAO dao) {
        this.dao = dao;
    }

    @Get
    List<CounterDAO.Counter> getAll() {
        return dao.findAll();
    }

    @Post("/{name}/increment")
    CounterDAO.Counter increment(String name) {
        if (!dao.incrementByName(name)) {
            throw new NotFoundException("Not found: " + name);
        }
        return dao.findByName(name);
    }
}
