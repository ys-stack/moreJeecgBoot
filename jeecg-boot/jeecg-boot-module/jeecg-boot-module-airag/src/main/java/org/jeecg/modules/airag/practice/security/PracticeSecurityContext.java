package org.jeecg.modules.airag.practice.security;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.jeecg.common.system.vo.LoginUser;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * @Author: ys
 * @Date: 2026/7/27 星期一 23:07
 * @Desc: 统一身份上下文
 */
@Component
public class PracticeSecurityContext {

    /**
     * 获取当前登录用户；身份缺失时直接拒绝，禁止安全逻辑降级为匿名用户。
     */
    public LoginUser requireUser() {
        Object principal;
        try {
            principal = SecurityUtils.getSubject().getPrincipal();
        } catch (Exception e) {
            throw new AuthenticationException("用户未登录", e);
        }
        if (!(principal instanceof LoginUser user) || user.getId() == null) {
            throw new AuthenticationException("用户未登录");
        }
        return user;
    }

    /**
     * 将登录用户的逗号分隔角色编码解析为精确匹配集合。
     */
    public Set<String> roles(LoginUser user) {
        if (user == null || user.getRoleCode() == null || user.getRoleCode().isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(user.getRoleCode().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 判断当前用户是否拥有 admin 角色。
     */
    public boolean isAdmin(LoginUser user) {
        return roles(user).contains("admin");
    }
}
