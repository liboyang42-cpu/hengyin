package com.chengyinhub.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.business.domain.dto.ImPollCloseCommand;
import com.chengyinhub.business.domain.dto.ImPollCreateCommand;
import com.chengyinhub.business.domain.dto.ImPollVoteCommand;
import com.chengyinhub.business.service.IImPollService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;

@RestController
@RequestMapping("/api/im/poll")
public class ApiImPollController extends BaseController {
    @Autowired
    private IImPollService pollService;

    @PostMapping("/create")
    public AjaxResult create(@RequestBody ImPollCreateCommand command) {
        return invoke(() -> pollService.create(getAppUserId(), command));
    }

    @PostMapping("/vote")
    public AjaxResult vote(@RequestBody ImPollVoteCommand command) {
        return invoke(() -> pollService.vote(getAppUserId(), command));
    }

    @PostMapping("/close")
    public AjaxResult close(@RequestBody ImPollCloseCommand command) {
        return invoke(() -> pollService.close(getAppUserId(), command));
    }

    @PostMapping("/result")
    public AjaxResult result(String poll_id) {
        Long id = com.chengyinhub.common.core.text.Convert.toLong(poll_id, 0L);
        return invoke(() -> pollService.result(getAppUserId(), id));
    }

    private AjaxResult invoke(Action action) {
        try {
            return success(action.run());
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        } catch (Exception e) {
            logger.error("群聊投票接口异常", e);
            return error("操作失败，请稍后重试");
        }
    }

    private interface Action { Object run(); }
}
