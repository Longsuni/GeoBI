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
     * 按当前登录用户的 Datart {@code username} 匹配外部 {@code sys_user.username}，返回首行；未匹配则返回 {@code null}。
     */
    Map<String, Object> loadExternalSysUserForCurrentLogin() throws Exception;

    /**
     * 未登录场景：按给定 {@code username} 查询外部 {@code sys_user}（与 execute/test 同源 SQL），返回首行。
     */
    Map<String, Object> loadExternalSysUserByUsername(String username) throws Exception;
}
