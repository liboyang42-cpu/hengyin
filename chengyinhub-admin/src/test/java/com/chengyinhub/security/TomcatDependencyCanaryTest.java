package com.chengyinhub.security;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.apache.tomcat.websocket.server.WsServerContainer;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.websocket.OnMessage;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomcatDependencyCanaryTest {

    @Test
    void 嵌入式Tomcat必须解析为安全点版本() {
        assertEquals("9.0.120.0", ServerInfo.getServerNumber());
    }

    @Test
    void 嵌入式Tomcat经真实网络栈处理登录上传畸形chunk并装载WebSocket端点() throws Exception {
        Path baseDir = Files.createTempDirectory("tomcat-dependency-canary-");
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        Context context = tomcat.addContext("", baseDir.toString());
        Wrapper canary = Tomcat.addServlet(context, "canary", new CanaryServlet());
        canary.addMapping("/login");
        canary.addMapping("/upload");
        Tomcat.addServlet(context, "websocket-canary", new WebSocketUpgradeServlet()).addMapping("/ws-canary");
        context.addServletContainerInitializer(new WebSocketCanaryInitializer(), Collections.emptySet());

        try {
            tomcat.start();
            int port = tomcat.getConnector().getLocalPort();

            String login = send(port, "GET /login HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(login.startsWith("HTTP/1.1 200"), login);
            assertTrue(login.endsWith("login-ok"), login);

            String upload = send(port, "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n"
                    + "Connection: close\r\n\r\nfile");
            assertTrue(upload.startsWith("HTTP/1.1 200"), upload);
            assertTrue(upload.endsWith("received=4"), upload);

            String malformedChunk = send(port, "POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n1;=\r\nx\r\n0\r\n\r\n");
            assertTrue(malformedChunk.startsWith("HTTP/1.1 400"), malformedChunk);

            assertWebSocketEcho(port);
        } finally {
            tomcat.stop();
            tomcat.destroy();
            deleteTree(baseDir);
        }
    }

    private static String send(int port, String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            return new String(readAll(socket.getInputStream()), StandardCharsets.ISO_8859_1);
        }
    }

    private static void assertWebSocketEcho(int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /ws-canary HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: QUFBQUFBQUFBQUFBQUFBQQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            String handshake = readHeaders(socket.getInputStream());
            assertTrue(handshake.startsWith("HTTP/1.1 101"), handshake);
            assertTrue(handshake.contains("Sec-WebSocket-Accept: uZFFNKUXmCHTUIyb2Ne3MPSqlt0="), handshake);

            byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);
            byte[] mask = new byte[]{1, 2, 3, 4};
            writeMaskedFrame(output, 0x81, payload, mask);
            output.flush();

            InputStream input = socket.getInputStream();
            assertEquals(0x81, input.read());
            int length = input.read() & 0x7f;
            byte[] echoed = new byte[length];
            readFully(input, echoed);
            assertEquals("echo:ping", new String(echoed, StandardCharsets.UTF_8));

            writeMaskedFrame(output, 0x88, new byte[]{0x03, (byte) 0xE8}, mask);
            output.flush();
            assertEquals(0x88, input.read());
            int closeLength = input.read() & 0x7f;
            assertEquals(2, closeLength);
            byte[] closePayload = new byte[closeLength];
            readFully(input, closePayload);
            assertEquals(0x03, closePayload[0] & 0xff);
            assertEquals(0xE8, closePayload[1] & 0xff);
        }
    }

    private static void writeMaskedFrame(OutputStream output, int opcode, byte[] payload, byte[] mask) throws IOException {
        output.write(opcode);
        output.write(0x80 | payload.length);
        output.write(mask);
        for (int index = 0; index < payload.length; index++) {
            output.write(payload[index] ^ mask[index % mask.length]);
        }
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("WebSocket 握手响应提前结束");
            }
            bytes.write(next);
            byte[] response = bytes.toByteArray();
            int size = response.length;
            if (size >= 4 && response[size - 4] == '\r' && response[size - 3] == '\n'
                    && response[size - 2] == '\r' && response[size - 1] == '\n') {
                return new String(response, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int read;
        while ((read = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    private static void readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read == -1) {
                throw new IOException("WebSocket 响应提前结束");
            }
            offset += read;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        File[] children = root.toFile().listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child.toPath());
            }
        }
        Files.deleteIfExists(root);
    }

    public static class CanaryServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.getWriter().write("login-ok");
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            int count = 0;
            while (request.getInputStream().read() != -1) {
                count++;
            }
            response.getWriter().write("received=" + count);
        }
    }

    public static class EchoEndpoint {
        @OnMessage
        public String echo(String message) {
            return "echo:" + message;
        }
    }

    public static class WebSocketUpgradeServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            Object value = getServletContext().getAttribute(ServerContainer.class.getName());
            if (!(value instanceof WsServerContainer)) {
                throw new ServletException("Tomcat 未安装 WebSocket ServerContainer");
            }
            try {
                ((WsServerContainer) value).upgradeHttpToWebSocket(request, response,
                        ServerEndpointConfig.Builder.create(EchoEndpoint.class, "/ws-canary").build(),
                        Collections.<String, String>emptyMap());
            } catch (javax.websocket.DeploymentException error) {
                throw new ServletException("Tomcat 无法升级 WebSocket canary", error);
            }
        }
    }

    public static class WebSocketCanaryInitializer implements ServletContainerInitializer {
        @Override
        public void onStartup(java.util.Set<Class<?>> classes, ServletContext context) throws ServletException {
            new WsSci().onStartup(Collections.<Class<?>>emptySet(), context);
        }
    }
}
