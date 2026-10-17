package com.chengyinhub.business.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

public final class R5ZeroViewFixtures {
    private static final ObjectMapper JSON=new ObjectMapper();
    private R5ZeroViewFixtures() { }

    public static void write(String name,Object source) throws Exception {
        String directory=System.getProperty("chengyin.zeroview.fixtureDir");if(directory==null)return;
        assertTrue(name.matches("[a-z-]+"));JsonNode actual=JSON.valueToTree(source);ObjectNode view=JSON.createObjectNode();
        for(String field:new String[]{"zeroHp","resultCard","hp","hpEnabled","hpMax","status","teamId","isCaptain","scope","runtimeScope","sessionId","version","authorityEpoch","ownerRevision","sceneId","sceneName"})
            if(actual.has(field))view.set(field,actual.get(field).deepCopy());
        for(String field:new String[]{"allowedActions","disabledReason","recoveryMemberId","recoveryMemberName","thenPolicy"})assertTrue(view.path("zeroHp").has(field),field);
        for(String field:new String[]{"baselineDay","baselineSteps","highWaterByDay","openid","code","session_key","encryptedData","iv"})assertFalse(view.path("zeroHp").has(field),field);
        Path folder=Paths.get(directory);Files.createDirectories(folder);
        Files.write(folder.resolve(name+".json"),(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(view)+"\n").getBytes(StandardCharsets.UTF_8));
    }
}
