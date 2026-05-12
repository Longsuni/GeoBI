/*
 * Datart
 * Copyright 2021
 * Licensed under the Apache License, Version 2.0
 */

package datart.server.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 外部系统约定的统一响应：code / data / message / status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalApiResponse<T> {

    private int code;

    private T data;

    private String message;

    /** 成功：{@code success}；失败：{@code fail} */
    private String status;

    public static <T> ExternalApiResponse<T> success(T data, String message) {
        ExternalApiResponse<T> r = new ExternalApiResponse<>();
        r.setCode(200);
        r.setData(data);
        r.setMessage(message);
        r.setStatus("success");
        return r;
    }

    public static <T> ExternalApiResponse<T> fail(int code, String message) {
        ExternalApiResponse<T> r = new ExternalApiResponse<>();
        r.setCode(code);
        r.setData(null);
        r.setMessage(message);
        r.setStatus("fail");
        return r;
    }
}
