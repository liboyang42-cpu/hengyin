package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.IpCharacter;

import java.util.List;

/**
 * P3 IP角色 Mapper。
 */
public interface IpCharacterMapper {
    List<IpCharacter> selectPublishedCharacters(Long worldId);
}
