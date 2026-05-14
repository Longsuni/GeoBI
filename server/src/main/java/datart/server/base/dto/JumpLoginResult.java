/*
 * Datart
 * Copyright 2021
 * Licensed under the Apache License, Version 2.0
 */

package datart.server.base.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import datart.core.entity.ext.UserBaseInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 外部跳转登录：响应头 {@code Authorization} 携带 token；JSON body 不含 token。
 */
@Data
@NoArgsConstructor
public class JumpLoginResult {

    @JsonIgnore
    private String token;

    private UserBaseInfo user;

    /** 外部库 sys_user 首行；未配置数据源或未查到则为 null */
    private Map<String, Object> externalSysUser;

    /** 本次跳转是否新注册了 Datart 用户（此前库中无该 username） */
    private boolean newlyRegistered;

    public JumpLoginResult(String token, UserBaseInfo user, Map<String, Object> externalSysUser, boolean newlyRegistered) {
        this.token = token;
        this.user = user;
        this.externalSysUser = externalSysUser;
        this.newlyRegistered = newlyRegistered;
    }
}
