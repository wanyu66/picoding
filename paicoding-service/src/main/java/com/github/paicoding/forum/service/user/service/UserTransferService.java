package com.github.paicoding.forum.service.user.service;

/**
 * @author YiHui
 * @date 2025/9/29
 */
public interface UserTransferService {

    /** 通过用户名和密码迁移用户。 */
    boolean transferUser(String uname, String pwd);

    /** 通过知识星球编号迁移用户。 */
    boolean transferUser(String starNumber);
}
