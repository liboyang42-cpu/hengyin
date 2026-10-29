import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import java.nio.file.*;
import java.util.Iterator;
import java.util.Map;

/** Harness: run the repo's AdvancedGameConfigValidator.validateAndNormalize on each entry of {key: config}. */
public class RunValidator {
    public static void main(String[] args) throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(Files.readString(Path.of(args[0])));
        boolean quiet = args.length > 1 && "--quiet".equals(args[1]);
        int ok = 0, bad = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            String raw = e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString();
            try {
                String out = new AdvancedGameConfigValidator().validateAndNormalize(raw);
                ok++;
                System.out.println("[JAVA OK ] " + e.getKey() + (quiet ? "" : "\n    -> " + out));
            } catch (Exception ex) {
                bad++;
                System.out.println("[JAVA ERR] " + e.getKey() + " :: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        System.out.println("java summary: " + ok + " ok, " + bad + " rejected");
    }
}
