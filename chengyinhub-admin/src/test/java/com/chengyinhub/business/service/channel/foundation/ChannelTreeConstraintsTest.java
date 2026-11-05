package com.chengyinhub.business.service.channel.foundation;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChannelTreeConstraintsTest {
    private ChannelDefinition category(long id, long parent) {
        return new ChannelDefinition(id,1,7,parent,ChannelDefinition.Kind.CATEGORY,"c"+id,
                null,1,false,ChannelDefinition.Status.ACTIVE,1,0,true,0,0,0,null,false);
    }
    @Test void topLevelNeedsNoAncestors() {
        ChannelTreeConstraints.requireAcyclicParentChain(category(1,0),Map.of());
    }
    @Test void missingAncestorIsNotSilentlyTreatedAsRoot() {
        assertThrows(IllegalArgumentException.class, () -> ChannelTreeConstraints.requireAcyclicParentChain(category(1,2),Map.of()));
    }
    @Test void indirectCyclesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChannelTreeConstraints.requireAcyclicParentChain(
                category(1,2),Map.of(2L,category(2,3),3L,category(3,1))));
    }
    @Test void thirtyTwoAncestorsAllowedButThirtyThreeRejected() {
        Map<Long,ChannelDefinition> ancestors = new HashMap<>();
        for (long id=2; id<=33; id++) ancestors.put(id,category(id,id==33?0:id+1));
        ChannelTreeConstraints.requireAcyclicParentChain(category(1,2),ancestors);
        ancestors.put(33L,category(33,34));
        ancestors.put(34L,category(34,0));
        assertThrows(IllegalArgumentException.class, () -> ChannelTreeConstraints.requireAcyclicParentChain(category(1,2),ancestors));
    }
    @Test void parentMapKeyCannotSubstituteAnotherChannel() {
        assertThrows(IllegalArgumentException.class, () -> ChannelTreeConstraints.requireAcyclicParentChain(category(1,2),Map.of(2L,category(3,0))));
    }
}
