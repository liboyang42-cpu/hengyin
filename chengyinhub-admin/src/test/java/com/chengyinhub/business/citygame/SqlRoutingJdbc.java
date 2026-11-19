package com.chengyinhub.business.citygame;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.mockito.Mockito.mock;

/**
 * Test double for {@link JdbcTemplate}: answers by the first route whose SQL fragment the statement contains, records
 * every statement with its arguments. Unrouted reads return an empty list / null, unrouted writes return {@code defaultUpdate}.
 */
final class SqlRoutingJdbc {
    record Call(String method, String sql, Object[] args) {}

    final Map<String, Function<Object[], Object>> routes = new LinkedHashMap<>();
    final List<Call> calls = new ArrayList<>();
    int defaultUpdate = 1;
    final JdbcTemplate jdbc;

    SqlRoutingJdbc() {
        jdbc = mock(JdbcTemplate.class, invocation -> {
            Object[] all = invocation.getArguments();
            if (all.length == 0 || !(all[0] instanceof String sql)) return null;
            Object[] args = Arrays.copyOfRange(all, 1, all.length);
            String method = invocation.getMethod().getName();
            calls.add(new Call(method, sql, args));
            for (Map.Entry<String, Function<Object[], Object>> r : routes.entrySet()) {
                if (sql.contains(r.getKey())) return r.getValue().apply(args);
            }
            return switch (method) {
                case "queryForList" -> new ArrayList<>();
                case "update" -> defaultUpdate;
                default -> null;
            };
        });
    }

    SqlRoutingJdbc on(String fragment, Function<Object[], Object> answer) {
        routes.put(fragment, answer);
        return this;
    }

    SqlRoutingJdbc rows(String fragment, List<Map<String, Object>> rows) {
        return on(fragment, a -> new ArrayList<>(rows));
    }

    List<Call> calls(String fragment) {
        return calls.stream().filter(c -> c.sql().contains(fragment)).toList();
    }
}
