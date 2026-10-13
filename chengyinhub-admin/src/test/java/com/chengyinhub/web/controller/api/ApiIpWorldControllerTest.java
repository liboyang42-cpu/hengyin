package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.IpCharacter;
import com.chengyinhub.business.domain.IpCityChapter;
import com.chengyinhub.business.domain.IpQuest;
import com.chengyinhub.business.domain.IpWorld;
import com.chengyinhub.business.domain.dto.IpWorldQueryDTO;
import com.chengyinhub.business.domain.vo.IpWorldHomeVO;
import com.chengyinhub.business.mapper.IpCharacterMapper;
import com.chengyinhub.business.mapper.IpCityChapterMapper;
import com.chengyinhub.business.mapper.IpQuestMapper;
import com.chengyinhub.business.mapper.IpWorldMapper;
import com.chengyinhub.business.service.IIpWorldService;
import com.chengyinhub.business.service.impl.IpWorldServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiIpWorldControllerTest {

    @Test
    void worldHomeReturnsPublishedWorldWithChildren() {
        IpWorldMapper worldMapper = mock(IpWorldMapper.class);
        IpCityChapterMapper chapterMapper = mock(IpCityChapterMapper.class);
        IpCharacterMapper characterMapper = mock(IpCharacterMapper.class);
        IpQuestMapper questMapper = mock(IpQuestMapper.class);
        IpWorldServiceImpl service = service(worldMapper, chapterMapper, characterMapper, questMapper);
        when(worldMapper.selectPublishedWorlds("310000")).thenReturn(Collections.singletonList(world(7L, "310000", "城瘾上海")));
        when(chapterMapper.selectPublishedChapters(7L)).thenReturn(Collections.singletonList(chapter(71L)));
        when(characterMapper.selectPublishedCharacters(7L)).thenReturn(Collections.singletonList(character(72L)));
        when(questMapper.selectPublishedQuests(7L)).thenReturn(Collections.singletonList(quest(73L)));

        IpWorldQueryDTO query = new IpWorldQueryDTO();
        query.setCityCode("310000");
        IpWorldHomeVO result = service.getHome(query);

        assertEquals(Long.valueOf(7L), result.getWorld().getId());
        assertEquals(1, result.getChapters().size());
        assertEquals(1, result.getCharacters().size());
        assertEquals(1, result.getQuests().size());
    }

    @Test
    void worldHomeKeepsEmptyArraysWhenNoWorldExists() {
        IpWorldMapper worldMapper = mock(IpWorldMapper.class);
        IpWorldServiceImpl service = service(worldMapper, mock(IpCityChapterMapper.class), mock(IpCharacterMapper.class), mock(IpQuestMapper.class));
        when(worldMapper.selectPublishedWorlds(null)).thenReturn(Collections.emptyList());

        IpWorldHomeVO result = service.getHome(new IpWorldQueryDTO());

        assertEquals(null, result.getWorld());
        assertEquals(0, result.getChapters().size());
        assertEquals(0, result.getCharacters().size());
        assertEquals(0, result.getQuests().size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void controllerReturnsWorldHome() {
        IIpWorldService mockService = mock(IIpWorldService.class);
        ApiIpWorldController controller = new ApiIpWorldController();
        ReflectionTestUtils.setField(controller, "ipWorldService", mockService);
        IpWorldHomeVO home = new IpWorldHomeVO();
        home.setWorld(world(3L, "310000", "城瘾上海"));
        when(mockService.getHome(any(IpWorldQueryDTO.class))).thenReturn(home);

        AjaxResult result = controller.home("310000", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        IpWorldHomeVO data = (IpWorldHomeVO) result.get(AjaxResult.DATA_TAG);
        assertEquals("城瘾上海", data.getWorld().getWorldName());
    }

    @Test
    void listPublishedWorldsUsesCityFilter() {
        IpWorldMapper worldMapper = mock(IpWorldMapper.class);
        IpWorldServiceImpl service = service(worldMapper, mock(IpCityChapterMapper.class), mock(IpCharacterMapper.class), mock(IpQuestMapper.class));
        when(worldMapper.selectPublishedWorlds("310000")).thenReturn(Arrays.asList(world(1L, "310000", "A")));

        assertEquals(1, service.listPublishedWorlds("310000").size());
        verify(worldMapper).selectPublishedWorlds(eq("310000"));
    }

    private static IpWorldServiceImpl service(IpWorldMapper worldMapper, IpCityChapterMapper chapterMapper,
                                              IpCharacterMapper characterMapper, IpQuestMapper questMapper) {
        IpWorldServiceImpl service = new IpWorldServiceImpl();
        ReflectionTestUtils.setField(service, "ipWorldMapper", worldMapper);
        ReflectionTestUtils.setField(service, "ipCityChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "ipCharacterMapper", characterMapper);
        ReflectionTestUtils.setField(service, "ipQuestMapper", questMapper);
        return service;
    }

    private static IpWorld world(Long id, String cityCode, String name) {
        IpWorld world = new IpWorld();
        world.setId(id);
        world.setCityCode(cityCode);
        world.setWorldName(name);
        world.setStatus("0");
        world.setDeleted("0");
        return world;
    }

    private static IpCityChapter chapter(Long id) {
        IpCityChapter chapter = new IpCityChapter();
        chapter.setId(id);
        chapter.setStatus("0");
        return chapter;
    }

    private static IpCharacter character(Long id) {
        IpCharacter character = new IpCharacter();
        character.setId(id);
        character.setStatus("0");
        return character;
    }

    private static IpQuest quest(Long id) {
        IpQuest quest = new IpQuest();
        quest.setId(id);
        quest.setStatus("0");
        return quest;
    }
}
