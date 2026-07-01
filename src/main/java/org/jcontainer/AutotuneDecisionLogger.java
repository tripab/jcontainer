package org.jcontainer;

import com.google.gson.Gson;

/**
 * Sink for one structured autotune decision record per control-loop cycle.
 */
@FunctionalInterface
public interface AutotuneDecisionLogger {

    Gson GSON = new Gson();

    void log(AutotuneDecisionLogEntry entry);

    static AutotuneDecisionLogger stderr() {
        return entry -> System.err.println(GSON.toJson(entry));
    }
}
