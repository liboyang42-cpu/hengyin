package com.chengyinhub.business.domain;

/** theme_state_key 上的属性标记(文档二 A2 / A3):是属性的键与它的类型预设。 */
public class ThemeAttributeMark {

    private String stateKey;

    /** observe / nerve / body / insight / agility / charm / fortune / custom。 */
    private String preset;

    public String getStateKey() { return stateKey; }
    public void setStateKey(String stateKey) { this.stateKey = stateKey; }

    public String getPreset() { return preset; }
    public void setPreset(String preset) { this.preset = preset; }
}
