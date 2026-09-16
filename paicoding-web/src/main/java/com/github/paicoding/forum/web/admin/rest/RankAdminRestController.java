package com.github.paicoding.forum.web.admin.rest;

import com.github.paicoding.forum.api.model.enums.rank.ActivityRankTimeEnum;
import com.github.paicoding.forum.api.model.vo.ResVo;
import com.github.paicoding.forum.core.permission.Permission;
import com.github.paicoding.forum.core.permission.UserRole;
import com.github.paicoding.forum.service.rank.service.UserActivityRankService;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Permission(role = UserRole.ADMIN)
@RequestMapping(path = {"/api/admin/rank/", "/admin/rank/"})
public class RankAdminRestController {
    @Autowired
    private UserActivityRankService userActivityRankService;

    @ApiOperation("从 MySQL 行为日志重建活跃排行榜")
    @PostMapping("rebuild")
    public ResVo<String> rebuild(@RequestParam(defaultValue = "MONTH") ActivityRankTimeEnum time) {
        userActivityRankService.rebuildRank(time);
        return ResVo.ok();
    }
}
