package com.chengyinhub.business.citygame.operations;

/** 显式缺失端口，只给隔离测试使用，不注册成生产 Bean。 */
public class UnavailableCityGameOperationsFacts implements CityGameOperationsFactPort {

    @Override
    public Slot read(String slot, String actionId, String regionId) {
        return Slot.missing("MISSING");
    }
}
