/*
 * Datart
 * Copyright 2021
 * Licensed under the Apache License, Version 2.0
 */

package datart.server.base.params;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class JumpLoginParam {

    /** 外部库 {@code public.sys_user.user_id}（JSON 字段名 {@code user-id}） */
    @NotBlank(message = "User id can not be empty")
    @JsonProperty("user-id")
    private String userId;
}
