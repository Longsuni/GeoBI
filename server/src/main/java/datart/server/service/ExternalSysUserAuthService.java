/*
 * Datart
 * <p>
 * Copyright 2021
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package datart.server.service;

import java.util.Map;

/**
 * 通过已配置的 JDBC 数据源读取外部库 {@code public.sys_user}，用于与 Datart 登录用户对齐权限（不包含 password 字段）。
 */
public interface ExternalSysUserAuthService {

    /**
     * 按当前 Datart 登录名解析外部 {@code sys_user}：优先 {@code username}，再按 {@code user_id::text}（与跳转登录以 user_id 作为 Datart 用户名一致）。
     */
    Map<String, Object> loadExternalSysUserForCurrentLogin() throws Exception;

    /**
     * 按外部 {@code sys_user.username} 查询首行（与 execute 校验变量 {@code $EXT_USERNAME$} 语义一致）。
     */
    Map<String, Object> loadExternalSysUserByUsername(String username) throws Exception;

    /**
     * 按外部 {@code sys_user.user_id} 查询首行（用于 jump-login 入参 {@code user-id}）。
     */
    Map<String, Object> loadExternalSysUserByUserId(String userId) throws Exception;
}
