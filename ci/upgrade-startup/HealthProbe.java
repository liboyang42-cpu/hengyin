import com.sun.tools.attach.VirtualMachine;
import javax.management.*;
import javax.management.remote.*;
import javax.management.openmbean.CompositeData;
import java.util.Map;

/** Container-local attach only; no remote management listener or production token. */
public class HealthProbe {
    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        String address;
        try { address = vm.startLocalManagementAgent(); } finally { vm.detach(); }
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            var connection = connector.getMBeanServerConnection();
            var endpoint = new ObjectName("org.springframework.boot:type=Endpoint,name=Health");
            Object health = connection.invoke(endpoint, "health", null, null);
            System.out.println("ACTUATOR_AGGREGATE_HEALTH=" + health);
            requireStatus(health, "DOWN");
            Object components = field(health, "components");
            Object payment = field(components, "wechatPay");
            requireStatus(payment, "DOWN");
            if (!"unavailable".equals(field(field(payment, "details"), "state")))
                throw new AssertionError("Disabled payment must remain unavailable");
            Object startup = connection.invoke(endpoint, "healthForPath",
                    new Object[]{new String[]{"isolatedStartup"}}, new String[]{"[Ljava.lang.String;"});
            System.out.println("ACTUATOR_ISOLATED_STARTUP_HEALTH=" + startup);
            requireStatus(startup, "UP");
            for (String required : new String[]{"db", "redis", "livenessState", "readinessState"})
                requireStatus(field(field(startup, "components"), required), "UP");
        }
    }
    private static Object field(Object value, String key) {
        return value instanceof Map<?, ?> map ? map.get(key)
                : value instanceof CompositeData data ? data.get(key) : null;
    }
    private static void requireStatus(Object value, String expected) {
        if (!expected.equals(field(value, "status")))
            throw new AssertionError("Expected health " + expected + ", got " + value);
    }
}
