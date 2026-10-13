package com.chengyinhub.business.domain.dto;

/**
 * 主题编辑-请求参数。字段与 {@link TopicCreateDTO} 完全一致,只多一个 id。
 *
 * <p>为什么不复用 TopicCreateDTO 加个 id:create 的入参形状是「新建」的契约,
 * 给它加一个只有 update 才有意义的主键,下一个人就会问「create 传 id 会怎样」。
 */
public class TopicUpdateDTO extends TopicCreateDTO {

    private static final long serialVersionUID = 1L;

    /** 被编辑的主题 id */
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
