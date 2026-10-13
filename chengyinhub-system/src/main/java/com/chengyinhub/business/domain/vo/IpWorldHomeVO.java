package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.IpCharacter;
import com.chengyinhub.business.domain.IpCityChapter;
import com.chengyinhub.business.domain.IpQuest;
import com.chengyinhub.business.domain.IpWorld;

import java.util.Collections;
import java.util.List;

/**
 * P3 IP世界首页视图。
 */
public class IpWorldHomeVO {
    private IpWorld world;
    private List<IpWorld> worlds = Collections.emptyList();
    private List<IpCityChapter> chapters = Collections.emptyList();
    private List<IpCharacter> characters = Collections.emptyList();
    private List<IpQuest> quests = Collections.emptyList();

    public IpWorld getWorld() { return world; }
    public void setWorld(IpWorld world) { this.world = world; }
    public List<IpWorld> getWorlds() { return worlds; }
    public void setWorlds(List<IpWorld> worlds) { this.worlds = worlds; }
    public List<IpCityChapter> getChapters() { return chapters; }
    public void setChapters(List<IpCityChapter> chapters) { this.chapters = chapters; }
    public List<IpCharacter> getCharacters() { return characters; }
    public void setCharacters(List<IpCharacter> characters) { this.characters = characters; }
    public List<IpQuest> getQuests() { return quests; }
    public void setQuests(List<IpQuest> quests) { this.quests = quests; }
}
