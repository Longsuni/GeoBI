/*
 * Datart
 * Copyright 2021
 * Licensed under the Apache License, Version 2.0
 */

package datart.server.base.params;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class JumpLoginParam {

    @NotBlank(message = "Username can not be empty")
    private String username;
}
