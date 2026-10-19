package com.chengyinhub.business.city.draft;

import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static com.chengyinhub.business.city.draft.CityBoardDraftService.*;

/** Loopback-only browser test fixture, never packaged as application code. */
public final class CityBoardDraftBrowserHarness {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"--approved-test-authority".equals(args[0])) throw new IllegalArgumentException("Explicit isolated test authority required");
        Path root = Paths.get(args[1]).toAbsolutePath(); int port = Integer.parseInt(args[2]);
        org.h2.jdbcx.JdbcDataSource source = CityBoardDraftPersistenceTest.database(root.resolve("browser-drafts").toString());
        if (!Files.exists(root.resolve("browser-drafts.mv.db"))) CityBoardDraftPersistenceTest.initialize(source);
        CityBoardDraftService service = CityBoardDraftPersistenceTest.service(source, new CityBoardDraftPersistenceTest.TestAuthority());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/") || path.equals("/vue.js")) {
                    if (!"GET".equals(exchange.getRequestMethod())) { reply(exchange, 405, "text/plain", new byte[0]); return; }
                    reply(exchange, 200, path.equals("/") ? "text/html; charset=utf-8" : "text/javascript", Files.readAllBytes(root.resolve(path.equals("/") ? "index.html" : "vue.js"))); return;
                }
                String base = "/business/city-board-drafts";
                require(path.startsWith(base + "/"), 404, "NOT_FOUND");
                Map<String, Object> data;
                if ("GET".equals(exchange.getRequestMethod())) {
                    data = path.equals(base + "/catalog") ? service.catalog() : service.reopen(path.substring(base.length() + 1));
                } else {
                    require("POST".equals(exchange.getRequestMethod()), 405, "METHOD_NOT_ALLOWED");
                    require(("http://127.0.0.1:" + port).equals(exchange.getRequestHeaders().getFirst("Origin"))
                            && "isolated".equals(exchange.getRequestHeaders().getFirst("X-City-Draft-Test")), 403, "TEST_ORIGIN_REQUIRED");
                    ByteArrayOutputStream body = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int read;
                    try (InputStream in = exchange.getRequestBody()) { while ((read = in.read(buffer)) != -1) { body.write(buffer, 0, read); require(body.size() <= 32768, 400, "CITY_DRAFT_TOO_LARGE"); } }
                    @SuppressWarnings("unchecked") Map<String, Object> input = JSON.parseObject(new String(body.toByteArray(), StandardCharsets.UTF_8), Map.class);
                    if (path.equals(base + "/validate")) data = service.validate(input);
                    else if (path.equals(base + "/save")) data = service.save(input);
                    else if (path.equals(base + "/preview")) data = service.preview(input);
                    else throw new Failure(404, "NOT_FOUND");
                }
                reply(exchange, 200, "application/json", JSON.toJSONBytes(object("code", 200, "data", data)));
            } catch (Failure e) { reply(exchange, 200, "application/json", JSON.toJSONBytes(object("code", e.status, "errorCode", e.getMessage(), "msg", e.getMessage()))); }
            catch (Exception e) { reply(exchange, 500, "application/json", JSON.toJSONBytes(object("code", 500, "errorCode", "HARNESS_FAILURE"))); }
        });
        server.start(); System.out.println("ISOLATED TEST ONLY http://127.0.0.1:" + port);
    }
    private static void reply(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type); exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length); try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
