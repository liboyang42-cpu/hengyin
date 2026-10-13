package com.chengyinhub.business.domain.dto;

import java.util.ArrayList;
import java.util.List;

import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.OfficialEventBinding;
import com.chengyinhub.business.domain.OfficialEventFulfillmentRequirement;
import com.chengyinhub.business.domain.OfficialEventMission;
import com.chengyinhub.business.domain.OfficialEventParty;

/** V2 制作台一次保存的活动壳及其四类受控子对象。 */
public class OfficialEventV2DraftRequest {
    private OfficialEvent event;
    private List<OfficialEventBinding> bindings = new ArrayList<>();
    private List<OfficialEventMission> missions = new ArrayList<>();
    private List<OfficialEventParty> parties = new ArrayList<>();
    private List<OfficialEventFulfillmentRequirement> fulfillmentRequirements = new ArrayList<>();

    public OfficialEvent getEvent() { return event; }
    public void setEvent(OfficialEvent event) { this.event = event; }
    public List<OfficialEventBinding> getBindings() { return bindings; }
    public void setBindings(List<OfficialEventBinding> bindings) { this.bindings = bindings == null ? new ArrayList<OfficialEventBinding>() : bindings; }
    public List<OfficialEventMission> getMissions() { return missions; }
    public void setMissions(List<OfficialEventMission> missions) { this.missions = missions == null ? new ArrayList<OfficialEventMission>() : missions; }
    public List<OfficialEventParty> getParties() { return parties; }
    public void setParties(List<OfficialEventParty> parties) { this.parties = parties == null ? new ArrayList<OfficialEventParty>() : parties; }
    public List<OfficialEventFulfillmentRequirement> getFulfillmentRequirements() { return fulfillmentRequirements; }
    public void setFulfillmentRequirements(List<OfficialEventFulfillmentRequirement> fulfillmentRequirements) {
        this.fulfillmentRequirements = fulfillmentRequirements == null ? new ArrayList<OfficialEventFulfillmentRequirement>() : fulfillmentRequirements;
    }
}
