import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Test-only, single-source invocation inside the ORIGINAL finished-workload container. */
class CombinedHttpNamespacePortProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 || !arguments[0].matches("[0-9]{1,5}")) throw new IllegalArgumentException("OWNED_NAMESPACE_PORT_REQUIRED");
        int port = Integer.parseInt(arguments[0]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("OWNED_NAMESPACE_PORT_INVALID");
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        }
        System.out.println("NAMESPACE_REDIS_PORT_FREE " + port);
    }
}
